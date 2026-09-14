package com.github.vbmacher.spark_lp.support

import com.github.vbmacher.spark_lp.SolveProgress

/** Tracks core solve time separately from independent validation and progress phases.
  *
  * A single wall-clock timer runs for the whole solve; time spent in [[validate]] blocks
  * is accumulated and subtracted so that [[solveSeconds]] reflects solver work only.
  * Progress events attribute elapsed time to named phases and record preconditioner ranks.
  */
final class SolveMeasurements {
  private val clock = new Stopwatch
  private var validation = 0.0
  private var initialization = Option.empty[Double]
  private var phase = "Initialization"
  private var previous = 0.0
  private val phases = scala.collection.mutable.Map.empty[String, Double].withDefaultValue(0.0)
  private val ranks = scala.collection.mutable.ArrayBuffer.empty[Int]

  /** Folds a solver progress event into phase durations, initialization time and rank history. */
  def progress(event: SolveProgress): Unit = {
    if (event.iteration > 0 && initialization.isEmpty) initialization = Some(event.elapsedSeconds)
    val name = event.phase.toString
    if (name != phase) {
      phases(phase) += event.elapsedSeconds - previous
      phase = name;
      previous = event.elapsedSeconds
    }
    event.work.flatMap(_.preconditionerRank).foreach(r => if (!ranks.lastOption.contains(r)) ranks += r)
  }

  /** Runs `body`, charging its wall-clock duration to validation rather than solve time. */
  def validate[A](body: => A): A = {
    val clock = new Stopwatch
    try body finally validation += clock.seconds
  }

  /** Total elapsed seconds minus accumulated validation time. */
  def solveSeconds: Double = clock.seconds - validation

  /** Serialisable summary of solve, validation, initialization and per-phase seconds plus rank events.
    *
    * @param elapsed the final solve duration used to close out the current phase.
    */
  def snapshot(elapsed: Double): Map[String, Any] = Map(
    "solve_seconds" -> elapsed,
    "validation_seconds" -> validation, "initialization_seconds" -> initialization,
    "phase_seconds" -> (phases.toMap + (phase -> (phases(phase) + elapsed - previous))),
    "rank_events" -> ranks.toVector)
}
