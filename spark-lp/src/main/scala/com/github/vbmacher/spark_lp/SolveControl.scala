package com.github.vbmacher.spark_lp

import scala.concurrent.duration.FiniteDuration

sealed trait StopReason
object StopReason {
  case object IterationLimit extends StopReason
  case object TimeLimit extends StopReason
  case object UserRequested extends StopReason
  case object NoProgress extends StopReason
}

/** Metadata for returned values. An absent iterate has no iteration number. */
final case class CandidateInfo(available: Boolean, feasible: Boolean, iteration: Option[Int])
object CandidateInfo {
  val Unavailable: CandidateInfo = CandidateInfo(false, false, None)
}

/** Opt-in heuristics. Patience counts completed outer iterations; inner patience counts CG
  * steps between meaningful true-residual improvements, across restarts and rank changes.
  */
final case class StagnationConfig(
  patience: Int = 10,
  absoluteImprovement: Double = 1e-10,
  relativeImprovement: Double = 1e-3,
  innerPatience: Int = 100,
  maxInnerSteps: Int = 2000) {
  require(patience > 0 && innerPatience > 0 && maxInnerSteps > 0, "Progress budgets must be positive")
  Seq(absoluteImprovement, relativeImprovement).foreach { value =>
    require(value >= 0.0 && !value.isNaN && !value.isInfinite, "Progress thresholds must be finite and nonnegative")
  }
}

/** Driver-only controls for continuous solves. The time budget starts immediately before
  * numerical initialization (after DSL compilation), and excludes result reconstruction.
  * Callbacks run synchronously on the driver and should be quick. Safe checks cannot interrupt
  * an active Spark action or native call. Stagnation detection is disabled by default.
  */
final case class SolveControl(
  onProgress: SolveProgress => Unit = SolveControl.IgnoreProgress,
  shouldStop: () => Boolean = SolveControl.Continue,
  timeLimit: Option[FiniteDuration] = None,
  stagnation: Option[StagnationConfig] = None,
  feasibilityTolerance: Double = 1e-8) {
  require(timeLimit.forall(_.toNanos > 0L), "Time limit must be positive")
  require(feasibilityTolerance > 0.0 && !feasibilityTolerance.isInfinite,
    "Candidate feasibility tolerance must be finite and positive")
}

object SolveControl {
  private val IgnoreProgress: SolveProgress => Unit = _ => ()
  private val Continue: () => Boolean = () => false
}
