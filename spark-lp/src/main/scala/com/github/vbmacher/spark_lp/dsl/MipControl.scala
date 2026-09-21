package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.SolveProgress
import scala.concurrent.duration.FiniteDuration

/**
  * Snapshot of whole-search progress; node-iteration events are delivered separately.
  *
  * @param processedNodes search nodes whose relaxations have been processed.
  * @param openNodes nodes waiting in the search queue.
  * @param incumbent best feasible integer objective found, in original objective units.
  * @param bestBound best proven objective bound over open nodes, in original objective units.
  * @param absoluteGap nonnegative incumbent-minus-bound gap when both values exist.
  * @param relativeGap absolute gap divided by `max(1, abs(incumbent))`.
  * @param elapsedSeconds wall-clock seconds since mixed-integer search began.
  */
final case class MipProgress(
  processedNodes: Int,
  openNodes: Int,
  incumbent: Option[Double],
  bestBound: Option[Double],
  absoluteGap: Option[Double],
  relativeGap: Option[Double],
  elapsedSeconds: Double
)

/**
  * Driver callbacks, stop controls, and clock for mixed-integer search.
  *
  * The time budget covers search setup and every root, child, cut-round, and probe relaxation.
  *
  * @param timeLimit optional whole-search wall-clock limit.
  * @param shouldStop polled at safe boundaries; return true to request a cooperative stop.
  * @param onProgress receives whole-search snapshots after meaningful state changes.
  * @param onNodeProgress receives `(nodeIndex, event)` for continuous relaxation progress.
  * @param nanoTime monotonic clock source, injectable for deterministic tests.
  */
final case class MipControl(
  timeLimit: Option[FiniteDuration] = None,
  shouldStop: () => Boolean = MipControl.Continue,
  onProgress: MipProgress => Unit = MipControl.Ignore,
  onNodeProgress: (Int, SolveProgress) => Unit = MipControl.IgnoreNode,
  nanoTime: () => Long = MipControl.Clock
) {
  require(timeLimit.forall(_.toNanos > 0L), "MIP time limit must be positive")
}

object MipControl {
  private val Continue: () => Boolean = () => false
  private val Ignore: MipProgress => Unit = _ => ()
  private val IgnoreNode: (Int, SolveProgress) => Unit = (_, _) => ()
  private val Clock: () => Long = () => System.nanoTime()
}
