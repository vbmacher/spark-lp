package com.github.vbmacher.spark_lp

/**
  * Metrics from one completed outer optimization iteration.
  *
  * @param objectiveValue objective in the solver's internal minimization form
  * @param primalResidual normalized constraint-feasibility error
  * @param dualResidual normalized stationarity error
  * @param dualityGap normalized primal-versus-dual objective error
  * @param feasible whether the iterate satisfies the original model within the configured candidate
  *                 tolerance
  */
final case class IterationProgress(
  objectiveValue: Double,
  primalResidual: Double,
  dualResidual: Double,
  dualityGap: Double,
  feasible: Boolean)

/**
  * Progress within one linear-system setup or solve.
  *
  * Counts restart for each operation. During setup they count factor blocks or preconditioner
  * columns; during an inner solve they count solver steps.
  *
  * @param completed completed units of the current operation
  * @param total total units when known
  * @param residual unpreconditioned residual norm when available
  * @param trueResidual true when `residual` was recomputed from the equations rather than updated
  *                     by the iterative recurrence
  * @param preconditionerRank current rank when the backend uses a rank-based preconditioner
  */
final case class WorkProgress(
  completed: Int,
  total: Option[Int] = None,
  residual: Option[Double] = None,
  trueResidual: Boolean = false,
  preconditionerRank: Option[Int] = None)

/**
  * One driver-side solver progress event.
  *
  * Iteration zero denotes initialization. Later setup and inner-solve events identify the outer
  * iteration being computed. `iterate` is present only for a completed outer iteration; `work`
  * never represents partially updated model values.
  *
  * @param phase solver operation that emitted the event.
  * @param iteration current outer iteration; zero denotes initialization.
  * @param elapsedSeconds wall-clock seconds since numerical solving began.
  * @param iterate metrics for a completed outer iterate, when the event completes one.
  * @param work progress within the current setup or inner-solve operation, when available.
  */
final case class SolveProgress(
  phase: SolvePhase,
  iteration: Int,
  elapsedSeconds: Double,
  iterate: Option[IterationProgress] = None,
  work: Option[WorkProgress] = None)
