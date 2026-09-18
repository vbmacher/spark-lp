package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.dsl.implicits._
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.SparkSession
import org.scalatest.funsuite.AnyFunSuite

class LpExpressionEditSuite extends AnyFunSuite with DataFrameSuiteBase {
  test("aggregated scalar edits and pending RHS copies preserve originals") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("edit")
    val x = model.variable("x")
    val y = model.variable("y")
    val original = x + 2.0 * x + y + 4.0
    val changed = original.withCoefficient(x, 5.0).withoutCoefficient(y).withConstant(-1.0)
    assert(original.coefficient(x) == 3.0 && original.constant == 4.0)
    assert(changed.coefficient(x) == 5.0 && changed.coefficient(y) == 0.0)
    val pending = (x >= 1.0).named("floor")
    val updated = pending.withRhs(2.0)
    assert(pending.rhs == 1.0 && updated.explicitName == pending.explicitName)
    model += changed
    model += updated
    model += (y === 0.0)
    val result = model.solve()
    try assert(math.abs(result.objectiveValue - 9.0) < 1e-6) finally result.close()
    intercept[LpModelException](original.withCoefficient(LpProblem("foreign").variable("z"), 1.0))
    for (bad <- Seq(Double.NaN, Double.PositiveInfinity)) {
      intercept[LpModelException](original.withCoefficient(x, bad))
      intercept[LpModelException](original.withConstant(bad))
      intercept[LpModelException](pending.withRhs(bad))
    }
  }

  test("relational coefficient replacement is lazy and inspection remains distributed") {
    implicit val ss: SparkSession = spark
    val localSpark = spark
    import localSpark.implicits._
    val reads = spark.sparkContext.longAccumulator("edits")
    val source = Seq(("a", 2.0), ("b", 3.0)).toDS().map { v => reads.add(1L); v }.toDF("key", "cost")
    val model = LpProblem("relational")
    val family = model.variables("x", source, $"key")
    val original = family.sum($"cost") + family.sum
    val changed = original.withCoefficient(family("a"), 7.0).withoutCoefficient(family("b"))
    assert(reads.value == 0L)
    assert(changed.coefficients.collect().map(_.value).toSeq == Seq(7.0))
    assert(original.coefficient(family("a")) == 3.0 && original.coefficient(family("b")) == 4.0)
    val copied = model.copy()
    assert(copied.expression(changed).coefficient(copied.variable(family("a"))) == 7.0)
    val cancelled = original - original
    assert(cancelled.coefficients.count() == 0)
  }
}
