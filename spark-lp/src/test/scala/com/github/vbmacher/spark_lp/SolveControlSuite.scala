package com.github.vbmacher.spark_lp

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

class SolveControlSuite extends AnyFunSuite {
  test("independently constructed default controls compare equal") {
    val defaults = SolveControl()

    val independentlyConstructedDefaults = SolveControl()

    assert(independentlyConstructedDefaults == defaults,
      "default callbacks must not make an integer model controlled")
  }

  test("progress events report the current iteration, elapsed time and supplied work") {
    val events = ArrayBuffer.empty[SolveProgress]
    var now = 0L
    val monitor = new SolveMonitor(SolveControl(onProgress = events += _), () => now)

    monitor.phase(SolvePhase.Initialization)
    monitor.iteration = 3
    now = 2000000000L
    monitor.report(SolvePhase.SystemSetup, work = Some(WorkProgress(2, Some(4))))

    assert(events.size == 2)
    val initialization = events(0)
    val systemSetup = events(1)
    assert(initialization.iteration == 0)
    assert(initialization.iterate.isEmpty)
    assert(initialization.work.isEmpty)
    assert(systemSetup.iteration == 3)
    assert(systemSetup.elapsedSeconds == 2.0)
    assert(systemSetup.work.contains(WorkProgress(2, Some(4))))
    assert(systemSetup.iterate.isEmpty)
  }

  test("the deadline stops the solve at the configured elapsed time") {
    var now = 100L
    val monitor = new SolveMonitor(SolveControl(timeLimit = Some(10.nanos)), () => now)

    // One nanosecond before the deadline, work can still continue.
    now = 109L
    monitor.check()

    now = 110L
    val stop = intercept[SolveStopped](monitor.check())

    assert(stop.reason == StopReason.TimeLimit)
  }

  test("progress window tolerates regressions before stopping on noise") {
    val config = StagnationConfig(patience = 3, absoluteImprovement = 0.1, relativeImprovement = 0.0)
    val window = new ProgressWindow(config, 3)
    val residuals = Seq(10.0, 9.0, 9.5, 8.5, 8.8, 8.45, 8.49)

    val stops = residuals.zipWithIndex.map { case (residual, step) =>
      window.observe(step, Vector(residual))
    }

    assert(stops == Seq(false, false, false, false, false, false, true))
  }

  test("successive gains below the threshold accumulate and keep the window open") {
    val config = StagnationConfig(patience = 3, absoluteImprovement = 0.1, relativeImprovement = 0.0)
    val window = new ProgressWindow(config, 3)
    val residuals = (0 to 20).map(step => 10.0 - step * 0.06)

    val stops = residuals.zipWithIndex.map { case (residual, step) =>
      window.observe(step, Vector(residual))
    }

    assert(!stops.contains(true))
  }

  test("improving residuals keep a solve running when the objective is flat") {
    val policy = new OuterProgress(StagnationConfig(patience = 2))

    val stops = (1 to 10).map { iteration =>
      val residual = 1.0 / iteration
      policy.observe(iteration, true, 5.0, 0.0, 0.0, residual, residual)
    }

    assert(!stops.contains(true))
  }

  test("regaining feasibility does not reset the stagnation window") {
    val policy = new OuterProgress(StagnationConfig(patience = 2))
    // Establish the same best objective and residuals the solve will return to.
    (1 to 10).foreach { iteration =>
      val residual = 1.0 / iteration
      policy.observe(iteration, true, 5.0, 0.0, 0.0, residual, residual)
    }

    val stoppedWhileInfeasible = policy.observe(11, false, 0.0, 1.0, 1.0, 1.0, 1.0)
    val stoppedAfterRegainingFeasibility = policy.observe(12, true, 5.0, 0.0, 0.0, 0.1, 0.1)

    assert(!stoppedWhileInfeasible)
    assert(stoppedAfterRegainingFeasibility)
  }

  test("inner progress stops after 100 cumulative steps without sufficient residual improvement") {
    val policy = new ProgressWindow(StagnationConfig(relativeImprovement = 0.1), 100)

    val stoppedInitially = policy.observe(0, Vector(1.0))
    val stoppedAfterImprovement = policy.observe(50, Vector(0.8))
    val stoppedAfter50Steps = policy.observe(100, Vector(0.79))
    val stoppedAfter100Steps = policy.observe(150, Vector(0.78))

    assert(!stoppedInitially)
    assert(!stoppedAfterImprovement)
    assert(!stoppedAfter50Steps)
    assert(stoppedAfter100Steps)
  }
}
