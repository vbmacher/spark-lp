package com.github.vbmacher.spark_lp.support

/** Monotonic wall-clock timer for generation, preparation and solver work.
  *
  * Timing starts at construction and is based on `System.nanoTime`, so it is unaffected
  * by system clock adjustments.
  */
final class Stopwatch {
  private val started = System.nanoTime()

  /** Seconds elapsed since construction. */
  def seconds: Double = (System.nanoTime() - started) / 1e9
}
