package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.dsl.implicits._
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.SparkSession
import org.scalatest.funsuite.AnyFunSuite

class LpMipGapIntegrationSuite extends AnyFunSuite with DataFrameSuiteBase {
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
        assert(metadata.bestBound.nonEmpty)
        assert(metadata.absoluteGap.exists(g => g >= 0.0 && g <= 0.6))
        val bound = metadata.bestBound.get

        assert(if (sense == Minimize) bound <= result.objectiveValue + 1e-8 else bound >= result.objectiveValue - 1e-8)
        assert(metadata.relativeGap.contains(metadata.absoluteGap.get / math.max(1.0, math.abs(result.objectiveValue))))
      } finally result.close()

      val stopped = model.solve(SolveConfig(mip = MipConfig(maxNodes = 1)))
      try {
        assert(stopped.status == LpStatus.IterationLimit)
        assert(stopped.mip.get.incumbent.isEmpty)
        assert(stopped.mip.get.absoluteGap.isEmpty)
        assert(stopped.mip.get.termination == "NodeLimit")
      } finally stopped.close()
    }
  }

  test("a feasible rounded incumbent only closes a node within the configured gap") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("rounded incumbent", Maximize)
    val b = model.variable("b", category = Binary)
    val y = model.variable("y", upperBound = Some(1000.0))
    model += b - y
    model += (y - 1.2 * b >= -0.3)

    val mip = MipConfig(integralityTolerance = 0.4, gapTolerance = 0.0)
    val limited = model.solve(SolveConfig(tolerance = 1e-3, mip = mip.copy(maxNodes = 1)))
    try {
      assert(limited.status == LpStatus.IterationLimit)
      assert(limited.candidate.feasible)
      assert(limited.value(b) == 0.0)
      assert(limited.mip.get.absoluteGap.exists(_ > 0.2))
    } finally limited.close()

    val continued = model.solve(SolveConfig(tolerance = 1e-3, mip = mip))
    try {
      assert(continued.value(b) == 1.0)
      assert(math.abs(continued.objectiveValue - 0.1) < 1e-3)
      assert(continued.status != LpStatus.Optimal || continued.mip.get.absoluteGap.contains(0.0))
    } finally continued.close()
  }
}
