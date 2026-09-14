package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.dsl.implicits._
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.col
import org.scalatest.funsuite.AnyFunSuite

class CoupledQpSuite extends AnyFunSuite with DataFrameSuiteBase {
  private val backends = Seq(NewtonSolver.Cholesky, NewtonSolver.ConjugateGradient)

  test("off-diagonal PSD factors have an independent original-space KKT witness") {
    implicit val ss: SparkSession = spark
    backends.foreach { backend =>
      val m = LpProblem("coupled")
      val x = m.variable("x")
      val y = m.variable("y")
      m += QpObjective.squared(x + 2.0 * y - 5.0) + QpObjective.squared(x - y - 1.0)
      val result = m.solve(SolveConfig(newtonSolver = backend, maxIterations = 100))
      try {
        assert(result.status == LpStatus.Optimal)
        val xv = result.value(x); val yv = result.value(y)
        // F=[[1,2],[1,-1]], target=[5,1], Q=2F^T F has nonzero off-diagonal.
        val a = xv + 2*yv - 5; val b = xv - yv - 1
        assert(math.abs(xv - 7.0/3) < 1e-4 && math.abs(yv - 4.0/3) < 1e-4)
        assert(math.abs(2*a + 2*b) < 1e-4 && math.abs(4*a - 2*b) < 1e-4)
        assert(math.abs(result.objectiveValue - (a*a + b*b)) < 1e-7)
      } finally result.close()
    }
  }

  test("rank-deficient PSD, free variables, shifts, fixed variables and objective sense") {
    implicit val ss: SparkSession = spark
    for (backend <- Seq(NewtonSolver.Auto, NewtonSolver.ConjugateGradient); sense <- Seq(Minimize, Maximize)) {
      val m = LpProblem("transformed coupled", sense)
      val x = m.variable("free", lowerBound = Double.NegativeInfinity)
      val y = m.variable("shifted", lowerBound = -2, upperBound = Some(4.0))
      val fixed = m.variable("fixed", lowerBound = 2, upperBound = Some(2.0))
      val objective = QpObjective.squared(x + y + fixed - 5.0)
      m += objective * (if (sense == Minimize) 1.0 else -1.0)
      m += (x - y === 1.0)
      val result = m.solve(SolveConfig(newtonSolver = backend, maxIterations = 100))
      try {
        assert(result.status == LpStatus.Optimal)
        assert(math.abs(result.value(x) - 2) < 2e-4)
        assert(math.abs(result.value(y) - 1) < 2e-4)
        assert(result.value(fixed) == 2.0)
        assert(math.abs(result.objectiveValue) < 1e-7)
      } finally result.close()
    }
  }

  test("keyed sparse factors aggregate repeated terms and repeated solves do not mutate the model") {
    implicit val ss: SparkSession = spark
    val localSpark = spark
    import localSpark.implicits._
    val m = LpProblem("keyed factors")
    val vars = m.variables("x", Seq(("a", 1.0), ("b", 2.0)).toDF("key", "a"), col("key"))
    m += QpObjective.squared(vars.sum(col("a")) + vars.sum(col("a")) - 6.0)
    m += (vars.sum === 2.0)
    val handlesBefore = m.handles.size
    val constraintsBefore = m.constraints.size
    for (_ <- 1 to 2) {
      val result = m.solve()
      try {
        assert(result.status == LpStatus.Optimal)
        val values = result.values(vars).select("lp_value").collect().map(_.getDouble(0))
        assert(values.forall(v => math.abs(v - 1.0) < 1e-4))
        assert(m.handles.size == handlesBefore && m.constraints.size == constraintsBefore)
      } finally result.close()
    }
    m.setObjective(vars.sum)
    val linear = m.solve()
    try assert(math.abs(linear.objectiveValue - 2) < 1e-6) finally linear.close()
  }

  test("invalid factor sense and integer categories are rejected; zero factors use the LP path") {
    implicit val ss: SparkSession = spark
    val m = LpProblem("invalid factors")
    val x = m.variable("x")
    m += QpObjective.squared(x) * -1.0
    m += (x >= 1.0)
    intercept[LpModelException](m.solve())
    m.setObjective(QpObjective.squared(x, 0.0) + x)
    val linear = m.solve()
    try assert(math.abs(linear.objectiveValue - 1) < 1e-6) finally linear.close()
    val integer = LpProblem("integer")
    val i = integer.variable("i", upperBound = Some(2.0), category = Integer)
    integer += QpObjective.squared(i)
    intercept[LpModelException](integer.solve())
  }
  test("rank-deficient bounded factors support Cholesky, while free factors select regularized CG") {
    implicit val ss: SparkSession = spark
    val m = LpProblem("rank one")
    val x = m.variable("x")
    val y = m.variable("y")
    m += QpObjective.squared(x + y - 3.0)
    m += (x - y === 1.0)
    val result = m.solve(SolveConfig(newtonSolver = NewtonSolver.Cholesky))
    try {
      assert(result.status == LpStatus.Optimal)
      assert(math.abs(result.value(x) - 2) < 1e-4 && math.abs(result.value(y) - 1) < 1e-4)
    } finally result.close()
    val freeModel = LpProblem("free")
    val f = freeModel.variable("f", lowerBound = Double.NegativeInfinity)
    freeModel += QpObjective.squared(f - 2.0)
    val error = intercept[LpModelException](freeModel.solve(SolveConfig(newtonSolver = NewtonSolver.Cholesky)))
    assert(error.getMessage.contains("requires regularized ConjugateGradient"))
  }

}
