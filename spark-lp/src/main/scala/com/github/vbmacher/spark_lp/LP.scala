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

    /** A cooperative stop was requested; candidate availability is explicit. */
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
    * @param x                   the returned completed iterate (best feasible on intentional stops), or an
    *                            empty RDD when `candidate.available` is false.
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
    dualObjectiveValue: Double = Double.NaN,
    innerIterations: Int = 0,
    preconditionerRank: Int = 0,
    stopReason: Option[StopReason] = None,
    candidate: CandidateInfo = CandidateInfo.Unavailable)

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
    * @param matrixFree             primal/dual regularization and bounded preconditioner controls.
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
    cgMaxIterations: Int = 0,
    matrixFree: MatrixFreeConfig = MatrixFreeConfig()
  )(implicit spark: SparkSession): (Double, DVector) = {
    val summary = solveSummary(c, AT, b, tolerance, maxIter, etaIter, valueCap, eps, infeasibilityTolerance,
      solver, cgTolerance, cgMaxIterations, matrixFree = matrixFree)
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
    stopAfterIteration: Option[Int => Boolean] = None,
    matrixFree: MatrixFreeConfig = MatrixFreeConfig(),
    control: SolveControl = SolveControl(),
    candidateViolation: Option[DVector => Double] = None,
    nanoTime: () => Long = () => System.nanoTime()
  )(implicit spark: SparkSession): SolveSummary = {
    validateParameters(tolerance, maxIter, etaIter, valueCap, eps, infeasibilityTolerance, cgTolerance)
    require(b.size > 0 && b.values.forall(v => !v.isNaN && !v.isInfinite), "b must be nonempty and finite")
    val caches = new CachedRDDs
    val monitor = new SolveMonitor(control, nanoTime)
    try {
      caches.cache(c)
      caches.cache(AT)
      // Reuse the wrapper so adjoint products discover the matrix dimensions only once.
      val matrix = new DMatrixOps(AT)

      val resolvedSolver = resolveNewtonSolver(solver, b.size)
      val systemFactory: NewtonSystemFactory = resolvedSolver match {
        case NewtonSolver.ConjugateGradient => new newton.CgFactory(cgTolerance, cgMaxIterations, config = matrixFree, monitor = monitor)
        case _ => new newton.DirectFactory(monitor)
      }
      logger.debug(s"Normal-equations solver: $resolvedSolver")

      // run initialization
      val init =
        try {
          monitor.phase(SolvePhase.Initialization)
          Initialize.init(c, AT, b, systemFactory)
        } catch {
          case stopped: SolveStopped =>
            logger.info(s"LP stopped during initialization: reason=${stopped.reason} candidate=unavailable")
            return SolveSummary(Double.NaN, spark.sparkContext.emptyRDD, 0, Termination.Stopped,
              Double.NaN, Double.NaN, Double.NaN, stopReason = Some(stopped.reason),
              innerIterations = systemFactory.innerIterations, preconditionerRank = systemFactory.maximumRank)
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
      var stopReason: Option[StopReason] = None
      var bestCandidate: Option[SolveSummary] = None
      var lastCandidate = CandidateInfo.Unavailable
      val progress = control.stagnation.map(new OuterProgress(_))

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
            monitor.iteration = iter
            monitor.check()
            // Complementarity belongs to the current iterate, not the starting point.
            val mu = x.dot(s) / unknowns
            // A^T * x - b
            var rb = matrix.adjointProduct(x).combine(1.0, -1.0, b)

            // A * lambda + s - c
            var rc = AT.product(lambdaBroadcast).combine(1.0, 1.0, s.diff(c))
            temporary.cache(rc)

            // Proximal references are the current x/lambda. The original residuals are
            // unchanged; only the Newton equations contain Rp and Rd.
            val rho = systemFactory.primalRegularization
            val weights = if (rho > 0.0) regularizedWeights(x, s, rho)
              else normalEquationWeights(x, s, eps, valueCap)
            val D2 = temporary.cache(weights.squared)
            newtonSystem = systemFactory.build(AT, equations, Some(weights))

            // qAff = -X S e, hAff = rc + qAff / x = rc - s.
            val hAff = rc.diff(s)
            val dLambdaAffRightSide = rb.combine(-1.0, -1.0, matrix.adjointProduct(D2.entrywiseProd(hAff)))
            val dLambdaAff = newtonSystem.solve(dLambdaAffRightSide, newtonAbsTolerance)
            dLambdaAffBroadcast = rebroadcast(dLambdaAffBroadcast, dLambdaAff)
            val (dxAff0, dsAff0) = newton.recoverDirections(
              weights, hAff, rc, AT.product(dLambdaAffBroadcast), rho)
            val dxAff = temporary.cache(dxAff0)
            val dsAff = temporary.cache(dsAff0)

            // Calculate following Doubles alphaPriAff, alphaDualAff, muAff (14.32), (14.33)
            val alphaPriAff = math.min(1.0, x.entrywiseNegDiv(dxAff).minValue)
            val alphaDualAff = math.min(1.0, s.entrywiseNegDiv(dsAff).minValue)
            val muAff = {
              val nx = x.combine(1.0, alphaPriAff, dxAff)
              val ns = s.combine(1.0, alphaDualAff, dsAff)
              nx.dot(ns) / unknowns
            }

            val sigma = math.min(1.0, math.pow(muAff / mu, 3)) // heuristic

            // q = -X S e - dxAff*dsAff + sigma*mu*e. Eliminate with the same W
            // used by the operator, then recover both directions from the regularized KKT.
            val xInv = x.mapElements { a =>
              require(a > 0.0 && !a.isInfinite, "Newton iterate X must be finite and positive")
              1.0 / a
            }
            val h = hAff.diff(xInv.entrywiseProd(dxAff.entrywiseProd(dsAff)))
              .combine(1.0, sigma * mu, xInv)
            val dLambdaRightSide = rb.combine(-1.0, -1.0, matrix.adjointProduct(D2.entrywiseProd(h)))
            val dLambda = newtonSystem.solve(dLambdaRightSide, newtonAbsTolerance)
            dLambdaBroadcast = rebroadcast(dLambdaBroadcast, dLambda)
            val (dx0, ds0) = newton.recoverDirections(
              weights, h, rc, AT.product(dLambdaBroadcast), rho)
            val dx = temporary.cache(dx0)
            val ds = temporary.cache(ds0)

            val alphaPrimalIterMax = x.entrywiseNegDiv(dx).minValue
            val alphaDualIterMax = s.entrywiseNegDiv(ds).minValue
            val alphaPrimalIter = math.min(1.0, etaIter * alphaPrimalIterMax)
            val alphaDualIter = math.min(1.0, etaIter * alphaDualIterMax)

            monitor.check()
            // x = x + alphaPriIter * dx
            x = caches.checkpoint(x.combine(1.0, alphaPrimalIter, dx))

            // lambda = lambda + alphaDualIter * dLambda
            lambda = new DenseVector((lambdaBroadcast.value.toBreeze + alphaDualIter * dLambda.toBreeze).toArray)
            lambdaBroadcast = rebroadcast(lambdaBroadcast, lambda)

            // s = s + alphaDualIter * ds
            s = caches.checkpoint(s.combine(1.0, alphaDualIter, ds))

            rb = matrix.adjointProduct(x).combine(1.0, -1.0, b)
            rc = temporary.cache(AT.product(lambdaBroadcast).combine(1.0, 1.0, s.diff(c)))
            val objectiveAndMin = c.zipPartitions(x) { (costs, values) =>
              val cv = costs.next().values
              val xv = values.next().values
              var objective = 0.0
              var minimum = Double.PositiveInfinity
              var i = 0
              while (i < xv.length) {
                objective += cv(i) * xv(i)
                minimum = math.min(minimum, xv(i))
                i += 1
              }
              Iterator.single((objective, minimum))
            }.reduce { case ((a, amin), (b, bmin)) => (a + b, math.min(amin, bmin)) }
            cTx = objectiveAndMin._1

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
            val violation = candidateViolation.map(_(x)).getOrElse {
              val rowViolation = rb.values.zip(b.values).map { case (r, rhs) =>
                math.abs(r) / (1.0 + math.abs(rhs))
              }.max
              math.max(rowViolation, math.max(0.0, -objectiveAndMin._2))
            }
            val feasible = !cTx.isNaN && !cTx.isInfinite && violation <= control.feasibilityTolerance
            lastCandidate = CandidateInfo(available = true, feasible = feasible, iteration = Some(iter))
            if (feasible && bestCandidate.forall(cTx < _.objectiveValue)) {
              bestCandidate.foreach { previous => if (previous.x ne x0) caches.release(previous.x) }
              bestCandidate = Some(SolveSummary(cTx, x, iter, Termination.Stopped, covg1, covg2, covg3,
                dualObjectiveValue = bTlambda, candidate = lastCandidate))
            }
            // Both replacements have been materialised and checkpointed by the residual actions.
            if (!bestCandidate.exists(_.x eq x0)) caches.release(x0)
            caches.release(s0)
            val terminal = converged || earlyTermination.nonEmpty
            monitor.report(SolveProgress(SolvePhase.OuterIteration, iter, 0.0,
              objectiveValue = Some(cTx), primalResidual = Some(covg1), dualResidual = Some(covg2),
              dualityGap = Some(covg3), feasible = Some(feasible)), terminal = terminal)
            if (!terminal) {
              if (monitor.callback(stopAfterIteration.exists(_(iter)))) throw SolveStopped(StopReason.UserRequested)
              if (progress.exists(_.observe(iter, feasible, cTx, violation, covg1, covg2, covg3)))
                throw SolveStopped(StopReason.NoProgress)
            }

          } catch {
            case stopped: SolveStopped =>
              earlyTermination = Some(Termination.Stopped)
              stopReason = Some(stopped.reason)
              // Safe checks run before updates or after a fully completed iteration.
            case e if isNumericalFailure(e) =>
              // Before wrapping into LpNumericalException, test the last completed iterate for a
              // Farkas certificate (at the same public infeasibilityTolerance) that explains the
              // degeneration. Without a certificate, report a numerical failure.
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
                  lastCandidate = CandidateInfo(true, false, Some(completedIterations))
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

      val last = SolveSummary(
        objectiveValue = if (!lastCandidate.available) Double.NaN else cTx,
        x = if (!lastCandidate.available) spark.sparkContext.emptyRDD[DenseVector] else x,
        iterations = completedIterations,
        termination = termination,
        primalResidual = primalResidual,
        dualResidual = dualResidual,
        dualityGap = dualityGap,
        primalCertificate = primalCertificate,
        dualCertificate = dualCertificate,
        certificateResidual = certificateResidual,
        dualObjectiveValue = b.dot(lambda),
        innerIterations = systemFactory.innerIterations,
        preconditionerRank = systemFactory.maximumRank,
        candidate = lastCandidate)
      val intentional = termination == Termination.Stopped || termination == Termination.IterationLimit
      val selected = if (intentional) bestCandidate.getOrElse(last) else last
      caches.keep(selected.x)
      val result = selected.copy(iterations = completedIterations, termination = termination,
        primalCertificate = primalCertificate, dualCertificate = dualCertificate,
        certificateResidual = certificateResidual, innerIterations = systemFactory.innerIterations,
        preconditionerRank = systemFactory.maximumRank,
        stopReason = if (termination == Termination.IterationLimit) Some(StopReason.IterationLimit) else stopReason)
      logger.info(s"LP finished: solver=$resolvedSolver termination=$termination stopReason=${result.stopReason} " +
        s"iterations=$completedIterations candidate=${result.candidate} objective=${result.objectiveValue} " +
        s"primalResidual=${result.primalResidual} dualResidual=${result.dualResidual} gap=${result.dualityGap}")
      result
    } catch {
      case failed: CallbackFailed => throw failed.error
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
        "No infeasibility or unboundedness certificate was established. Check scaling, " +
        "strictly positive iterates and the selected Newton solver settings; Cholesky requires full row rank.",
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

  /** W = (S/X + Rp)^(-1), evaluated without forming the potentially overflowing X/S.
    * No inverse-slack cap: the same diagonal participates in the operator and recovery.
    */
  private[spark_lp] def regularizedWeights(x: DVector, s: DVector, rho: Double): newton.Weights = {
    val squared = x.zip(s).map { case (xp, sp) =>
      require(xp.size == sp.size, "Newton vectors must have matching partitions")
      new DenseVector(xp.values.zip(sp.values).map { case (xi, si) =>
        require(xi > 0.0 && si > 0.0 && !xi.isInfinite && !si.isInfinite,
          "Newton iterates must be finite and positive")
        val w = 1.0 / (si / xi + rho)
        require(w > 0.0 && !w.isInfinite, "Non-finite or underflowed Newton weight")
        w
      })
    }
    newton.Weights(squared.mapElements(math.sqrt), squared)
  }

  /** Historical capped weights for the unregularized Cholesky reference. */
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
