package com.github.vbmacher.spark_lp

/** Metrics from one completed optimization iteration, always describing the same iterate. */
final case class IterationProgress(
  objectiveValue: Double,
  primalResidual: Double,
  dualResidual: Double,
  dualityGap: Double,
  feasible: Boolean)

/** Work within one setup or right-hand-side solve. Counts are local to that operation:
  * factor blocks or preconditioner pivots during setup; solver steps during an inner solve.
  * `total` is absent when unknown. Residuals are unpreconditioned norms; `trueResidual`
  * marks a recomputed residual, otherwise the iterative recurrence supplied it.
  * `preconditionerRank` is present only for backends using a rank-based preconditioner.
  */
final case class WorkProgress(
  completed: Int,
  total: Option[Int] = None,
  residual: Option[Double] = None,
  trueResidual: Boolean = false,
  preconditionerRank: Option[Int] = None)

/** A driver event stamped by the solve monitor. Iteration zero denotes initialization;
  * inner/setup events otherwise identify the outer iteration currently being computed.
  * Phase-entry events have no metrics. `iterate` is present only after a completed outer
  * iteration; `work` describes setup or inner work and never a partially updated LP iterate.
  */
final case class SolveProgress(
  phase: SolvePhase,
  iteration: Int,
  elapsedSeconds: Double,
  iterate: Option[IterationProgress] = None,
  work: Option[WorkProgress] = None)
