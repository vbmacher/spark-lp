package com.github.vbmacher.spark_lp.dsl

import java.lang.management.ManagementFactory
import scala.util.control.NonFatal

/**
  * Final timing metadata for one solve, in seconds. Wall durations use a monotonic clock.
  * `driverProcessCpuSeconds` is JVM driver-process CPU only: it includes concurrent driver
  * threads, excludes executor/cluster CPU, and is `None` when the JVM cannot provide it.
  */
final case class LpSolveTimings(
  compilationWallSeconds: Double,
  numericalSolveWallSeconds: Double,
  resultReconstructionWallSeconds: Double,
  totalWallSeconds: Double,
  driverProcessCpuSeconds: Option[Double])

private[dsl] final class LpSolveClock(
  val nanoTime: () => Long,
  val driverProcessCpuTime: () => Option[Long])

private[dsl] object LpSolveClock {
  private val NoCpuTime: () => Option[Long] = () => None

  private val ProcessCpuTime: () => Option[Long] =
    ManagementFactory.getOperatingSystemMXBean match {
      case bean: com.sun.management.OperatingSystemMXBean => () =>
        try {
          val value = bean.getProcessCpuTime
          if (value < 0L) None else Some(value)
        } catch { case NonFatal(_) => None }
      case _ => NoCpuTime
    }

  val system: LpSolveClock = new LpSolveClock(() => System.nanoTime(), ProcessCpuTime)
}

/** One mutable, solve-local phase accumulator; the immutable public value is created once. */
private[dsl] final class LpSolveTiming(clock: LpSolveClock) {
  private val NanosPerSecond = 1000000000.0
  private val Compilation = 0
  private val NumericalSolve = 1
  private val Reconstruction = 2

  private var phase = Compilation
  private var totalStart = 0L
  private var phaseStart = 0L
  private var compilationNanos = 0L
  private var numericalSolveNanos = 0L
  private var cpuStart: Option[Long] = None
  private var started = false

  def start(): Unit = {
    require(!started, "Solve timing already started")
    totalStart = clock.nanoTime()
    phaseStart = totalStart
    cpuStart = cpuTime()
    started = true
  }

  def startNumericalSolve(): Unit = {
    require(started && phase == Compilation, "Numerical solve timing started out of order")
    val now = clock.nanoTime()
    compilationNanos = nonnegative(now - phaseStart)
    phaseStart = now
    phase = NumericalSolve
  }

  def startReconstruction(): Unit = {
    require(started && phase != Reconstruction, "Result reconstruction timing started out of order")
    val now = clock.nanoTime()
    if (phase == Compilation) compilationNanos = nonnegative(now - phaseStart)
    else numericalSolveNanos = nonnegative(now - phaseStart)
    phaseStart = now
    phase = Reconstruction
  }

  def finish(): LpSolveTimings = {
    require(started && phase == Reconstruction, "Solve timing finished out of order")
    val cpuEnd = cpuTime()
    val now = clock.nanoTime()
    val cpuNanos = for {
      start <- cpuStart
      end <- cpuEnd
      if end >= start
    } yield end - start
    LpSolveTimings(
      seconds(compilationNanos),
      seconds(numericalSolveNanos),
      seconds(nonnegative(now - phaseStart)),
      seconds(nonnegative(now - totalStart)),
      cpuNanos.map(seconds))
  }

  private def cpuTime(): Option[Long] =
    try clock.driverProcessCpuTime() catch { case NonFatal(_) => None }

  private def nonnegative(value: Long): Long = math.max(0L, value)
  private def seconds(nanos: Long): Double = nanos / NanosPerSecond
}
