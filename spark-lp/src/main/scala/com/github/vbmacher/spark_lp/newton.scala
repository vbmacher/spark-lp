package com.github.vbmacher.spark_lp

import breeze.linalg.{norm, DenseVector => BDV}
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
  *    selection bounds that preconditioner storage and falls back to Jacobi CG when no
  *    column fits. This makes the constraint count a distributed-friendly dimension, at the cost
  *    of extra Spark jobs per iteration (one per CG step) and slightly inexact search directions.
  *    Convergence checks are unaffected: the outer loop recomputes its residuals from the
  *    iterates each iteration.
  *  - [[NewtonSolver.Auto]]: Cholesky while `m` is small enough for the driver, ConjugateGradient
  *    beyond [[NewtonSolver.AutoCholeskyLimit]]. The DataFrame DSL can lower this cutoff with
  *    its separate `SolveConfig.maxLocalConstraints` resource cap.
  */
sealed trait NewtonSolver

object NewtonSolver {

  /** Cholesky through the measured small-system crossover, matrix-free CG beyond it. */
  case object Auto extends NewtonSolver

  /** Always use the driver-local Cholesky factorization of the Gramian. */
  case object Cholesky extends NewtonSolver

  /** Always use the matrix-free partial-Cholesky-preconditioned conjugate gradient method. */
  case object ConjugateGradient extends NewtonSolver

  /** Largest Auto Cholesky row count. Equal-accuracy local benchmarks were mixed at 1000,
    * and favored CG from 1500 onward; see benchmarks/README.md for workloads and limitations.
    */
  val AutoCholeskyLimit: Int = 1000
}

/** Matrix-free Newton controls. Regularizations are diagonal entries (not square roots).
  * Proximal reference points are reset to the current iterate, so the residuals are those
  * of the original LP. A fixed positive regularization bounds the Newton weights without
  * changing the convergence test. See docs/algorithm.adoc for equations and pivot policy.
  */
final case class MatrixFreeConfig(
  primalRegularization: Double = 1e-8,
  dualRegularization: Double = 1e-8,
  preconditionerRank: Int = 0,
  preconditionerMemoryBytes: Long = 256L * 1024 * 1024) {
  Seq(primalRegularization, dualRegularization).foreach { value =>
    require(value > 0.0 && !value.isInfinite, "Regularization must be finite and positive")
  }
  require(preconditionerRank >= 0, "Preconditioner rank must be nonnegative (0 selects adaptive rank)")
  require(preconditionerMemoryBytes >= 0, "Preconditioner memory budget must be nonnegative")
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
    * `sqrt` from the validated squared weights. CG uses `(S/X + Rp)^(-1)`; the direct
    * reference uses the historical inverse-slack cap.
    */
  final case class Weights(sqrt: DVector, squared: DVector)

  /** A prepared solver for one normal-equations matrix, reusable for several right-hand sides. */
  trait NewtonSystem {

    /** Solves the system for `rhs`, leaving `rhs` unmodified. */
    final def solve(rhs: DenseVector): DenseVector = solve(rhs, 0.0)

    /**
      * Solves the system for `rhs`, leaving `rhs` unmodified. `absTolerance` is the absolute
      * residual norm targeted by an iterative implementation (`0.0` uses only its relative
      * target); direct implementations ignore it. CgFactory documents its inexact fallback.
      */
    def solve(rhs: DenseVector, absTolerance: Double): DenseVector

    /** Frees any resources (e.g. broadcasts) held by the prepared system. */
    def release(): Unit
  }

  /** Builds `B^T diag(w) B + Rd`. With no weights, uses `(I + Rp)^(-1)` for initialization. */
  trait NewtonSystemFactory {
    def build(B: DMatrix, m: Int, weights: Option[Weights]): NewtonSystem
    def primalRegularization: Double = 0.0
    def dualRegularization: Double = 0.0
    def innerIterations: Int = 0
    def maximumRank: Int = 0
  }

  /** Recover directions for A dx + Rd dy = -rb, A^T dy + ds - Rp dx = -rc,
    * S dx + X ds = q, with h = rc + q / x and W = (S/X + Rp)^(-1).
    */
  def recoverDirections(weights: Weights, h: DVector, rc: DVector, aty: DVector,
    primalRegularization: Double): (DVector, DVector) = {
    val dx = weights.squared.entrywiseProd(aty.combine(1.0, 1.0, h))
    val ds = rc.combine(-1.0, -1.0, aty).combine(1.0, primalRegularization, dx)
    (dx, ds)
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

  /** Matrix-free PCG on B^T W B + Rd. Adaptive rank starts with Jacobi and adds
    * updated-diagonal Cholesky pivots only when a solve misses its target. Rank is bounded
    * even when explicitly requested. True (unpreconditioned) residuals decide acceptance;
    * exhausted solves may return at most a 1e-3 relative defect. The outer loop alone
    * judges original-LP convergence and certificates.
    */
  final class CgFactory(
    relTolerance: Double,
    maxIterations: Int,
    config: MatrixFreeConfig = MatrixFreeConfig())(implicit spark: SparkSession) extends NewtonSystemFactory {

    require(relTolerance > 0.0 && !relTolerance.isInfinite, "CG tolerance must be finite and positive")
    // The escalated rank persists across systems: once one iteration's system forces an
    // escalation, later iterations (which are at least as ill-conditioned) start from it.
    private var currentRank: Int = -1
    private var steps: Int = 0
    private var peakRank: Int = 0
    override val primalRegularization: Double = config.primalRegularization
    override val dualRegularization: Double = config.dualRegularization
    override def innerIterations: Int = steps
    override def maximumRank: Int = peakRank

    override def build(B: DMatrix, m: Int, weights: Option[Weights]): NewtonSystem = {
      // Initialization uses the same regularized least-squares operator with S/X = I.
      val initialWeight = 1.0 / (1.0 + primalRegularization)
      val w = Some(weights.map(_.squared).getOrElse(
        B.mapPartitions(rows => Iterator.single(new DenseVector(
          rows.map(_ => initialWeight).toArray)))))
      val matrix = new DMatrixOps(B)
      val requestedRank = config.preconditionerRank
      val budgetRank = autoMaxRank(m, config.preconditionerMemoryBytes)
      val maxRank = if (requestedRank > 0) math.min(requestedRank, budgetRank) else budgetRank
      if (currentRank < 0) currentRank = if (requestedRank > 0) maxRank else 0
      currentRank = math.min(currentRank, maxRank)
      val diagonal = matrix.gramianDiagonal(w).values.map(_ + dualRegularization)
      def buildPreconditioner(rank: Int): BDV[Double] => BDV[Double] = {
        val partial = new PartialCholesky(diagonal, rank, j => {
          val column = matrix.gramianColumns(Array(j), w)
          column(j) += dualRegularization
          column
        }, dualRegularization)
        peakRank = math.max(peakRank, partial.indices.length)
        (r: BDV[Double]) => partial(r)
      }
      var preconditioner = buildPreconditioner(currentRank)

      val maxIter = if (maxIterations > 0) maxIterations else math.min(math.max(100L, 2L * m), 1000L).toInt

      new NewtonSystem {
        private var pBroadcast: Broadcast[DenseVector] = _
        private var lastSolution: BDV[Double] = _

        /** One application of the operator: `B^T (w * (B p))`, driver memory O(m). */
        private def applyOperator(p: BDV[Double]): BDV[Double] = {
          if (pBroadcast != null) Broadcasts.destroyAsync(pBroadcast)
          pBroadcast = spark.sparkContext.broadcast(new DenseVector(p.data))
          new BDV(regularizedProduct(B, matrix, w, pBroadcast, dualRegularization).values)
        }

        override def solve(rhs: DenseVector, absTolerance: Double): DenseVector = {
          val rhsVector = new BDV(rhs.values.clone())
          require(rhs.size == m && rhs.values.forall(v => !v.isNaN && !v.isInfinite),
            "CG right-hand side must be finite and match the operator")
          require(absTolerance >= 0.0 && !absTolerance.isInfinite, "CG absolute tolerance must be finite and nonnegative")
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
                  // Regularization makes this SPD even for dependent rows: this is numerical failure.
                  throw new IllegalStateException(
                    s"Normal-equations operator is not positive definite (p^T A p = $pAp at CG step ${totalSteps + 1})")
                }
                val alpha = rz / pAp
                x += alpha * p
                r -= alpha * ap
                resNorm = norm(r)
                totalSteps += 1
                steps += 1
                if (resNorm.isNaN || resNorm.isInfinite)
                  throw new IllegalStateException("Non-finite CG residual")
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
                currentRank = math.min(maxRank, math.max(DefaultPreconditionerRank, 2 * currentRank))
                logger.info(s"CG at residual $resNorm (target $targetNorm) after $totalSteps " +
                  s"steps; escalating the partial Cholesky preconditioner to rank $currentRank")
                preconditioner = buildPreconditioner(currentRank)
              } else {
                exhausted = true
              }
            }
          }

          // An infeasible LP can drive the iterates to scales where roundoff prevents
          // the requested tolerance. A bounded inexact direction can still expose a
          // Farkas certificate on the next iterate. This is never a convergence test.
          if (resNorm.isNaN || resNorm.isInfinite ||
              (!finished && resNorm > math.max(targetNorm, 1e-3 * rhsNorm))) {
            throw new IllegalStateException(
              s"Normal-equations CG solve stalled: residual $resNorm after $totalSteps steps " +
                s"(target $targetNorm, right-hand side norm $rhsNorm). Check scaling, regularization, " +
                "cgTolerance/cgMaxIterations and preconditioner settings.")
          }
          logger.debug(s"CG residual $resNorm (target $targetNorm) in $totalSteps steps; inexact=${!finished}")
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

  private[spark_lp] def regularizedProduct(B: DMatrix, matrix: DMatrixOps,
    weights: Option[DVector], p: Broadcast[DenseVector], dual: Double): DenseVector = {
    val bp = B.product(p)
    val weighted = weights.map(_.entrywiseProd(bp)).getOrElse(bp)
    val result = matrix.adjointProduct(weighted).values
    var i = 0
    while (i < result.length) {
      result(i) += dual * p.value(i)
      i += 1
    }
    new DenseVector(result)
  }

  /** First nonzero rank of the adaptive preconditioner, after Jacobi misses its target. */
  val DefaultPreconditionerRank: Int = 50

  // Budget for factors and aggregation workspace. Four double arrays per row/rank
  // cover an old factor during escalation, the new factor, a column and reduction buffers.
  // O(m) iteration vectors and Spark runtime overhead are outside this factor budget.
  private[spark_lp] val PreconditionerMemoryBudget: Long = 256L * 1024 * 1024
  private val PreconditionerBytesPerRowRank: Long = 4L * 8L
  private val PreconditionerFlopsBudget: Double = 2e10 // ~seconds of driver factorization time

  /** The largest preconditioner rank the driver budgets allow for `m` constraint rows. */
  private[spark_lp] def autoMaxRank(m: Int, memoryBytes: Long = PreconditionerMemoryBudget): Int = {
    val memoryCap = math.min(Int.MaxValue.toLong, memoryBytes / (PreconditionerBytesPerRowRank * math.max(1, m))).toInt
    val flopsCap = math.sqrt(PreconditionerFlopsBudget / math.max(1, m)).toInt
    math.min(m, math.min(memoryCap, flopsCap))
  }

  /** Sequential complete diagonal pivoting, fetching only one Gramian column per pivot.
    * Storage is O(m * rank), plus O(m) scratch. Small Schur pivots stop factorization;
    * only the preconditioner's remaining diagonal is floored against roundoff. The
    * regularized operator itself is never silently perturbed after RHS construction.
    */
  final class PartialCholesky(
    diagonal: Array[Double],
    requestedRank: Int,
    column: Int => Array[Double],
    dualRegularization: Double) {

    private val m = diagonal.length
    require(diagonal.forall(d => d > 0.0 && !d.isInfinite), "Invalid regularized Gramian diagonal")
    private val floor = math.max(dualRegularization, 64.0 * math.ulp(diagonal.max))
    private val schur = diagonal.clone()
    private val selected = new Array[Boolean](m)
    private val pivots = scala.collection.mutable.ArrayBuffer.empty[Int]
    private val factors = scala.collection.mutable.ArrayBuffer.empty[Array[Double]]
    private var stopped = false
    while (pivots.length < math.min(m, requestedRank) && !stopped) {
      var pivot = -1
      var i = 0
      while (i < m) {
        if (!selected(i) && (pivot < 0 || schur(i) > schur(pivot))) pivot = i
        i += 1
      }
      if (schur(pivot) <= floor) stopped = true
      else {
        val l = column(pivot)
        require(l.length == m && l.forall(v => !v.isNaN && !v.isInfinite), "Invalid Gramian column")
        val root = math.sqrt(schur(pivot))
        i = 0
        while (i < m) {
          if (!selected(i) && i != pivot) {
            var j = 0
            while (j < factors.length) {
              l(i) -= factors(j)(i) * factors(j)(pivot)
              j += 1
            }
            l(i) /= root
            schur(i) -= l(i) * l(i)
            if (schur(i) < -floor || schur(i).isNaN || schur(i).isInfinite)
              throw new IllegalStateException(s"Unstable Schur pivot at row $i: ${schur(i)}")
          } else l(i) = 0.0
          i += 1
        }
        l(pivot) = root
        selected(pivot) = true
        pivots += pivot
        factors += l
      }
    }
    val indices: Array[Int] = pivots.toArray
    private val k = indices.length
    private val rest = (0 until m).filterNot(selected).toArray
    rest.foreach(i => schur(i) = math.max(floor, schur(i)))

    def apply(r: BDV[Double]): BDV[Double] = {
      val z = r.toArray
      var j = 0
      while (j < k) {
        val pivot = indices(j)
        z(pivot) /= factors(j)(pivot)
        var i = 0
        while (i < m) {
          if (i != pivot) z(i) -= factors(j)(i) * z(pivot)
          i += 1
        }
        j += 1
      }
      rest.foreach(i => z(i) /= schur(i))
      j = k - 1
      while (j >= 0) {
        val pivot = indices(j)
        var i = 0
        while (i < m) {
          if (i != pivot) z(pivot) -= factors(j)(i) * z(i)
          i += 1
        }
        z(pivot) /= factors(j)(pivot)
        j -= 1
      }
      new BDV(z)
    }
  }
}
