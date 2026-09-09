package com.github.vbmacher.spark_lp

import breeze.linalg.{cholesky, norm, DenseMatrix => BDM, DenseVector => BDV}
import com.github.vbmacher.spark_lp.vectors.dmatrix.implicits._
import com.github.vbmacher.spark_lp.vectors.dvector.implicits._
import com.github.vbmacher.spark_lp.vectors.{DMatrix, DVector}
import com.typesafe.scalalogging.LazyLogging
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.sql.SparkSession
import org.apache.spark.wrappers.{Broadcasts, CholeskyDecomposition}

/**
  * Strategy for solving the m x m normal-equations ("Newton") systems `A^T D^2 A y = r` that the
  * interior-point solver forms during initialization and once per iteration.
  *
  * The choice governs the driver-side footprint of the constraint dimension `m`:
  *
  *  - [[NewtonSolver.Cholesky]]: the classic direct method. The weighted Gramian is aggregated to
  *    the driver (roughly `16*m*m` bytes of related allocations per solve) and factorized there
  *    (`O(m^3)` per iteration). Exact and fast for small `m`; limited to 65535 rows and by driver
  *    memory.
  *  - [[NewtonSolver.ConjugateGradient]]: matrix-free. The Gramian is never materialised; each CG
  *    step applies the operator with the distributed matrix-vector products already used
  *    elsewhere. Its dense iteration vectors use `O(m)` driver memory; the optional partial
  *    Cholesky preconditioner uses `O(m * rank)` driver and task-local storage. Automatic rank
  *    selection bounds that preconditioner storage and falls back to unpreconditioned CG when no
  *    column fits. This makes the constraint count a distributed-friendly dimension, at the cost
  *    of extra Spark jobs per iteration (one per CG step) and slightly inexact search directions.
  *    Convergence checks are unaffected: the outer loop recomputes its residuals from the
  *    iterates each iteration.
  *  - [[NewtonSolver.Auto]]: Cholesky while `m` is small enough for the driver, ConjugateGradient
  *    beyond that (the core API pivots at [[NewtonSolver.AutoCholeskyLimit]]; the DataFrame DSL
  *    pivots at `SolveConfig.maxLocalConstraints`).
  */
sealed trait NewtonSolver

object NewtonSolver {

  /** Cholesky up to a driver-friendly row count, matrix-free conjugate gradient beyond it. */
  case object Auto extends NewtonSolver

  /** Always use the driver-local Cholesky factorization of the Gramian. */
  case object Cholesky extends NewtonSolver

  /** Always use the matrix-free partial-Cholesky-preconditioned conjugate gradient method. */
  case object ConjugateGradient extends NewtonSolver

  /** Row count at which [[Auto]] switches from Cholesky to conjugate gradient in the core API. */
  val AutoCholeskyLimit: Int = 5000
}

/**
  * Internal machinery behind [[NewtonSolver]]: per-iteration systems for the weighted Gramian
  * `B^T diag(w) B` of the stored constraint matrix `B` (the solver's `AT`).
  */
private[spark_lp] object newton extends LazyLogging {

  /**
    * The scaling weights of one interior-point iteration, both partitioned consistently with the
    * constraint matrix. `sqrt` (the iteration's `D`) feeds the Cholesky path, which scales matrix
    * rows before aggregating the Gramian; `squared` (the iteration's `D2 = D^2`) feeds the
    * matrix-free path, which applies the diagonal between the two products. The solver derives
    * `sqrt` from the validated squared weights so both systems use the same capped operator.
    */
  final case class Weights(sqrt: DVector, squared: DVector)

  /** A prepared solver for one normal-equations matrix, reusable for several right-hand sides. */
  trait NewtonSystem {

    /** Solves the system for `rhs`, leaving `rhs` unmodified. */
    final def solve(rhs: DenseVector): DenseVector = solve(rhs, 0.0)

    /**
      * Solves the system for `rhs`, leaving `rhs` unmodified. `absTolerance` is the absolute
      * residual norm at which an iterative implementation may stop (`0.0` demands its full
      * relative tolerance); direct implementations ignore it.
      */
    def solve(rhs: DenseVector, absTolerance: Double): DenseVector

    /** Frees any resources (e.g. broadcasts) held by the prepared system. */
    def release(): Unit
  }

  /** Builds a [[NewtonSystem]] for `B^T diag(w) B` (`B^T B` when no weights are given). */
  trait NewtonSystemFactory {
    def build(B: DMatrix, m: Int, weights: Option[Weights]): NewtonSystem
  }

  /**
    * The direct method used since the original implementation: aggregate the packed upper
    * triangle of the weighted Gramian to the driver, factor it once, and solve each right-hand
    * side from that Cholesky factor (LAPACK `dpptrf`/`dpptrs`).
    */
  object CholeskyFactory extends NewtonSystemFactory {

    override def build(B: DMatrix, m: Int, weights: Option[Weights]): NewtonSystem = {
      val scaled = weights.map(_.sqrt.diagonalProduct(B)).getOrElse(B)
      val packedGramian = scaled.gramianMatrix(m).data
      CholeskyDecomposition.factor(packedGramian, m)

      new NewtonSystem {
        override def solve(rhs: DenseVector, absTolerance: Double): DenseVector = {
          // dpptrs overwrites only the right-hand side; preserve the caller's vector.
          val solution = rhs.values.clone()
          CholeskyDecomposition.solveFactored(packedGramian, m, solution)
          new DenseVector(solution)
        }

        override def release(): Unit = ()
      }
    }
  }

  /**
    * The matrix-free method: preconditioned conjugate gradient on the normal equations.
    * The operator `p => B^T diag(w) B p` is evaluated with the existing distributed products
    * (one Spark job per CG step); the Gramian itself is never materialised.
    *
    * The preconditioner is a partial Cholesky factorization (the device of Gondzio's matrix-free
    * interior-point method): the `rank` Gramian columns with the largest diagonal entries are
    * computed exactly (one distributed pass, `O(m * rank)` driver and task-local storage) and
    * factorized, and the remaining block is approximated by the diagonal of its Schur complement.
    * The rank is adaptive: solves start at most at rank [[newton.DefaultPreconditionerRank]] and
    * the rank doubles whenever a solve cannot reach its target, up to a driver-budget cap. When no
    * column fits that budget, CG runs without the partial-Cholesky preconditioner. An escalated
    * rank persists for subsequent iterations.
    *
    * Solves after the first within one system are warm-started from the previous solution — the
    * predictor and corrector right-hand sides of one interior-point iteration are closely
    * related, so the corrector typically starts near its solution.
    *
    * Remaining safeguards for ill-conditioned systems: the solve target is
    * `max(relTolerance * ||rhs||, absTolerance)` — the caller states the absolute defect the
    * outer iteration can absorb (the defect enters the next primal residual one-to-one). A
    * stagnating recurrence triggers residual replacement — the true residual `rhs - A x` is
    * recomputed and CG restarts from it. A solve that stops short even at the rank cap is
    * returned as an inexact direction while its residual is within
    * `max(1e-2 * ||rhs||, 100 * target)` — the outer loop recomputes its residuals from the
    * iterates each iteration, so inexact directions cost outer iterations, never a false
    * convergence claim. Anything worse fails the iteration like a Cholesky breakdown would.
    *
    * @param relTolerance       relative residual `||r|| / ||rhs||` at which a solve is accepted.
    * @param maxIterations      CG step limit per solve (per rank level); values < 1 select
    *                           `min(max(100, 2m), 1000)`.
    * @param preconditionerRank fixed rank for the partial Cholesky preconditioner; values < 1
    *                           select the adaptive escalation described above. A rank costs
    *                           `O(m * rank)` driver memory and `O(m * rank^2)` driver flops per
    *                           interior-point iteration.
    */
  final class CgFactory(
    relTolerance: Double,
    maxIterations: Int,
    preconditionerRank: Int = 0)(implicit spark: SparkSession) extends NewtonSystemFactory {

    // The escalated rank persists across systems: once one iteration's system forces an
    // escalation, later iterations (which are at least as ill-conditioned) start from it.
    private var currentRank: Int = -1

    override def build(B: DMatrix, m: Int, weights: Option[Weights]): NewtonSystem = {
      val w = weights.map(_.squared)
      // Keep the lazy column count across CG steps instead of submitting a first() job each time.
      val matrix = new DMatrixOps(B)

      val maxRank =
        if (preconditionerRank > 0) math.min(preconditionerRank, m)
        else autoMaxRank(m)
      if (currentRank < 0) {
        currentRank =
          if (preconditionerRank > 0) maxRank
          else math.min(maxRank, math.min(m, DefaultPreconditionerRank))
      }
      currentRank = math.min(currentRank, maxRank)
      var diagonal: Array[Double] = null
      def buildPreconditioner(rank: Int): BDV[Double] => BDV[Double] = {
        if (rank == 0) {
          (r: BDV[Double]) => r.copy
        } else {
          if (diagonal == null) diagonal = B.gramianDiagonal(w).values
          val partial = new PartialCholesky(B, m, w, diagonal, rank)
          (r: BDV[Double]) => partial(r)
        }
      }
      var preconditioner = buildPreconditioner(currentRank)

      val maxIter = if (maxIterations > 0) maxIterations else math.min(math.max(100, 2 * m), 1000)

      new NewtonSystem {
        private var pBroadcast: Broadcast[DenseVector] = _
        private var lastSolution: BDV[Double] = _

        /** One application of the operator: `B^T (w * (B p))`, driver memory O(m). */
        private def applyOperator(p: BDV[Double]): BDV[Double] = {
          if (pBroadcast != null) Broadcasts.destroyAsync(pBroadcast)
          pBroadcast = spark.sparkContext.broadcast(new DenseVector(p.data))
          val Bp = B.product(pBroadcast)
          val weighted = w.map(_.entrywiseProd(Bp)).getOrElse(Bp)
          new BDV(matrix.adjointProduct(weighted).values)
        }

        override def solve(rhs: DenseVector, absTolerance: Double): DenseVector = {
          val rhsVector = new BDV(rhs.values.clone())
          val rhsNorm = norm(rhsVector)
          if (rhsNorm == 0.0) return new DenseVector(new Array[Double](m))

          val targetNorm = math.max(relTolerance * rhsNorm, absTolerance)

          // warm start from the previous solution of this system, when one exists (the predictor
          // and corrector right-hand sides of one interior-point iteration are closely related)
          val x = if (lastSolution != null) lastSolution.copy else BDV.zeros[Double](m)
          var r = if (lastSolution != null) rhsVector - applyOperator(x) else rhsVector.copy
          var resNorm = norm(r)
          var totalSteps = 0
          var finished = resNorm <= targetNorm
          var exhausted = false

          while (!finished && !exhausted) {
            var stepsAtRank = 0
            var gaveUp = false

            // Each cycle is a CG run with directions restarted from the preconditioned residual;
            // cycles after the first begin with residual replacement (r recomputed as rhs - A x).
            while (!finished && !gaveUp && stepsAtRank < maxIter) {
              val cycleStartNorm = resNorm
              var z = precondition(r)
              var p = z.copy
              var rz = r dot z
              var cycleBest = resNorm
              var stepsSinceImprovement = 0
              var stagnated = false

              while (!finished && !stagnated && stepsAtRank < maxIter) {
                val ap = applyOperator(p)
                val pAp = p dot ap
                if (pAp.isNaN || pAp.isInfinite) {
                  throw new IllegalStateException(
                    s"Non-finite curvature in the normal-equations CG solve at step ${totalSteps + 1}")
                }
                if (pAp <= 0.0) {
                  // A symmetric positive definite operator cannot produce non-positive curvature:
                  // the Gramian is rank deficient (linearly dependent constraint rows).
                  throw new IllegalArgumentException(
                    s"Normal-equations operator is not positive definite (p^T A p = $pAp at CG step ${totalSteps + 1})")
                }
                val alpha = rz / pAp
                x += alpha * p
                r -= alpha * ap
                resNorm = norm(r)
                totalSteps += 1
                stepsAtRank += 1
                finished = resNorm <= targetNorm
                if (!finished) {
                  if (resNorm < 0.95 * cycleBest) {
                    cycleBest = resNorm
                    stepsSinceImprovement = 0
                  } else {
                    stepsSinceImprovement += 1
                    stagnated = stepsSinceImprovement >= 25
                  }
                  if (!stagnated) {
                    z = precondition(r)
                    val rzNew = r dot z
                    p = z + (rzNew / rz) * p
                    rz = rzNew
                  }
                }
              }

              // residual replacement: judge the cycle on the true residual, not the recurrence
              r = rhsVector - applyOperator(x)
              resNorm = norm(r)
              finished = resNorm <= targetNorm
              // a full cycle that recovered less than 10% of the residual will not recover more
              gaveUp = !finished && resNorm > 0.9 * cycleStartNorm
            }

            if (!finished) {
              // the current rank is not strong enough for this system: double it and try again
              // (with a fresh step budget), until the driver-budget cap is reached
              if (currentRank < maxRank) {
                currentRank = math.min(maxRank, 2 * currentRank)
                logger.info(s"CG at residual $resNorm (target $targetNorm) after $totalSteps " +
                  s"steps; escalating the partial Cholesky preconditioner to rank $currentRank")
                preconditioner = buildPreconditioner(currentRank)
              } else {
                exhausted = true
              }
            }
          }

          if (!finished) {
            if (resNorm > math.max(1e-2 * rhsNorm, 100 * targetNorm)) {
              throw new IllegalStateException(
                s"Normal-equations CG solve stalled: residual $resNorm after $totalSteps steps " +
                  s"(target $targetNorm, right-hand side norm $rhsNorm). The system is too " +
                  "ill-conditioned for the current cgTolerance/cgMaxIterations settings.")
            }
            logger.info(s"CG stopped with residual $resNorm (target $targetNorm) after " +
              s"$totalSteps steps; continuing with an inexact direction")
          } else {
            logger.debug(s"CG solved to residual $resNorm (target $targetNorm) in $totalSteps steps")
          }
          lastSolution = x.copy
          new DenseVector(x.data)
        }

        private def precondition(r: BDV[Double]): BDV[Double] = preconditioner(r)

        override def release(): Unit = {
          if (pBroadcast != null) Broadcasts.destroyAsync(pBroadcast)
        }
      }
    }
  }

  /** Rank at which the adaptive partial-Cholesky preconditioner of [[CgFactory]] starts. */
  val DefaultPreconditionerRank: Int = 50

  // Driver budget for the partial-Cholesky factor and its aggregation workspace. Four double
  // arrays per row/rank cover C, L21t, an aggregation buffer, and margin for the diagonal/indexes.
  private val PreconditionerMemoryBudget: Long = 256L * 1024 * 1024
  private val PreconditionerBytesPerRowRank: Long = 4L * 8L
  private val PreconditionerFlopsBudget: Double = 2e10 // ~seconds of driver factorization time

  /** The largest preconditioner rank the driver budgets allow for `m` constraint rows. */
  private[spark_lp] def autoMaxRank(m: Int): Int = {
    val memoryCap = (PreconditionerMemoryBudget / (PreconditionerBytesPerRowRank * math.max(1, m))).toInt
    val flopsCap = math.sqrt(PreconditionerFlopsBudget / math.max(1, m)).toInt
    math.min(m, math.min(memoryCap, flopsCap))
  }

  /**
    * Partial Cholesky preconditioner for the weighted Gramian `G = B^T diag(w) B` (Gondzio's
    * matrix-free IPM preconditioner). The `rank` columns of `G` with the largest diagonal
    * entries are computed exactly — one distributed pass over `B`, `O(m * rank)` driver and
    * task-local aggregation storage —
    * and factorized; the remaining block is approximated by the diagonal of its Schur
    * complement:
    *
    * `P G P^T ~ M = [L11 0; L21 I] [I 0; 0 diag(S)] [L11^T L21^T; 0 I]`
    *
    * where `G11 = L11 L11^T`, `L21 = G21 L11^{-T}` and `diag(S) = diag(G22 - L21 L21^T)`.
    * Applying `M^{-1}` costs `O(m * rank)` driver flops, negligible next to the distributed
    * operator application of one CG step.
    */
  private final class PartialCholesky(
    B: DMatrix,
    m: Int,
    w: Option[DVector],
    diagonal: Array[Double],
    rank: Int) {

    private val k = rank

    // the k Gramian columns with the largest diagonal (ascending index order for determinism)
    private val indices: Array[Int] =
      diagonal.zipWithIndex.sortBy { case (d, _) => -d }.take(k).map(_._2).sorted
    private val rest: Array[Int] = {
      val selected = new Array[Boolean](m)
      indices.foreach(selected(_) = true)
      (0 until m).filterNot(selected).toArray
    }
    private val restCount = rest.length

    // G[:, indices] as a column-major m x k matrix, one distributed pass
    private val C = new BDM(m, k, B.gramianColumns(indices, w))

    private val L11: BDM[Double] = {
      val G11 = BDM.tabulate(k, k)((a, b) => C(indices(a), b))
      val maxDiagonal = (0 until k).map(a => G11(a, a)).max
      // jitter ladder: retry a near-singular leading block with a tiny relative ridge
      val ridges = Seq(0.0, 1e-12 * maxDiagonal, 1e-8 * maxDiagonal)
      ridges.view.map { ridge =>
        try {
          val M = G11.copy
          var a = 0
          while (a < k) { M(a, a) += ridge; a += 1 }
          Some(cholesky(M))
        } catch { case scala.util.control.NonFatal(_) => None }
      }.collectFirst { case Some(l) => l }.getOrElse {
        throw new IllegalArgumentException(
          "Normal-equations Gramian is not positive definite (partial Cholesky preconditioner " +
            "failed): the constraint matrix is rank deficient")
      }
    }

    // L21^T (k x (m - k)): forward-substitute L11 * L21^T = G21^T column by column
    private val L21t: BDM[Double] = {
      val X = BDM.tabulate(k, restCount)((a, i) => C(rest(i), a))
      var col = 0
      while (col < restCount) {
        var a = 0
        while (a < k) {
          var s = X(a, col)
          var b = 0
          while (b < a) {
            s -= L11(a, b) * X(b, col)
            b += 1
          }
          X(a, col) = s / L11(a, a)
          a += 1
        }
        col += 1
      }
      X
    }

    // diagonal of the Schur complement, guarded against cancellation to keep M positive definite
    private val schurDiagonal: Array[Double] = Array.tabulate(restCount) { i =>
      var sumSquares = 0.0
      var a = 0
      while (a < k) {
        val v = L21t(a, i)
        sumSquares += v * v
        a += 1
      }
      val d = diagonal(rest(i)) - sumSquares
      if (d > 0.0 && !d.isInfinite) d
      else if (diagonal(rest(i)) > 0.0 && !diagonal(rest(i)).isInfinite) diagonal(rest(i))
      else 1.0
    }

    /** Applies `M^{-1}` to `r`. */
    def apply(r: BDV[Double]): BDV[Double] = {
      // forward: y1 = L11^{-1} r1
      val y1 = new Array[Double](k)
      var a = 0
      while (a < k) {
        var s = r(indices(a))
        var b = 0
        while (b < a) {
          s -= L11(a, b) * y1(b)
          b += 1
        }
        y1(a) = s / L11(a, a)
        a += 1
      }

      // y2 = diag(S)^{-1} (r2 - L21 y1)
      val y2 = new Array[Double](restCount)
      var i = 0
      while (i < restCount) {
        var s = r(rest(i))
        var b = 0
        while (b < k) {
          s -= L21t(b, i) * y1(b)
          b += 1
        }
        y2(i) = s / schurDiagonal(i)
        i += 1
      }

      // backward: z1 = L11^{-T} (y1 - L21^T y2), accumulated column-wise over L21t
      val t = y1.clone()
      i = 0
      while (i < restCount) {
        val v = y2(i)
        var b = 0
        while (b < k) {
          t(b) -= L21t(b, i) * v
          b += 1
        }
        i += 1
      }
      val z1 = new Array[Double](k)
      a = k - 1
      while (a >= 0) {
        var s = t(a)
        var b = a + 1
        while (b < k) {
          s -= L11(b, a) * z1(b)
          b += 1
        }
        z1(a) = s / L11(a, a)
        a -= 1
      }

      val z = new Array[Double](m)
      a = 0
      while (a < k) { z(indices(a)) = z1(a); a += 1 }
      i = 0
      while (i < restCount) { z(rest(i)) = y2(i); i += 1 }
      new BDV(z)
    }
  }
}
