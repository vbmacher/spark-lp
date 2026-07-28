package com.github.vbmacher.spark_lp.dsl

/** Direction of the objective. */
sealed trait ObjectiveSense
case object Minimize extends ObjectiveSense
case object Maximize extends ObjectiveSense

/** Category of a decision variable. Only [[Continuous]] is accepted in this release. */
sealed trait VariableCategory
case object Continuous extends VariableCategory

/** Reserved; rejected in this release. */
case object Integer extends VariableCategory

/** Reserved; rejected in this release. */
case object Binary extends VariableCategory

/** Truthful outcome of one solve. There are deliberately no `Infeasible`/`Unbounded` members yet. */
sealed trait LpStatus

object LpStatus {

  /** All solver convergence conditions were met within tolerance. */
  case object Optimal extends LpStatus

  /**
    * `maxIterations` was reached; the reported values are the last iterate, which need NOT be
    * primal-feasible (see [[LpResiduals]]).
    */
  case object IterationLimit extends LpStatus
}

sealed abstract class LpException(message: String, cause: Throwable) extends RuntimeException(message, cause)

/** Model or validation error; always names the offending variable, constraint, or key. */
final class LpModelException private[spark_lp](message: String) extends LpException(message, null)

/**
  * Numerical failure inside the solver: a non-positive-definite Gramian during initialization or an
  * iteration's Cholesky step, or a zero iterate element. Names the phase (initialization or
  * iteration k) and the count of completed iterations. No iterate values are exposed — a
  * numerically failed run has no iterate with meaningful convergence semantics.
  */
final class LpNumericalException private[spark_lp](
  val phase: String,
  val completedIterations: Int,
  message: String,
  cause: Throwable) extends LpException(message, cause)

/**
  * Solver configuration.
  *
  * @param maxLocalConstraints upper limit on the number of equality-form constraint rows. Every
  *                            constraint row is driver-local: for `m` rows the driver holds roughly
  *                            `16 * m * m` bytes of Gramian-related allocations per solve, plus an
  *                            `O(m^3)` Cholesky factorization per iteration. The variable count is
  *                            the distributed dimension and can be large; the constraint count is not.
  */
final case class SolveConfig(
  tolerance: Double = 1e-8,
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
