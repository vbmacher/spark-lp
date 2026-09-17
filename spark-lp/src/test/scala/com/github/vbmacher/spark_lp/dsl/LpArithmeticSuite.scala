package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.dsl.implicits._
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.SparkSession
import org.scalatest.funsuite.AnyFunSuite

class LpArithmeticSuite extends AnyFunSuite with DataFrameSuiteBase {
  test("division and local variable dot products solve the manually expanded model") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("arithmetic")
    val x = model.variable("x")
    val y = model.variable("y")
    val variables = Seq(x, y)
    model += lpDot(Seq(2.0, 3.0), variables) / 2.0 + 4.0
    model += (x / -2.0 <= -1.0)
    model += (y >= 1.0)
    val result = model.solve()
    try {
      assert(result.status == LpStatus.Optimal)
      assert(math.abs(result.value(x) - 2.0) < 1e-6)
      assert(math.abs(result.value(y) - 1.0) < 1e-6)
      assert(math.abs(result.objectiveValue - 7.5) < 1e-6)
    } finally result.close()
  }

  test("dot products preserve constants, repeated terms and empty-input zero") {
    val value = lpDot(Seq(2.0, -3.0), Seq(LpExpr.constant(4.0), LpExpr.constant(5.0)))
    assert(value.constant == -7.0)
    assert((value / -2.0).constant == 3.5)
    assert(lpDot(Seq.empty[Double], Seq.empty[LpExpr]).constant == 0.0)
    intercept[LpModelException](lpDot(Seq(1.0), Seq.empty[LpExpr]))
    intercept[LpModelException](lpDot(Seq.empty[Double], Seq(LpExpr.zero)))
  }

  test("invalid scalars fail explicitly") {
    for (value <- Seq(0.0, -0.0, Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity))
      intercept[LpModelException](LpExpr.constant(1.0) / value)
    for (value <- Seq(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity))
      intercept[LpModelException](lpDot(Seq(value), Seq(LpExpr.constant(1.0))))
  }
}
