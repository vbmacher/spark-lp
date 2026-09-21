package com.github.vbmacher.spark_lp.dsl

/** Specifies whether the solver minimizes or maximizes the objective. */
sealed trait ObjectiveSense
case object Minimize extends ObjectiveSense
case object Maximize extends ObjectiveSense

/** Restricts the values that a decision variable may take. */
sealed trait VariableCategory
case object Continuous extends VariableCategory

/**
  * A decision variable restricted to whole-number values.
  *
  * The built-in mixed-integer solver requires finite effective bounds. They may be declared on the
  * variable or inferred from supported linear constraints during presolve.
  */
case object Integer extends VariableCategory

/**
  * A decision variable restricted to `0` or `1`.
  *
  * Declared bounds further restrict that set; for example, `lowerBound = 1` fixes the variable to
  * `1`.
  */
case object Binary extends VariableCategory

object VariableCategory {
  /** Declared bounds intersected with the category's domain: [[Binary]] clamps them to `{0, 1}`;
    * [[Integer]] and [[Continuous]] pass through unchanged. */
  def domainBounds(category: VariableCategory, lower: Double, upper: Option[Double]): (Double, Option[Double]) =
    if (category == Binary) (math.max(0.0, lower), Some(math.min(1.0, upper.getOrElse(1.0)))) else (lower, upper)

  /** As [[domainBounds]] but reports an unbounded upper as `+Infinity` instead of `None`. */
  def domainBoundsFinite(category: VariableCategory, lower: Double, upper: Option[Double]): (Double, Double) = {
    val (lo, hi) = domainBounds(category, lower, upper)
    (lo, hi.getOrElse(Double.PositiveInfinity))
  }
}

/**
  * Classification of why a solve ended.
  *
  * For a mixed-integer model, [[LpStatus.Optimal]] means that the branch-and-bound search resolved
  * every remaining branch within the configured gap. An unresolved branch produces
  * [[LpStatus.IterationLimit]] rather than a stronger claim. For an external adapter, inspect
  * [[LpSolution.backend]] for the backend claim and independent-validation status.
  */
sealed trait LpStatus

object LpStatus {

/** The model was solved within the configured feasibility and optimality tolerances. */
  case object Optimal extends LpStatus

  /**
    * An iteration or node budget ended the solve before optimality was established.
    *
    * The result may retain an iterate or incumbent. Check [[LpSolution.candidate]] before using it.
    */
  case object IterationLimit extends LpStatus

/** A callback, deadline or no-progress policy requested a cooperative stop. */
  case object Stopped extends LpStatus

  /**
    * The solver established that no values satisfy all constraints and bounds.
    *
    * For a continuous solve, [[LpSolution.evidence]] may contain an independently verifiable
    * [[InfeasibilityCertificate]].
    */
  case object Infeasible extends LpStatus

  /**
    * The objective can improve without limit while the model remains feasible.
    *
    * For a continuous solve, [[LpSolution.evidence]] may contain an [[UnboundedDirection]] and a
    * feasible point.
    */
  case object Unbounded extends LpStatus

  /**
    * The solver proved that no finite optimum exists but could not distinguish infeasibility from
    * unboundedness because it had no feasible point.
    */
  case object InfeasibleOrUnbounded extends LpStatus
}

sealed abstract class LpException(message: String, cause: Throwable) extends RuntimeException(message, cause)

/** Invalid or unsupported model data detected before a trustworthy solve result was available. */
final class LpModelException private[spark_lp](message: String) extends LpException(message, null)

/**
  * Failure of the numerical linear algebra used by the continuous solver.
  *
  * The exception identifies initialization or an outer iteration and records the number of completed
  * iterations. The solver checks the last completed iterate for an infeasibility certificate before
  * throwing; a valid certificate is returned as an [[LpStatus]] instead. No variable values are
  * exposed with this exception.
  */
final class LpNumericalException private[spark_lp](
  val phase: String,
  val completedIterations: Int,
  message: String,
  cause: Throwable) extends LpException(message, cause)

/**
  * Configuration for one solve of an [[LpProblem]].
  *
  * @param tolerance maximum normalized primal, stationarity and objective-gap residual for an
  *                  optimal continuous result
  * @param infeasibilityTolerance maximum residual accepted for an infeasibility or unboundedness
  *                               certificate
  * @param maxIterations maximum completed outer interior-point iterations per continuous solve
  * @param etaIteration fraction of the largest step that keeps primal and dual variables positive
  * @param valueCap largest magnitude accepted by guarded unregularized solver operations
  * @param epsilon positive numerical floor used by guarded divisions
  * @param maxLocalConstraints maximum equality-form rows allowed by explicit driver-local
  *                            Cholesky; [[NewtonSolver.Auto]] may switch to CG at a lower value
  * @param newtonSolver method used for the predictor and corrector linear systems
  * @param cgTolerance relative residual target for a conjugate-gradient linear solve
  * @param cgMaxIterations conjugate-gradient step limit per right-hand side; values below one choose
  *                        `min(max(100, 2*m), 1000)`
  * @param mip branch-and-bound limits and controls
  * @param stopAfterIteration optional driver callback for continuous models. Return true to stop
  *                           after the given completed iteration; active Spark work is not interrupted
  * @param cgConfig regularization and preconditioner limits used only by CG
  * @param control progress callbacks, stop checks, time limit and optional stagnation policy for
  *                continuous solves
  * @param relaxIntegrality solve integer and binary variables as continuous variables
  * @param boundInference limits for deriving finite integer bounds from constraints
  * @param presolve model-simplification effort and limits
  * @param start optional starting assignment mapped into the compiled model
  */
final case class SolveConfig(
  tolerance: Double = 1e-8,
  infeasibilityTolerance: Double = 1e-8,
  maxIterations: Int = 50,
  etaIteration: Double = 0.999,
  valueCap: Double = 1e20,
  epsilon: Double = 1e-20,
  maxLocalConstraints: Long = NewtonSolver.AutoCholeskyLimit.toLong,
  newtonSolver: NewtonSolver = NewtonSolver.Auto,
  cgTolerance: Double = 1e-10,
  cgMaxIterations: Int = 0,
  mip: MipConfig = MipConfig(),
  stopAfterIteration: Option[Int => Boolean] = None,
  cgConfig: com.github.vbmacher.spark_lp.newton.CgConfig = com.github.vbmacher.spark_lp.newton.CgConfig(),
  control: com.github.vbmacher.spark_lp.SolveControl = com.github.vbmacher.spark_lp.SolveControl(),
  relaxIntegrality: Boolean = false,
  boundInference: BoundInferenceConfig = BoundInferenceConfig(),
  presolve: PresolveConfig = PresolveConfig(),
  start: Option[LpStart] = None) {

  com.github.vbmacher.spark_lp.LP.validateParameters(
    tolerance, maxIterations, etaIteration, valueCap, epsilon, infeasibilityTolerance, cgTolerance)
  require(maxLocalConstraints >= 0, "maxLocalConstraints must be nonnegative")

  /** The concrete normal-equations solver for a model with `m` equality-form rows. */
  private[dsl] def resolvedNewtonSolver(m: Long): NewtonSolver =
    NewtonSolver.resolve(newtonSolver, m, maxLocalConstraints)
}

/**
  * Configuration for the built-in mixed-integer branch-and-bound search.
  *
  * @param maxNodes maximum launched search nodes; cut re-solves and strong-branching probes do not
  *                 count as nodes
  * @param integralityTolerance maximum distance from a whole number accepted as integral
  * @param gapTolerance relative gap at which the incumbent is accepted as optimal
  * @param absoluteGapTolerance absolute gap at which the incumbent is accepted as optimal
  * @param control progress callback, cooperative stop callback and optional search deadline
  * @param sosZeroTolerance largest absolute value treated as zero when validating SOS membership
  * @param search cutting-plane, strong-branching and parallel-node settings
  */
final case class MipConfig(
  maxNodes: Int = 1000,
  integralityTolerance: Double = 1e-6,
  gapTolerance: Double = 1e-9,
  absoluteGapTolerance: Double = 0.0,
  control: MipControl = MipControl(),
  sosZeroTolerance: Double = 1e-6,
  search: MipSearchConfig = MipSearchConfig()) {
  require(java.lang.Double.isFinite(sosZeroTolerance) && sosZeroTolerance >= 0.0, "SOS zero tolerance must be finite and nonnegative")
  require(absoluteGapTolerance >= 0.0 && !absoluteGapTolerance.isInfinite,
    "absoluteGapTolerance must be finite and nonnegative")
  require(maxNodes > 0, "maxNodes must be positive")
  require(integralityTolerance > 0.0 && integralityTolerance < 0.5,
    "integralityTolerance must be between 0 and 0.5 (exclusive)")
  require(gapTolerance >= 0.0 && !gapTolerance.isInfinite, "gapTolerance must be finite and nonnegative")
}

/**
  * Normalized errors of the returned continuous iterate.
  *
  * The values use the solver's internal minimization form:
  * `primal = ||Ax - b|| / (1 + ||b||)`, `dual = ||A^T lambda + s - c|| / (1 + ||c||)`,
  * `gap = |c^T x - b^T lambda| / (1 + |b^T lambda|)`.
  * For quadratic objectives, dual stationarity subtracts `Q*x`, and the gap compares
  * `0.5*x^T Q*x + c^T*x` with `b^T lambda - 0.5*x^T Q*x`.
  * At continuous [[LpStatus.Optimal]], all three are at most [[SolveConfig.tolerance]]. For a
  * mixed-integer result they describe the retained LP relaxation, not the branch-and-bound gap.
  *
  * @param primal equality and bound feasibility error
  * @param dual stationarity error
  * @param gap primal-versus-dual objective error
  */
final case class LpResiduals(primal: Double, dual: Double, gap: Double)

/**
  * Summary of a mixed-integer search in original objective units.
  *
  * `None` means that an incumbent, bound or gap was not established; it never means zero.
  *
  * @param incumbent best feasible integer objective found.
  * @param bestBound best proven objective bound over remaining nodes.
  * @param absoluteGap nonnegative difference between incumbent and best bound.
  * @param relativeGap absolute gap divided by `max(1, abs(incumbent))`.
  * @param processedNodes search nodes whose relaxation was processed.
  * @param openNodes nodes left unexplored when the search stopped.
  * @param termination branch-and-bound termination label.
  * @param elapsedSeconds wall-clock seconds spent in mixed-integer search.
  * @param search cut, strong-branching, and parallel-search counters.
  */
final case class MipSummary(incumbent: Option[Double], bestBound: Option[Double],
  absoluteGap: Option[Double], relativeGap: Option[Double], processedNodes: Int,
  openNodes: Int, termination: String, elapsedSeconds: Double = 0.0,
  search: MipSearchStatistics = MipSearchStatistics())

private[dsl] object MipGap {
  def absolute(incumbent: Double, bound: Double): Double = math.max(0.0, incumbent - bound)
  def relative(absolute: Double, originalIncumbent: Double): Double =
    absolute / math.max(1.0, math.abs(originalIncumbent))
  def accepted(incumbent: Double, bound: Double, originalIncumbent: Double, config: MipConfig): Boolean =
    !incumbent.isNaN && !incumbent.isInfinite && !bound.isNaN && !bound.isInfinite && {
      val gap = absolute(incumbent, bound)
      gap <= config.absoluteGapTolerance || relative(gap, originalIncumbent) <= config.gapTolerance
    }
}
