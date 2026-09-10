package com.github.vbmacher.spark_lp

import com.github.vbmacher.spark_lp.dsl.LpNumericalException
import com.github.vbmacher.spark_lp.newton.{NewtonSystem, NewtonSystemFactory}
import com.github.vbmacher.spark_lp.vectors.dense_vector.implicits.DenseVectorOps
import com.github.vbmacher.spark_lp.vectors.dmatrix.implicits._
import com.github.vbmacher.spark_lp.vectors.dvector.implicits._
import com.github.vbmacher.spark_lp.vectors.{DMatrix, DVector}
import com.typesafe.scalalogging.LazyLogging
import org.apache.spark.SparkException
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.sql.SparkSession

import scala.reflect.ClassTag
import scala.util.control.NonFatal

object LP extends LazyLogging {

  /** How a solver run terminated. */
  private[spark_lp] sealed trait Termination

  private[spark_lp] object Termination {

    /** All three convergence conditions were met within tolerance. */
    case object Converged extends Termination

    /** The iteration budget was exhausted without convergence or a certificate. */
    case object IterationLimit extends Termination

    /** A caller requested a graceful stop after a completed iteration. */
    case object Stopped extends Termination

    /**
      * A Farkas certificate of primal infeasibility was found: the normalized dual iterate
      * `y = lambda / (b^T lambda)` satisfies `A^T y <= infeasibilityTolerance` componentwise with
      * `b^T y = 1`, so no `x >= 0` with `A x = b` exists (within tolerance).
      */
    case object PrimalInfeasible extends Termination

    /**
      * A Farkas certificate of dual infeasibility was found: the normalized primal iterate
      * `z = x / |c^T x|` satisfies `z >= 0`, `c^T z = -1` and
      * `||A z||_inf <= infeasibilityTolerance`, so the dual is infeasible and the primal is
      * unbounded or infeasible (unbounded when a primal-feasible point is known).
      */
    case object DualInfeasible extends Termination
  }

  /**
    * Detailed result of one solver run.
    *
    * @param objectiveValue      final objective value `c^T x` of the returned iterate.
    * @param x                   the returned (last completed) iterate; primal-feasible only if
    *                            `termination` is [[Termination.Converged]].
    * @param iterations          the number of completed iterations.
    * @param termination         how the run terminated (see [[Termination]]).
    * @param primalResidual      final `||A x - b|| / (1 + ||b||)`.
    * @param dualResidual        final `||A^T lambda + s - c|| / (1 + ||c||)`.
    * @param dualityGap          final `|c^T x - b^T lambda| / (1 + |b^T lambda)|`.
    * @param primalCertificate   on [[Termination.PrimalInfeasible]], the normalized Farkas ray
    *                            `y = lambda / (b^T lambda)` (so `b^T y = 1`, `A^T y <= eps`).
    * @param dualCertificate     on [[Termination.DualInfeasible]], the normalized Farkas ray
    *                            `z = x / |c^T x|` (so `z >= 0`, `c^T z = -1`, `||A z||_inf <= eps`).
    * @param certificateResidual residual quality of the reported certificate:
    *                            `max(0, max_i (A^T y)_i)` for a primal-infeasibility certificate,
    *                            `||A z||_inf` for a dual-infeasibility one; `NaN` when neither
    *                            certificate was found.
    */
  private[spark_lp] case class SolveSummary(
    objectiveValue: Double,
    x: DVector,
    iterations: Int,
    termination: Termination,
    primalResidual: Double,
    dualResidual: Double,
    dualityGap: Double,
    primalCertificate: Option[DenseVector] = None,
    dualCertificate: Option[DVector] = None,
    certificateResidual: Double = Double.NaN,
    dualObjectiveValue: Double = Double.NaN)

  /**
    * Computes the optimal value and the corresponding vector for a LP problem.
    *
    * @param c                      the objective coefficient DVector.
    * @param AT                     the constraint DMatrix (transposed).
    * @param b                      the constraint values.
    * @param tolerance              convergence tolerance.
    * @param maxIter                maximum number of iterations if it did not converge.
    * @param etaIter                step size. Shrinkage value.
    * @param valueCap               value cap
    * @param eps                    numerical threshold
    * @param infeasibilityTolerance threshold for the Farkas infeasibility certificate tests.
    * @param solver                 how to solve the per-iteration normal-equations systems (see
    *                               [[NewtonSolver]]). The default [[NewtonSolver.Auto]] uses the
    *                               driver-local Cholesky factorization up to
    *                               [[NewtonSolver.AutoCholeskyLimit]] constraint rows and the
    *                               matrix-free conjugate gradient beyond that.
    * @param cgTolerance            relative residual at which a conjugate-gradient solve is
    *                               accepted (matrix-free solver only).
    * @param cgMaxIterations        CG step limit per normal-equations solve; values < 1 select
    *                               `min(max(100, 2m), 1000)` (matrix-free solver only).
    * @param spark                  a SparkSession instance.
    * @return optimal value and the corresponding solution vector.
    */
  def solve(
    c: DVector,
    AT: DMatrix,
    b: DenseVector,
    tolerance: Double = 1e-8,
    maxIter: Int = 50,
    etaIter: Double = 0.999,
    valueCap: Double = 1e20,
    eps: Double = 1e-20,
    infeasibilityTolerance: Double = 1e-8,
    solver: NewtonSolver = NewtonSolver.Auto,
    cgTolerance: Double = 1e-10,
    cgMaxIterations: Int = 0
  )(implicit spark: SparkSession): (Double, DVector) = {
    val summary = solveSummary(c, AT, b, tolerance, maxIter, etaIter, valueCap, eps, infeasibilityTolerance,
      solver, cgTolerance, cgMaxIterations)
    (summary.objectiveValue, summary.x)
  }

  /**
    * Solve variant that also reports the iteration count, the termination reason and the final
    * residuals the solver computes each iteration.
    *
    * After each iteration's residual update, two scale-invariant Farkas certificate tests are
    * evaluated against `infeasibilityTolerance` (see [[Termination.PrimalInfeasible]] and
    * [[Termination.DualInfeasible]]); when one holds, the loop stops early and the normalized
    * certificate ray is retained in the summary. Otherwise the explicit iteration budget bounds
    * the run; growing residuals alone do not establish infeasibility.
    *
    * Numerical failures (a non-positive-definite Gramian during initialization or an iteration's
    * normal-equations solve — a Cholesky breakdown or a stalled conjugate-gradient run — or a
    * zero iterate element) are wrapped in
    * [[com.github.vbmacher.spark_lp.dsl.LpNumericalException]] naming the phase and the count of
    * completed iterations — except that a failure during an iteration is first reclassified by
    * running the same certificate tests (at the same `infeasibilityTolerance`) on the last
    * completed iterate: infeasible instances frequently degenerate the Cholesky step before the
    * residual-level certificate crisply forms.
    */
  private[spark_lp] def solveSummary(
    c: DVector,
    AT: DMatrix,
    b: DenseVector,
    tolerance: Double = 1e-8,
    maxIter: Int = 50,
    etaIter: Double = 0.999,
    valueCap: Double = 1e20,
    eps: Double = 1e-20,
    infeasibilityTolerance: Double = 1e-8,
    solver: NewtonSolver = NewtonSolver.Auto,
    cgTolerance: Double = 1e-10,
    cgMaxIterations: Int = 0,
    stopAfterIteration: Option[Int => Boolean] = None
  )(implicit spark: SparkSession): SolveSummary = {
    validateParameters(tolerance, maxIter, etaIter, valueCap, eps, infeasibilityTolerance, cgTolerance)
    require(b.size > 0 && b.values.forall(v => !v.isNaN && !v.isInfinite), "b must be nonempty and finite")
    val caches = new CachedRDDs
    try {
      caches.cache(c)
      caches.cache(AT)
      // Reuse the wrapper so adjoint products discover the matrix dimensions only once.
      val matrix = new DMatrixOps(AT)

      val resolvedSolver = resolveNewtonSolver(solver, b.size)
      val systemFactory: NewtonSystemFactory = resolvedSolver match {
        case NewtonSolver.ConjugateGradient => new newton.CgFactory(cgTolerance, cgMaxIterations)
        case _ => newton.CholeskyFactory
      }
      logger.debug(s"Normal-equations solver: $resolvedSolver")

      // run initialization
      val init =
        try {
          Initialize.init(c, AT, b, systemFactory)
        } catch {
          case e if isNumericalFailure(e) => throw numericalFailure("initialization", 0, e)
        }

      var x = init.x
      caches.cache(x)

      var lambda = init.lambda
      var lambdaBroadcast = spark.sparkContext.broadcast(lambda)

      var s = init.s
      caches.cache(s)

      // set number of unknown in lp
      val unknowns = init.rows

      // set number of equations in lp
      val equations = init.cols

      // initial objective value
      var cTx = Double.PositiveInfinity

      var converged = false
      var earlyTermination: Option[Termination] = None
      var primalCertificate: Option[DenseVector] = None
      var dualCertificate: Option[DVector] = None
      var certificateResidual = Double.NaN
      var iter = 1
      var completedIterations = 0

      var primalResidual = Double.NaN
      var dualResidual = Double.NaN
      var dualityGap = Double.NaN

      // constants of the residual normalizations
      val normB = math.sqrt(b.dot(b))
      val normC = math.sqrt(c.dot(c))

      // The defect of an iterative normal-equations solve enters the next primal residual
      // one-to-one, so solves need only be as accurate (in absolute terms) as the primal
      // convergence condition `||rb|| < tolerance * (1 + ||b||)` demands.
      val newtonAbsTolerance = 0.1 * tolerance * (1.0 + normB)

      var dLambdaAffBroadcast: Broadcast[DenseVector] = null
      var dLambdaBroadcast: Broadcast[DenseVector] = null

      try {
        while (!converged && earlyTermination.isEmpty && iter <= maxIter) {

          // the last completed iterate, for certificate-based reclassification of numerical failures
          val x0 = x
          val lambda0 = lambda
          val s0 = s

          var newtonSystem: NewtonSystem = null
          val temporary = new CachedRDDs
          try {
            // Complementarity belongs to the current iterate, not the starting point.
            val mu = x.dot(s) / unknowns
            // A^T * x - b
            var rb = matrix.adjointProduct(x).combine(1.0, -1.0, b)

            // A * lambda + s - c
            var rc = AT.product(lambdaBroadcast).combine(1.0, 1.0, s.diff(c))
            temporary.cache(rc)

            // D^2 = X S^(-1) is the canonical normal-equations weight. Derive D from it so the
            // Cholesky and matrix-free systems, as well as their right-hand sides, stay identical when
            // the inverse-slack cap applies.
            val weights = normalEquationWeights(x, s, eps, valueCap)
            val D2 = weights.squared
            temporary.cache(D2)

            // solve (14.30) for (dxAff, dLambdaAff, dsAff)
            // 1) solve for A^T D2 A dLambdaAff = -rb + A^T * (-D^2 * rc + x)
            // The normal-equations system A^T D2 A must be positive definite, that means:
            //   x^T A x > 0    where x != 0
            newtonSystem = systemFactory.build(AT, equations, Some(weights))

            val ATD2rcx = matrix.adjointProduct(x.diff(D2.entrywiseProd(rc)))

            val dLambdaAffRightSide = ATD2rcx.combine(1.0, -1.0, rb)
            val dLambdaAff = newtonSystem.solve(dLambdaAffRightSide, newtonAbsTolerance)
            dLambdaAffBroadcast = rebroadcast(dLambdaAffBroadcast, dLambdaAff)

            // 2) dsAff = -rc - A * dLambdaAff
            // The step-length and corrector actions reuse these directions and populate their caches.
            val dsAff = temporary.cache(rc.combine(-1.0, -1.0, AT.product(dLambdaAffBroadcast)))

            // 3) dxAff = -x - D^2 * dsAff
            val dxAff = temporary.cache(x.combine(-1.0, -1.0, D2.entrywiseProd(dsAff)))

            // Calculate following Doubles alphaPriAff, alphaDualAff, muAff (14.32), (14.33)
            val alphaPriAff = math.min(1.0, x.entrywiseNegDiv(dxAff).minValue)
            val alphaDualAff = math.min(1.0, s.entrywiseNegDiv(dsAff).minValue)
            val muAff = {
              val nx = x.combine(1.0, alphaPriAff, dxAff)
              val ns = s.combine(1.0, alphaDualAff, dsAff)
              nx.dot(ns) / unknowns
            }

            val sigma = math.min(1.0, math.pow(muAff / mu, 3)) // heuristic

            // Solve (14.35) for (dx, dLambda, ds)
            // 1) A^T D2 A dLambda = -rb + A^T * D2 *(-rc + s + X^(-1) dXAff dSAff e - sigma mu X^(-1)e)
            val xInv = x.mapElements {
              case a if 0 < math.abs(a) && math.abs(a) < eps => math.signum(a) * valueCap
              case a if a >= eps => math.pow(a, -1)
              case _ => throw new IllegalArgumentException(s"Found zero element in X")
            }

            val xInvdXAffdsAff = xInv.entrywiseProd(dxAff.entrywiseProd(dsAff))
            val dLambdaRightSide = rb.combine(
              -1.0, 1.0,
              matrix.adjointProduct(
                D2.entrywiseProd(
                  s.diff(rc)
                    .combine(1.0, 1.0, xInvdXAffdsAff)
                    .combine(1.0, -1.0 * sigma * mu, xInv))))

            val dLambda = newtonSystem.solve(dLambdaRightSide, newtonAbsTolerance)
            dLambdaBroadcast = rebroadcast(dLambdaBroadcast, dLambda)

            // 2) ds = -rc - A * dLambda
            val ds = temporary.cache(rc.combine(-1.0, -1.0, AT.product(dLambdaBroadcast)))

            // 3) dx = -D^2 dS e - x - S^(-1) dXAff dSAff e + sigma mu S^(-1) e
            val sInv = s.mapElements {
              case a if math.abs(a) < eps => math.signum(a) * valueCap
              case a if math.abs(a) >= eps => math.pow(a, -1)
            }

            val sInvdXAffdsAff = sInv.entrywiseProd(dxAff.entrywiseProd(dsAff))
            val dx = temporary.cache(D2
              .entrywiseProd(ds)
              .combine(-1.0, -1.0, x)
              .combine(1.0, -1.0, sInvdXAffdsAff)
              .combine(1.0, sigma * mu, sInv))

            val alphaPrimalIterMax = x.entrywiseNegDiv(dx).minValue
            val alphaDualIterMax = s.entrywiseNegDiv(ds).minValue
            val alphaPrimalIter = math.min(1.0, etaIter * alphaPrimalIterMax)
            val alphaDualIter = math.min(1.0, etaIter * alphaDualIterMax)

            // x = x + alphaPriIter * dx
            x = caches.checkpoint(x.combine(1.0, alphaPrimalIter, dx))

            // lambda = lambda + alphaDualIter * dLambda
            lambda = new DenseVector((lambdaBroadcast.value.toBreeze + alphaDualIter * dLambda.toBreeze).toArray)
            lambdaBroadcast = rebroadcast(lambdaBroadcast, lambda)

            // s = s + alphaDualIter * ds
            s = caches.checkpoint(s.combine(1.0, alphaDualIter, ds))

            rb = matrix.adjointProduct(x).combine(1.0, -1.0, b)
            rc = temporary.cache(AT.product(lambdaBroadcast).combine(1.0, 1.0, s.diff(c)))
            cTx = c.dot(x)

            val bTlambda = b.dot(lambda)
            val normRb = math.sqrt(rb.dot(rb))
            val normRc = math.sqrt(rc.dot(rc))
            val covg1 = normRb / (1 + normB)
            val covg2 = normRc / (1 + normC)
            val covg3 = math.abs(cTx - bTlambda) / (1 + math.abs(bTlambda))

            primalResidual = covg1
            dualResidual = covg2
            dualityGap = covg3

            converged = (covg1 < tolerance) && (covg2 < tolerance) && (covg3 < tolerance)

            if (!converged) {
              // Farkas certificate tests on the fresh iterate (see scaladoc). `A^T lambda = rc + c - s`,
              // so the primal test is one distributed pass; `A x = rb + b` is driver-local, so the dual
              // test is free.
              if (bTlambda > 0) {
                val maxATlambda = rc.combine(1.0, -1.0, s.diff(c)).maxValue
                val quality = math.max(0.0, maxATlambda) / bTlambda
                if (quality <= infeasibilityTolerance) {
                  earlyTermination = Some(Termination.PrimalInfeasible)
                  primalCertificate = Some(new DenseVector(lambda.values.map(_ / bTlambda)))
                  certificateResidual = quality
                  logger.info(s"Primal infeasibility certificate found (residual $quality)")
                }
              }
              if (earlyTermination.isEmpty && cTx < 0) {
                val axInf = rb.combine(1.0, 1.0, b).values.map(math.abs).max
                val quality = axInf / math.abs(cTx)
                if (quality <= infeasibilityTolerance) {
                  earlyTermination = Some(Termination.DualInfeasible)
                  val cTxAbs = math.abs(cTx)
                  dualCertificate = Some(x.mapElements(_ / cTxAbs))
                  certificateResidual = quality
                  logger.info(s"Dual infeasibility certificate found (residual $quality)")
                }
              }

            }

            logger.debug(s"LP iteration=$iter sigma=$sigma primalResidual=$covg1 " +
              s"dualResidual=$covg2 gap=$covg3 converged=$converged cTx=$cTx bTlambda=$bTlambda")

            completedIterations = iter
            if (!converged && earlyTermination.isEmpty && stopAfterIteration.exists(_(iter)))
              earlyTermination = Some(Termination.Stopped)
            // Both replacements have been materialised and checkpointed by the residual actions.
            caches.release(x0)
            caches.release(s0)

          } catch {
            case e if isNumericalFailure(e) =>
              // Before wrapping into LpNumericalException, test the last completed iterate for a
              // Farkas certificate (at the same public infeasibilityTolerance) that explains the
              // degeneration; genuine precondition violations (rank-deficient A) still throw.
              val certificate =
                try certificatesOnIterate(c, AT, b, x0, lambda0, infeasibilityTolerance)
                catch {
                  case NonFatal(_) => None
                }
              certificate match {
                case Some(cert) =>
                  earlyTermination = Some(cert.termination)
                  primalCertificate = cert.primalCertificate
                  dualCertificate = cert.dualCertificate
                  certificateResidual = cert.residual
                  // report the last completed iterate; the failed iteration may have partially updated state
                  if (x ne x0) caches.release(x)
                  if (s ne s0) caches.release(s)
                  x = x0
                  lambda = lambda0
                  s = s0
                  cTx = cert.cTx
                  logger.info(s"Numerical failure in iteration $iter reclassified as ${cert.termination} " +
                    s"(certificate residual ${cert.residual})")
                case None => throw numericalFailure(s"iteration $iter", iter - 1, e)
              }
          } finally {
            if (newtonSystem != null) newtonSystem.release()
            temporary.close()
          }
          iter += 1
        }

      } finally {
        if (dLambdaAffBroadcast != null) dLambdaAffBroadcast.unpersist(blocking = false)
        if (dLambdaBroadcast != null) dLambdaBroadcast.unpersist(blocking = false)
        lambdaBroadcast.unpersist(blocking = false)
      }

      val termination =
        if (converged) Termination.Converged
        else earlyTermination.getOrElse(Termination.IterationLimit)

      logger.info(s"LP finished: solver=$resolvedSolver termination=$termination " +
        s"iterations=$completedIterations objective=$cTx primalResidual=$primalResidual " +
        s"dualResidual=$dualResidual gap=$dualityGap")

      SolveSummary(
        objectiveValue = cTx,
        x = caches.keep(x),
        iterations = completedIterations,
        termination = termination,
        primalResidual = primalResidual,
        dualResidual = dualResidual,
        dualityGap = dualityGap,
        primalCertificate = primalCertificate,
        dualCertificate = dualCertificate,
        certificateResidual = certificateResidual,
        dualObjectiveValue = b.dot(lambda))
    } finally caches.close()
  }

  private[spark_lp] def validateParameters(
    tolerance: Double, maxIter: Int, etaIter: Double, valueCap: Double, eps: Double,
    infeasibilityTolerance: Double, cgTolerance: Double): Unit = {
    Seq("tolerance" -> tolerance, "valueCap" -> valueCap, "epsilon" -> eps,
      "infeasibilityTolerance" -> infeasibilityTolerance, "cgTolerance" -> cgTolerance).foreach {
      case (name, value) => require(value > 0.0 && !value.isInfinite, s"$name must be finite and positive")
    }
    require(maxIter > 0, "maxIterations must be positive")
    require(etaIter > 0.0 && etaIter < 1.0, "etaIteration must be between 0 and 1 (exclusive)")
  }

  /** One certificate found by [[certificatesOnIterate]], with `c^T x` of the tested iterate. */
  private case class Certificate(
    termination: Termination,
    primalCertificate: Option[DenseVector],
    dualCertificate: Option[DVector],
    residual: Double,
    cTx: Double)

  /**
    * Runs the two Farkas certificate tests on a completed iterate `(x, lambda)`, at the same
    * `infeasibilityTolerance` used inside the iteration loop.
    */
  private def certificatesOnIterate(
    c: DVector,
    AT: DMatrix,
    b: DenseVector,
    x: DVector,
    lambda: DenseVector,
    infeasibilityTolerance: Double): Option[Certificate] = {
    val cTx = c.dot(x)
    val bTlambda = b.dot(lambda)

    val primal = if (bTlambda > 0) {
      val maxATlambda = AT.product(lambda).maxValue
      val quality = math.max(0.0, maxATlambda) / bTlambda
      if (quality <= infeasibilityTolerance) {
        Some(Certificate(
          Termination.PrimalInfeasible,
          primalCertificate = Some(new DenseVector(lambda.values.map(_ / bTlambda))),
          dualCertificate = None,
          residual = quality,
          cTx = cTx))
      } else None
    } else None

    primal.orElse {
      if (cTx < 0) {
        val axInf = AT.adjointProduct(x).values.map(math.abs).max
        val quality = axInf / math.abs(cTx)
        if (quality <= infeasibilityTolerance) {
          val cTxAbs = math.abs(cTx)
          Some(Certificate(
            Termination.DualInfeasible,
            primalCertificate = None,
            dualCertificate = Some(x.mapElements(_ / cTxAbs)),
            residual = quality,
            cTx = cTx))
        } else None
      } else None
    }
  }

  /** Failure modes of the linear algebra underneath the solver, possibly wrapped by Spark. */
  private def isNumericalFailure(e: Throwable): Boolean = e match {
    case _: MatchError | _: IllegalArgumentException | _: IllegalStateException | _: AssertionError => true
    case e: SparkException => e.getCause != null && isNumericalFailure(e.getCause)
    case _ => false
  }

  private def numericalFailure(phase: String, completedIterations: Int, cause: Throwable): LpNumericalException = {
    val detail = Option(cause.getMessage).getOrElse(cause.toString)
    new LpNumericalException(
      phase = phase,
      completedIterations = completedIterations,
      message = s"Numerical failure during $phase (completed iterations: $completedIterations): $detail. " +
        "The solver requires a constraint matrix with full row rank (linearly independent constraint rows) " +
        "and strictly interior iterates; a non-positive-definite Gramian or a zero iterate element " +
        "indicates this precondition is violated.",
      cause = cause)
  }

  def rebroadcast[T: ClassTag](old: Broadcast[T], v: T)(implicit spark: SparkSession): Broadcast[T] = {
    if (old != null) old.unpersist(blocking = false)
    spark.sparkContext.broadcast(v)
  }

  /** Resolves Auto from the already-local equality-form row count. */
  private[spark_lp] def resolveNewtonSolver(solver: NewtonSolver, equations: Int): NewtonSolver = solver match {
    case NewtonSolver.Auto if equations <= NewtonSolver.AutoCholeskyLimit => NewtonSolver.Cholesky
    case NewtonSolver.Auto => NewtonSolver.ConjugateGradient
    case s => s
  }

  /** Builds one validated weight pair for both normal-equations implementations. */
  private[spark_lp] def normalEquationWeights(
    x: DVector,
    s: DVector,
    eps: Double,
    valueCap: Double): newton.Weights = {
    val squared = x.entrywiseProd(
      s.mapElements {
        case a if math.abs(a) < eps => math.signum(a) * valueCap
        case a if math.abs(a) >= eps => math.pow(a, -1)
      }
    ).mapElements {
      case a if a > 0.0 && !a.isInfinite => a
      case a => throw new IllegalArgumentException(s"Found non-positive or non-finite D^2 element: $a")
    }
    newton.Weights(sqrt = squared.mapElements(math.sqrt), squared = squared)
  }
}
