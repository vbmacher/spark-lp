package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.dsl.implicits._
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.SparkSession
import org.scalatest.funsuite.AnyFunSuite

class LpMipGapSuite extends AnyFunSuite with DataFrameSuiteBase {
  test("either gap threshold suffices with explicit original-unit scaling and unavailable-bound guards") {
    assert(MipGap.accepted(10.0, 9.0, 10.0, MipConfig(gapTolerance = 0.0, absoluteGapTolerance = 1.0)))
    assert(MipGap.accepted(10.0, 9.0, -100.0, MipConfig(gapTolerance = 0.01)))
    assert(MipGap.accepted(10.0, 9.0, 100.0, MipConfig(gapTolerance = 0.01, absoluteGapTolerance = 1.0)))
    assert(!MipGap.accepted(10.0, 9.0, 0.0, MipConfig(gapTolerance = 0.1, absoluteGapTolerance = 0.5)))
    assert(!MipGap.accepted(100.0, 90.0, 100.0, MipConfig(gapTolerance = 0.0, absoluteGapTolerance = 1.0)))
    for (bound <- Seq(Double.NaN, Double.NegativeInfinity, Double.PositiveInfinity))
      assert(!MipGap.accepted(1.0, bound, 1.0, MipConfig(absoluteGapTolerance = 1e9)))
    for (t <- Seq(-1.0, Double.NaN, Double.PositiveInfinity))
      intercept[IllegalArgumentException](MipConfig(absoluteGapTolerance = t))
  }

  test("MIP bounds and gaps preserve maximization, constants and missing incumbents") {
    implicit val ss: SparkSession = spark
    for (sense <- Seq(Minimize, Maximize); constant <- Seq(0.0, -10.0, 10.0)) {
      val model = LpProblem("gap", sense)
      val x = model.variable("x", category = Binary)
      val y = model.variable("y", category = Binary)
      val sign = if (sense == Minimize) -1.0 else 1.0
      model += sign * (x + y) + constant
      model += (2.0 * x + 2.0 * y <= 3.0)
      val result = model.solve(SolveConfig(mip = MipConfig(gapTolerance = 0.0, absoluteGapTolerance = 0.6)))
      try {
        assert(result.status == LpStatus.Optimal)
        assert(math.abs(result.objectiveValue - (constant + sign)) < 1e-6)
        val metadata = result.mip.get
        assert(metadata.incumbent.contains(result.objectiveValue))
        assert(metadata.bestBound.nonEmpty && metadata.absoluteGap.exists(g => g >= 0.0 && g <= 0.6))
        val bound = metadata.bestBound.get
        assert(if (sense == Minimize) bound <= result.objectiveValue + 1e-8 else bound >= result.objectiveValue - 1e-8)
        assert(metadata.relativeGap.contains(metadata.absoluteGap.get / math.max(1.0, math.abs(result.objectiveValue))))
      } finally result.close()
      val stopped = model.solve(SolveConfig(mip = MipConfig(maxNodes = 1)))
      try {
        assert(stopped.status == LpStatus.IterationLimit)
        assert(stopped.mip.get.incumbent.isEmpty && stopped.mip.get.absoluteGap.isEmpty)
        assert(stopped.mip.get.termination == "NodeLimit")
      } finally stopped.close()
    }
  }
}
