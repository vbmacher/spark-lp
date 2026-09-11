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
  * not converge is reported as [[LpStatus.IterationLimit]] or [[LpStatus.Stopped]].
  *
  * For models containing [[Integer]] or [[Binary]] variables, the same members retain their
  * truthful meaning across the discrete search: [[LpStatus.Optimal]] requires every candidate
  * subproblem to be resolved; [[LpStatus.Infeasible]] requires every leaf to be proved infeasible
  * by a certificate or contradictory row bounds;
  * and an unresolved subproblem degrades the result to [[LpStatus.IterationLimit]], never to a
  * stronger claim.
  */
sealed trait LpStatus

object LpStatus {

  /** All solver convergence conditions were met within tolerance. */
  case object Optimal extends LpStatus

  /**
    * The iteration or integer-node budget was reached, or an integer branch stayed unresolved;
    * the reported values are a retained iterate, which need NOT
    * be primal-feasible (see [[LpResiduals]]).
    */
  case object IterationLimit extends LpStatus

  /** A cooperative stop; inspect candidate metadata for availability and primal feasibility. */
  case object Stopped extends LpStatus

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
  * iteration's normal-equations solve (a Cholesky breakdown or a stalled conjugate-gradient run),
  * or a zero iterate element. Names the phase (initialization or
  * iteration k) and the count of completed iterations. No iterate values are exposed — a
  * numerically failed run has no iterate with meaningful convergence semantics.
  *
  * A failure during an iteration is thrown only when it is not explained by infeasibility: the
  * solver first re-runs the Farkas certificate tests (at the same
  * `SolveConfig.infeasibilityTolerance`) on the last completed iterate and reports
  * [[LpStatus.Infeasible]]/[[LpStatus.Unbounded]]/[[LpStatus.InfeasibleOrUnbounded]] instead when
  * a certificate holds. Without a certificate, the failure is numerical. Rank-deficient
  * matrices require the regularized matrix-free backend; Cholesky still requires full row rank.
  */
final class LpNumericalException private[spark_lp](
  val phase: String,
  val completedIterations: Int,
  message: String,
  cause: Throwable) extends LpException(message, cause)

/**
  * Solver configuration.
  *
  * @param stopAfterIteration optional driver callback for continuous models. Return true to stop
  *                           after the given completed iteration and retain the best feasible values. No Spark
  *                           jobs are interrupted; validation/reconstruction may finish afterward.
  * @param control driver progress callbacks, cooperative stops and opt-in stagnation policy for
  *                continuous models; candidate feasibility uses its separate tolerance.
  *
  * @param infeasibilityTolerance threshold of the Farkas certificate tests behind
  *                               [[LpStatus.Infeasible]], [[LpStatus.Unbounded]] and
  *                               [[LpStatus.InfeasibleOrUnbounded]]: a certificate is claimed only
  *                               when its normalized residual is at or below this value.
  * @param maxLocalConstraints resource cap on the number of equality-form constraint rows `m`
  *                            allowed for the driver-local Cholesky normal-equations solver.
  *                            With Cholesky, every constraint row is driver-local: the driver
  *                            holds roughly `16 * m * m` bytes of Gramian-related allocations per
  *                            solve, plus an `O(m^3)` factorization per iteration. Models with
  *                            more rows are handled according to `newtonSolver`: the default
  *                            [[NewtonSolver.Auto]] switches to the matrix-free conjugate-gradient
  *                            solver. It avoids the driver-local `m x m` Gramian; its optional
  *                            partial-Cholesky preconditioner uses bounded `O(m * rank)` driver
  *                            and task-local storage. An explicit [[NewtonSolver.Cholesky]]
  *                            rejects them.
  * @param newtonSolver how the per-iteration `m x m` normal-equations systems are solved (see
  *                     [[NewtonSolver]]). [[NewtonSolver.Auto]] picks Cholesky up to the smaller
  *                     of `NewtonSolver.AutoCholeskyLimit` and `maxLocalConstraints`, then CG.
  * @param cgTolerance relative residual at which one conjugate-gradient solve is accepted
  *                    (matrix-free solver only).
  * @param cgMaxIterations conjugate-gradient step limit per normal-equations solve; values < 1
  *                        select `min(max(100, 2m), 1000)` (matrix-free solver only).
  * @param matrixFree primal/dual regularization and bounded preconditioner controls (CG only).
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
  matrixFree: com.github.vbmacher.spark_lp.MatrixFreeConfig = com.github.vbmacher.spark_lp.MatrixFreeConfig(),
  control: com.github.vbmacher.spark_lp.SolveControl = com.github.vbmacher.spark_lp.SolveControl()) {

  com.github.vbmacher.spark_lp.LP.validateParameters(
    tolerance, maxIterations, etaIteration, valueCap, epsilon, infeasibilityTolerance, cgTolerance)
  require(maxLocalConstraints >= 0, "maxLocalConstraints must be nonnegative")

  /** The concrete normal-equations solver for a model with `m` equality-form rows. */
  private[dsl] def resolvedNewtonSolver(m: Long): NewtonSolver = newtonSolver match {
    case NewtonSolver.Auto =>
      if (m <= math.min(maxLocalConstraints, NewtonSolver.AutoCholeskyLimit.toLong)) NewtonSolver.Cholesky
      else NewtonSolver.ConjugateGradient
    case s => s
  }
}

/** Limits for the driver-side branch-and-bound search. */
final case class MipConfig(
  maxNodes: Int = 1000,
  integralityTolerance: Double = 1e-6,
  gapTolerance: Double = 1e-9) {
  require(maxNodes > 0, "maxNodes must be positive")
  require(integralityTolerance > 0.0 && integralityTolerance < 0.5,
    "integralityTolerance must be between 0 and 0.5 (exclusive)")
  require(gapTolerance >= 0.0 && !gapTolerance.isInfinite, "gapTolerance must be finite and nonnegative")
}

/**
  * Final residuals of the returned iterate, in the solver's minimization form:
  * `primal = ||Ax - b|| / (1 + ||b||)`, `dual = ||A^T lambda + s - c|| / (1 + ||c||)`,
  * `gap = |c^T x - b^T lambda| / (1 + |b^T lambda|)`.
  * For continuous solves, all three are below `tolerance` at [[LpStatus.Optimal]]. For integer
  * models these describe the retained LP relaxation, not the global search gap.
  */
final case class LpResiduals(primal: Double, dual: Double, gap: Double)
