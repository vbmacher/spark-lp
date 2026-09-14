package com.github.vbmacher.spark_lp

import scala.util.control.{ControlThrowable, NonFatal}

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
    if (callback(control.shouldStop())) throw SolveStopped(StopReason.UserRequested)
    if (control.timeLimit.exists(limit => nanoTime() - started >= limit.toNanos))
      throw SolveStopped(StopReason.TimeLimit)
  }
  def report(phase: SolvePhase, iterate: Option[IterationProgress] = None,
    work: Option[WorkProgress] = None, terminal: Boolean = false): Unit = {
    callback(control.onProgress(SolveProgress(phase, iteration, elapsedSeconds, iterate, work)))
    if (!terminal) check()
  }
  def phase(phase: SolvePhase): Unit = report(phase)
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
