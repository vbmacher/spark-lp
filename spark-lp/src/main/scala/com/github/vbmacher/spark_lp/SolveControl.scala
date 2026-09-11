package com.github.vbmacher.spark_lp

import scala.concurrent.duration.FiniteDuration
import scala.util.control.{ControlThrowable, NonFatal}

sealed trait SolvePhase
object SolvePhase {
  case object Initialization extends SolvePhase
  case object Cholesky extends SolvePhase
  case object Preconditioner extends SolvePhase
  case object InnerSolve extends SolvePhase
  case object OuterIteration extends SolvePhase
}

sealed trait StopReason
object StopReason {
  case object IterationLimit extends StopReason
  case object TimeLimit extends StopReason
  case object UserRequested extends StopReason
  case object NoProgress extends StopReason
}

/** All metrics describe this event, never a partially updated outer iterate. Inner residuals
  * are unpreconditioned; `trueResidual` distinguishes recomputed and recurrence residuals.
  */
final case class SolveProgress(
  phase: SolvePhase,
  iteration: Int,
  elapsedSeconds: Double,
  objectiveValue: Option[Double] = None,
  primalResidual: Option[Double] = None,
  dualResidual: Option[Double] = None,
  dualityGap: Option[Double] = None,
  innerSteps: Option[Int] = None,
  preconditionerRank: Option[Int] = None,
  innerResidual: Option[Double] = None,
  trueResidual: Boolean = false,
  feasible: Option[Boolean] = None,
  completedBlocks: Option[Int] = None)

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
  onProgress: Option[SolveProgress => Unit] = None,
  shouldStop: Option[() => Boolean] = None,
  timeLimit: Option[FiniteDuration] = None,
  stagnation: Option[StagnationConfig] = None,
  feasibilityTolerance: Double = 1e-8) {
  require(timeLimit.forall(_.toNanos > 0L), "Time limit must be positive")
  require(feasibilityTolerance > 0.0 && !feasibilityTolerance.isInfinite,
    "Candidate feasibility tolerance must be finite and positive")
}

private[spark_lp] final case class CallbackFailed(error: Throwable) extends ControlThrowable

private[spark_lp] final case class SolveStopped(reason: StopReason) extends ControlThrowable

/** One monitor per solve, shared by initialization and every Newton system. Never serialized. */
private[spark_lp] final class SolveMonitor(
  val control: SolveControl = SolveControl(),
  nanoTime: () => Long = () => System.nanoTime()) {
  private val started = nanoTime()
  var iteration: Int = 0
  def elapsedSeconds: Double = (nanoTime() - started).toDouble / 1e9
  def callback[A](body: => A): A = try body catch {
    case NonFatal(error) => throw CallbackFailed(error)
  }
  def check(): Unit = {
    if (callback(control.shouldStop.exists(_()))) throw SolveStopped(StopReason.UserRequested)
    if (control.timeLimit.exists(limit => nanoTime() - started >= limit.toNanos))
      throw SolveStopped(StopReason.TimeLimit)
  }
  def report(event: SolveProgress, terminal: Boolean = false): Unit = {
    callback(control.onProgress.foreach(_(event.copy(iteration = iteration, elapsedSeconds = elapsedSeconds))))
    if (!terminal) check()
  }
  def phase(phase: SolvePhase): Unit = report(SolveProgress(phase, iteration, 0.0))
}

/** Thresholds are measured against the last meaningful best, so cumulative small gains count
  * but oscillation around a plateau cannot reset patience. All scores are minimized.
  */
private[spark_lp] final class ProgressWindow(config: StagnationConfig, patience: Int) {
  private var best = Vector.empty[Double]
  private var lastProgress = 0
  def observe(step: Int, scores: Vector[Double]): Boolean = {
    require(scores.forall(v => !v.isNaN && !v.isInfinite), "Progress scores must be finite")
    if (best.isEmpty) { best = scores; lastProgress = step }
    else {
      require(best.size == scores.size, "Progress score dimensions changed")
      var improved = false
      best = best.zip(scores).map { case (previous, current) =>
        val threshold = config.absoluteImprovement + config.relativeImprovement * math.abs(previous)
        if (previous - current > threshold) { improved = true; current } else previous
      }
      if (improved) lastProgress = step
    }
    step - lastProgress >= patience
  }
}

private[spark_lp] final class OuterProgress(config: StagnationConfig) {
  private var feasibleSeen = false
  private var window = new ProgressWindow(config, config.patience)
  def observe(iteration: Int, feasible: Boolean, objective: Double,
    violation: Double, primal: Double, dual: Double, gap: Double): Boolean = {
    if (feasible && !feasibleSeen) {
      feasibleSeen = true
      window = new ProgressWindow(config, config.patience)
    }
    // Once feasibility is reached, regressions cannot create a fresh patience window.
    val scores = if (feasibleSeen) Vector(if (feasible) objective else Double.MaxValue, primal, dual, gap)
      else Vector(violation, primal, dual, gap)
    window.observe(iteration, scores)
  }
}
