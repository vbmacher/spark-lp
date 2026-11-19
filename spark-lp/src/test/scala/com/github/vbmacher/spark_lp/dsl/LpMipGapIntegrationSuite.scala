package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.dsl.implicits._
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.SparkSession
import org.scalatest.funsuite.AnyFunSuite

class LpMipGapIntegrationSuite extends AnyFunSuite with DataFrameSuiteBase {
  test("a large bound cannot mask a constraint violation introduced by MIP rounding") {
    implicit val ss: SparkSession = spark
    for (offset <- Seq(0.0, 1000.0)) {
      val model = LpProblem("rounded feasibility")
      val b = model.variable("b", category = Binary)
      val y = model.variable("y", upperBound = Some(1000.0))
      val fixed = model.variable("fixed", lowerBound = offset, upperBound = Some(offset))
      model += lpSum(y)
      model += (b + y + fixed === offset + 9e-7)

      val solution = model.solve()
      try {
        assert(solution.candidate.feasible)
        val assignment = Seq(b -> solution.value(b), y -> solution.value(y), fixed -> offset)
        val report = model.validateCandidate(model.candidateValues(assignment))
        try assert(report.feasible)
        finally report.close()
      } finally solution.close()
    }
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
}
