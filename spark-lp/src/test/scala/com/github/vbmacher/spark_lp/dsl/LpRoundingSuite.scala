package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.dsl.implicits._
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.SparkSession
import org.scalatest.funsuite.AnyFunSuite

class LpRoundingSuite extends AnyFunSuite with DataFrameSuiteBase {
  test("integer and bound snapping are explicit, finite and category-aware") {
    val rule = LpRounding(1e-5, 1e-6)
    assert(rule(1.9999999, Integer, 0.2, Some(3.8)) == 2.0)
    assert(rule(1.99, Integer, 0.0, None) == 1.99)
    assert(rule(1.9999999, Continuous, 0.0, None) == 1.9999999)
    assert(rule(-2.0 - 1e-7, Continuous, -2.0, None) == -2.0)
    assert(rule(3.0 + 1e-7, Continuous, 0.0, Some(3.0)) == 3.0)
    assert(rule(0.9999999, Binary, 0.2, None) == 1.0)
    for (bad <- Seq(-1.0, Double.NaN, Double.PositiveInfinity)) {
      intercept[IllegalArgumentException](LpRounding(bad))
      intercept[IllegalArgumentException](LpRounding(boundTolerance = bad))
    }
    intercept[IllegalArgumentException](LpRounding(0.5))
  }

  test("rounded scalar and distributed reports preserve raw results even when snapping violates a row") {
    implicit val ss: SparkSession = spark
    val localSpark = spark
    import localSpark.implicits._
    val model = LpProblem("report")
    val x = model.variable("x")
    val family = model.variables("family", Seq("a", "b").toDF("key"), $"key")
    model += x + family.sum
    model += (x >= 1e-4)
    model += (family.sum >= 2e-4)
    val raw = model.solve()
    val report = raw.rounded(LpRounding(boundTolerance = 1e-3))
    try {
      val objective = raw.objectiveValue
      val candidate = raw.candidate
      assert(raw.value(x) > 0.0 && report.value(x) == 0.0) // violates x >= 1e-4
      assert(report.values(family).filter($"lp_value" =!= 0.0).count() == 0)
      assert(raw.values(family).filter($"lp_value" > 0.0).count() == 2)
      assert(raw.objectiveValue == objective && raw.candidate == candidate)
      assert(raw.value(x) > 0.0)
      raw.close()
      intercept[LpModelException](report.value(x))
      intercept[LpModelException](report.values(family))
    } finally raw.close()
  }
}
