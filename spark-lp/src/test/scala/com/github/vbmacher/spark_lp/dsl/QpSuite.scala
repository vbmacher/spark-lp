package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.dsl.implicits._
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.col
import org.scalatest.funsuite.AnyFunSuite

class QpSuite extends AnyFunSuite with DataFrameSuiteBase {
  private val backends = Seq(NewtonSolver.Cholesky, NewtonSolver.ConjugateGradient)

  test("weighted deviations have independently checked equality KKT witnesses on both backends") {
    implicit val ss: SparkSession = spark
    backends.foreach { backend =>
      val m = LpProblem("weighted")
      val x = m.variable("x")
      val y = m.variable("y")
      m += QpObjective.squaredDeviation(x, 2.0) + QpObjective.squaredDeviation(y, 4.0, 2.0)
      m += (x + y === 3.0)
      val result = m.solve(SolveConfig(newtonSolver = backend, maxIterations = 100))
      try {
        assert(result.status == LpStatus.Optimal)
        val xv = result.value(x); val yv = result.value(y)
        // x=0, y=3, equality multiplier=-4; gradients are both -4, bound multiplier=0.
        assert(math.abs(xv) < 2e-4 && math.abs(yv - 3.0) < 2e-4)
        val boundMultiplier = 2 * (xv - 2) - 4 * (yv - 4)
        assert(boundMultiplier >= -1e-7 && math.abs(xv * boundMultiplier) < 1e-6)
        assert(math.abs(result.objectiveValue - 6.0) < 1e-6)
        assert(result.residuals.dual < 1e-8 && result.residuals.gap < 1e-8)
      } finally result.close()
    }
  }

  test("shifts, fixed values, upper bounds and concave maximization preserve objective constants") {
    implicit val ss: SparkSession = spark
    for (backend <- backends; sense <- Seq(Minimize, Maximize)) {
      val m = LpProblem("transforms", sense)
      val x = m.variable("x", lowerBound = -3, upperBound = Some(1.0))
      val fixed = m.variable("fixed", lowerBound = 5, upperBound = Some(5.0))
      val objective = QpObjective.squaredDeviation(x, 2) + QpObjective.squaredDeviation(fixed, 3, 2)
      m += objective * (if (sense == Minimize) 1.0 else -1.0)
      val result = m.solve(SolveConfig(newtonSolver = backend, maxIterations = 100))
      try {
        assert(result.status == LpStatus.Optimal)
        assert(math.abs(result.value(x) - 1.0) < 1e-6)
        assert(result.value(fixed) == 5.0)
        assert(math.abs(result.objectiveValue - (if (sense == Minimize) 9.0 else -9.0)) < 1e-6)
      } finally result.close()
    }
  }

  test("unconstrained separable QP and keyed curvature remain distributed") {
    implicit val ss: SparkSession = spark
    val localSpark = spark
    import localSpark.implicits._
    val m = LpProblem("keyed")
    val x = m.variables("x", Seq(("a", 2.0, -4.0), ("b", 4.0, -12.0)).toDF("key", "q", "c"), col("key"))
    m += QpObjective.separable(x.sum(col("q")), x.sum(col("c")))
    val result = m.solve()
    try {
      assert(result.status == LpStatus.Optimal)
      val values = result.values(x).select("key", "lp_value").collect().map(r => r.getString(0) -> r.getDouble(1)).toMap
      assert(math.abs(values("a") - 2) < 1e-6 && math.abs(values("b") - 3) < 1e-6)
      assert(math.abs(result.objectiveValue + 22) < 1e-6)
    } finally result.close()
  }

  test("zero curvature agrees with LP and rejects invalid curvature, categories and curved free variables") {
    implicit val ss: SparkSession = spark
    val m = LpProblem("zero")
    val x = m.variable("x")
    m += QpObjective.separable(0.0 * x, x)
    m += (x >= 2.0)
    val qp = m.solve()
    m.setObjective(x)
    val lp = m.solve()
    try {
      assert(qp.status == lp.status && math.abs(qp.objectiveValue - lp.objectiveValue) < 1e-9)
    } finally { qp.close(); lp.close() }
    for ((q, lower, cat) <- Seq((-1.0, 0.0, Continuous), (Double.NaN, 0.0, Continuous),
      (1.0, Double.NegativeInfinity, Continuous), (1.0, 0.0, Integer), (1.0, 0.0, Binary))) {
      val invalid = LpProblem("invalid")
      val v = invalid.variable("v", lowerBound = lower, category = cat)
      invalid += QpObjective.separable(q * v, v)
      intercept[LpModelException](invalid.solve())
    }
  }

  test("positive curvature prevents an LP-only false unbounded certificate") {
    implicit val ss: SparkSession = spark
    backends.foreach { backend =>
      val m = LpProblem("bounded quadratic")
      val x = m.variable("x")
      m += QpObjective.separable(2.0 * x, -100.0 * x)
      m += (x >= 1.0)
      val result = m.solve(SolveConfig(newtonSolver = backend, maxIterations = 100))
      try {
        assert(result.status == LpStatus.Optimal)
        assert(math.abs(result.value(x) - 50) < 1e-5)
        assert(math.abs(result.objectiveValue + 2500) < 1e-5)
      } finally result.close()
    }
  }
  test("all-fixed quadratic objective is evaluated without invoking Newton") {
    implicit val ss: SparkSession = spark
    val m = LpProblem("fixed")
    val x = m.variable("x", lowerBound = 5, upperBound = Some(5.0))
    m += QpObjective.squaredDeviation(x, 2, 3)
    val result = m.solve()
    try {
      assert(result.status == LpStatus.Optimal && result.iterations == 0)
      assert(result.objectiveValue == 27.0)
    } finally result.close()
  }

  test("curvature-free recession component can still certify unboundedness") {
    implicit val ss: SparkSession = spark
    val m = LpProblem("flat recession")
    val x = m.variable("x")
    val y = m.variable("y")
    m += QpObjective.separable(2.0 * x, -1.0 * y)
    m += (x === 0.0)
    m += (y >= 1.0)
    val result = m.solve(SolveConfig(tolerance = 1e-4, maxIterations = 100))
    try assert(Set[LpStatus](LpStatus.Unbounded, LpStatus.InfeasibleOrUnbounded)(result.status))
    finally result.close()
  }

}
