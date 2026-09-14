package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.SolveProgress
import scala.concurrent.duration.FiniteDuration

/** Whole-search event; node progress is delivered separately and never represents an incumbent. */
final case class MipProgress(processedNodes: Int, openNodes: Int, incumbent: Option[Double],
  bestBound: Option[Double], absoluteGap: Option[Double], relativeGap: Option[Double], elapsedSeconds: Double)

/** Driver callbacks and a monotonic clock. The budget covers search setup and all root/child LP work. */
final case class MipControl(timeLimit: Option[FiniteDuration] = None,
  shouldStop: () => Boolean = MipControl.Continue,
  onProgress: MipProgress => Unit = MipControl.Ignore,
  onNodeProgress: (Int, SolveProgress) => Unit = MipControl.IgnoreNode,
  nanoTime: () => Long = MipControl.Clock) {
  require(timeLimit.forall(_.toNanos > 0L), "MIP time limit must be positive")
}
object MipControl {
  private val Continue: () => Boolean = () => false
  private val Ignore: MipProgress => Unit = _ => ()
  private val IgnoreNode: (Int, SolveProgress) => Unit = (_, _) => ()
  private val Clock: () => Long = () => System.nanoTime()
}
