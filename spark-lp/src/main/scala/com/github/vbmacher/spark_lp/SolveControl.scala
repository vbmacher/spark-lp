package com.github.vbmacher.spark_lp

import scala.concurrent.duration.FiniteDuration

/** Reason that a configured policy stopped a solve before optimality. */
sealed trait StopReason
object StopReason {
  case object IterationLimit extends StopReason
  case object TimeLimit extends StopReason
  case object UserRequested extends StopReason
  case object NoProgress extends StopReason
}

/**
  * Describes whether a solve returned usable variable values.
  *
  * @param available true when at least one complete iterate or incumbent is retained
  * @param feasible true when the retained values satisfy the original model within the candidate
  *                 validation tolerance
  * @param iteration completed outer iteration that produced the values; absent for values not tied
  *                  to one continuous iteration
  */
final case class CandidateInfo(available: Boolean, feasible: Boolean, iteration: Option[Int])
object CandidateInfo {
  val Unavailable: CandidateInfo = CandidateInfo(false, false, None)
}

/**
  * Optional no-progress policy for a continuous solve.
  *
  * @param patience completed outer iterations allowed without meaningful progress
  * @param absoluteImprovement absolute part of the meaningful-improvement threshold
  * @param relativeImprovement relative part, multiplied by the magnitude of the best value
  * @param innerPatience CG steps allowed without meaningful true-residual improvement
  * @param maxInnerSteps maximum CG steps for one right-hand side across retries and rank changes
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

/**
  * Driver-side progress and stopping controls for continuous solves.
  *
  * Callbacks run synchronously and should return quickly. Stop requests are cooperative: they are
  * checked between safe solver operations and cannot interrupt an active Spark action or native
  * call. The time limit starts after model compilation and excludes result reconstruction.
  *
  * @param onProgress receives initialization, linear-system and completed-iteration events
  * @param shouldStop polled at safe boundaries; return true to request [[StopReason.UserRequested]]
  * @param timeLimit optional numerical-solve deadline
  * @param stagnation optional no-progress policy; disabled when absent
  * @param feasibilityTolerance tolerance used to label retained candidate values as feasible
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
