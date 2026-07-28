package com.github.vbmacher.spark_lp.dsl

/** Direction of the objective. */
sealed trait ObjectiveSense
case object Minimize extends ObjectiveSense
case object Maximize extends ObjectiveSense

/** Category of a decision variable. The solver honours every category selected by the model. */
sealed trait VariableCategory
case object Continuous extends VariableCategory

/**
  * Integral decision variable. Its effective domain is the integral values within its declared
  * finite bounds.
  */
case object Integer extends VariableCategory

/**
  * `{0, 1}` decision variable, intersected with any declared bounds (e.g. `lowerBound = 1` pins
  * the variable to 1).
  */
case object Binary extends VariableCategory

/**
  * Truthful outcome of one solve. `Infeasible` and the two unboundedness-related members are
  * claimed only when a Farkas certificate backs them (see each member); everything else that did
  * not converge is reported as [[LpStatus.IterationLimit]].
  *
  * For models containing [[Integer]] or [[Binary]] variables, the same members retain their
  * truthful meaning across the discrete search: [[LpStatus.Optimal]] requires every candidate
  * subproblem to be resolved; [[LpStatus.Infeasible]] requires every leaf to be certificate-pruned;
  * and an unresolved subproblem degrades the result to [[LpStatus.IterationLimit]], never to a
  * stronger claim.
  */
sealed trait LpStatus

object LpStatus {

  /** All solver convergence conditions were met within tolerance. */
  case object Optimal extends LpStatus

  /**
    * `maxIterations` was reached (or the solver's divergence backstop stopped a run whose
    * residuals had grown past recovery); the reported values are the last iterate, which need NOT
    * be primal-feasible (see [[LpResiduals]]).
    */
  case object IterationLimit extends LpStatus

  /**
    * A Farkas certificate of primal infeasibility was found within
    * `SolveConfig.infeasibilityTolerance`: a ray `y` with `A^T y <= infeasibilityTolerance`
    * componentwise and `b^T y = 1`, proving no feasible point exists. The `constraints`
    * diagnostics still expose the last iterate; its `slack` column locates the conflicting rows.
    */
  case object Infeasible extends LpStatus

  /**
    * A Farkas certificate of dual infeasibility was found within
    * `SolveConfig.infeasibilityTolerance` and the last iterate is primal-feasible within
    * `tolerance`: the objective is unbounded in the optimization direction.
    */
  case object Unbounded extends LpStatus

  /**
    * A Farkas certificate of dual infeasibility was found, but no primal-feasible point is known:
    * the problem cannot be optimal, but the two remaining cases (infeasible or unbounded) are not
    * distinguished.
    */
  case object InfeasibleOrUnbounded extends LpStatus
}

sealed abstract class LpException(message: String, cause: Throwable) extends RuntimeException(message, cause)

/** Model or validation error; always names the offending variable, constraint, or key. */
final class LpModelException private[spark_lp](message: String) extends LpException(message, null)

/**
  * Numerical failure inside the solver: a non-positive-definite Gramian during initialization or an
  * iteration's Cholesky step, or a zero iterate element. Names the phase (initialization or
  * iteration k) and the count of completed iterations. No iterate values are exposed — a
  * numerically failed run has no iterate with meaningful convergence semantics.
  *
  * A failure during an iteration is thrown only when it is not explained by infeasibility: the
  * solver first re-runs the Farkas certificate tests (at the same
  * `SolveConfig.infeasibilityTolerance`) on the last completed iterate and reports
  * [[LpStatus.Infeasible]]/[[LpStatus.Unbounded]]/[[LpStatus.InfeasibleOrUnbounded]] instead when
  * a certificate holds. Genuine precondition violations (e.g. a rank-deficient constraint matrix)
  * still throw.
  */
final class LpNumericalException private[spark_lp](
  val phase: String,
  val completedIterations: Int,
  message: String,
  cause: Throwable) extends LpException(message, cause)

/**
  * Solver configuration.
  *
  * @param infeasibilityTolerance threshold of the Farkas certificate tests behind
  *                               [[LpStatus.Infeasible]], [[LpStatus.Unbounded]] and
  *                               [[LpStatus.InfeasibleOrUnbounded]]: a certificate is claimed only
  *                               when its normalized residual is at or below this value.
  * @param maxLocalConstraints upper limit on the number of equality-form constraint rows. Every
  *                            constraint row is driver-local: for `m` rows the driver holds roughly
  *                            `16 * m * m` bytes of Gramian-related allocations per solve, plus an
  *                            `O(m^3)` Cholesky factorization per iteration. The variable count is
  *                            the distributed dimension and can be large; the constraint count is not.
  */
final case class SolveConfig(
  tolerance: Double = 1e-8,
  infeasibilityTolerance: Double = 1e-8,
  maxIterations: Int = 50,
  etaIteration: Double = 0.999,
  valueCap: Double = 1e20,
  epsilon: Double = 1e-20,
  maxLocalConstraints: Long = 5000L)

/**
  * Final residuals of the returned iterate, in the solver's minimization form:
  * `primal = ||Ax - b|| / (1 + ||b||)`, `dual = ||A^T lambda + s - c|| / (1 + ||c||)`,
  * `gap = |c^T x - b^T lambda| / (1 + |b^T lambda|)`.
  * All three are below `tolerance` iff status is [[LpStatus.Optimal]].
  */
final case class LpResiduals(primal: Double, dual: Double, gap: Double)
