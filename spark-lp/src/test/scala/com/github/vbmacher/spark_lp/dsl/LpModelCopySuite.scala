package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.dsl.implicits._
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{col, struct}
import org.scalatest.funsuite.AnyFunSuite

class LpModelCopySuite extends AnyFunSuite with DataFrameSuiteBase {
  test("copies preserve identities while objectives, constraints and result ownership are independent") {
    implicit val ss: SparkSession = spark
    val original = LpProblem("original")
    val x = original.variable("x")
    original += x + 3.0
    original += (x >= 2.0).named("minimum")
    val copied = original.copy("scenario")
    val y = copied.variable(x)
    copied.model += (y >= 4.0)
    copied.model.setObjective(2.0 * y)
    val a = original.solve()
    val b = copied.model.solve()
    try {
      assert(math.abs(a.objectiveValue - 5.0) < 1e-6)
      assert(math.abs(b.objectiveValue - 8.0) < 1e-6)
      intercept[LpModelException](b.value(x))
      a.close()
      assert(math.abs(b.value(y) - 4.0) < 1e-6)
      intercept[LpModelException](copied.variable(y))
    } finally { a.close(); b.close() }
  }

  test("composite-key grouped binary models and typed coefficients bind to the copied model") {
    implicit val ss: SparkSession = spark
    val localSpark = spark
    import localSpark.implicits._
    val model = LpProblem("keyed", Maximize)
    val xs = model.variables("xs", Seq(("a", 1), ("b", 2)).toDF("group", "index"),
      struct(col("group"), col("index")), category = Binary)
    model += xs.sum
    model += (xs.sumBy("group")() <= 1.0).named("capacity")
    val copied = model.copy()
    val result = copied.model.solve()
    try {
      assert(result.status == LpStatus.Optimal)
      assert(result.objectiveValue == 2.0)
      assert(result.values(copied.variables(xs)).count() == 2)
    } finally result.close()
    val typed = LpProblem("typed")
    val input = Seq("a", "b").toDS()
    val amounts = typed.variablesOf("amounts", input, (s: String) => s)
    typed += lpSum(amounts.weightedBy(input)(_ => 2.0))
    typed += (amounts.sum >= 3.0)
    val tc = typed.copy()
    val tr = tc.model.solve()
    try assert(math.abs(tr.objectiveValue - 6.0) < 1e-6) finally tr.close()
  }

  test("quadratic factor copies preserve the complete objective") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("qp")
    val x = model.variable("x")
    model += QpObjective.squared(x - 2.0)
    val copied = model.copy()
    val result = copied.model.solve()
    try assert(math.abs(result.value(copied.variable(x)) - 2.0) < 1e-5) finally result.close()
    assert(copied.model.handles.size == model.handles.size)
  }
}
