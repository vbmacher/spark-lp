package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.dsl.implicits._
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.SparkSession
import org.scalatest.funsuite.AnyFunSuite

/**
  * Keyed coefficient row used by DSL validation tests.
  *
  * @param name variable-member key.
  * @param w coefficient joined to that member.
  */
case class KeyedWeight(name: String, w: Double)

class LpDslValidationSuite extends AnyFunSuite with DataFrameSuiteBase {

  test("inconsistent duplicate equality rows are rejected with both constraint names") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("dup", Minimize)
    val x = model.variable("x")
    model += lpSum(x)
    model += (x === 1.0).named("first")
    model += (x === 2.0).named("second")

    val error = intercept[LpModelException](model.solve())

    assert(error.getMessage.contains("first"))
    assert(error.getMessage.contains("second"))
    assert(error.getMessage.toLowerCase.contains("duplicate"))
  }

  test("identical duplicate equality rows are merged with a diagnostics note") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("dupOk", Minimize)
    val x = model.variable("x")
    val y = model.variable("y")
    model += x + 2.0 * y
    model += (x + y === 4.0).named("first")
    model += (x + y === 4.0).named("second")

    val solution = model.solve()
    try {
      val notes = solution.constraints.collect().map(row => row.getString(0) -> Option(row.getString(7))).toMap

      assert(solution.status == LpStatus.Optimal)
      assert(math.abs(solution.value(x) - 4.0) < 1e-4)
      assert(math.abs(solution.value(y)) < 1e-4)
      assert(notes("first").isEmpty)
      assert(notes("second").exists(_.contains("merged")))
    } finally solution.close()
  }

  test("duplicate detection checks scaled equality contradictions in both row orders") {
    implicit val ss: SparkSession = spark
    for (reverse <- Seq(false, true)) {
      val model = LpProblem("scaled contradiction")
      val x = model.variable("x")
      val rows = Seq((x === 0.0).named("unit"), (1e9 * x === 0.5).named("scaled"))
      (if (reverse) rows.reverse else rows).foreach(row => model += row)

      val error = intercept[LpModelException](model.solve())

      assert(error.getMessage.contains("inconsistent duplicate rows"))
      assert(error.getMessage.contains("'unit'"))
      assert(error.getMessage.contains("'scaled'"))
    }
  }

  test("scaling a duplicate equality row leaves the optimal result unchanged") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("dupScaled", Minimize)
    val x = model.variable("x")
    val y = model.variable("y")
    model += x + 2.0 * y
    model += (x + y === 1.0).named("unit")
    model += (2.0 * x + 2.0 * y === 2.0).named("doubled")

    val solution = model.solve()

    try {
      assert(solution.status == LpStatus.Optimal)
      assert(math.abs(solution.value(x) - 1.0) < 1e-4)
      assert(math.abs(solution.objectiveValue - 1.0) < 1e-4)
    } finally solution.close()
  }

  test("duplicate variable keys in a DataFrame domain are rejected with display names") {
    implicit val ss: SparkSession = spark
    val ssLocal = spark
    import ssLocal.implicits._
    val model = LpProblem("dupKeys", Minimize)
    val domain = Seq("a", "b", "a").toDF("k")
    val v = model.variables("v", domain, $"k")
    model += lpSum(v)
    model += (lpSum(v) >= 1.0).named("floor")

    val error = intercept[LpModelException](model.solve())

    assert(error.getMessage.contains("duplicate"))
    assert(error.getMessage.contains("v[a]"))
  }

  test("null variable keys in the domain are rejected") {
    implicit val ss: SparkSession = spark
    val ssLocal = spark
    import ssLocal.implicits._
    val model = LpProblem("nullKeys", Minimize)
    val domain = Seq(Some("a"), None).toDF("k")
    val v = model.variables("v", domain, $"k")
    model += lpSum(v)
    model += (lpSum(v) >= 1.0).named("floor")

    val error = intercept[LpModelException](model.solve())

    assert(error.getMessage.contains("null"))
  }

  test("adding a second objective with += is rejected") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("obj", Minimize)
    val x = model.variable("x")
    model += lpSum(x)

    val error = intercept[LpModelException](model += 2.0 * x)

    assert(error.getMessage.contains("objective"))
  }

  test("setObjective replaces the original objective used by the solve") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("replace objective", Minimize)
    val x = model.variable("x")
    model += lpSum(x)
    model += (x >= 2.0).named("floor")

    model.setObjective(3.0 * x)
    val solution = model.solve()

    try assert(math.abs(solution.objectiveValue - 6.0) < 1e-5)
    finally solution.close()
  }

  test("a model without an objective solves feasibility") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("noObj", Minimize)
    val x = model.variable("x")
    model += (x >= 2.0).named("floor")

    val result = model.solve()
    try {
      assert(result.status == LpStatus.Optimal)
      assert(result.objectiveValue == 0.0)
      assert(result.value(x) >= 2.0 - 1e-8)
    } finally result.close()
  }

  test("a model without constraints has an analytic optimum") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("empty", Minimize)
    val x = model.variable("x")
    model += lpSum(x)

    val result = model.solve()
    try {
      assert(result.status == LpStatus.Optimal)
      assert(result.iterations == 0)
      assert(result.value(x) == 0.0)
      assert(result.objectiveValue == 0.0)
    } finally result.close()
  }

  test("an unresolvable coefficient column is an LpModelException naming the context") {
    implicit val ss: SparkSession = spark
    val ssLocal = spark
    import ssLocal.implicits._
    val model = LpProblem("badCol", Minimize)
    val domain = Seq("a", "b").toDF("k")
    val v = model.variables("v", domain, $"k")
    model += lpSum(v * $"no_such_column")
    model += (lpSum(v) >= 1.0).named("floor")

    val error = intercept[LpModelException](model.solve())

    assert(error.getMessage.contains("objective"))
  }

  test("duplicate keys in a weightedBy source are rejected") {
    implicit val ss: SparkSession = spark
    val ssLocal = spark
    import ssLocal.implicits._
    val model = LpProblem("dupWeights", Minimize)
    val domain = spark.createDataset(Seq(KeyedWeight("a", 0.0), KeyedWeight("b", 0.0)))
    val v = model.variablesOf("v", domain, (k: KeyedWeight) => k.name)
    val weights = spark.createDataset(Seq(KeyedWeight("a", 1.0), KeyedWeight("a", 2.0)))
    model += lpSum(v.weightedBy(weights)(_.w))
    model += (lpSum(v) >= 1.0).named("floor")

    val error = intercept[LpModelException](model.solve())

    assert(error.getMessage.contains("duplicate"))
    assert(error.getMessage.contains("pre-aggregate"))
  }

  test("weightedBy keys absent from the domain are rejected") {
    implicit val ss: SparkSession = spark
    val ssLocal = spark
    import ssLocal.implicits._
    val model = LpProblem("foreignWeights", Minimize)
    val domain = spark.createDataset(Seq(KeyedWeight("a", 0.0)))
    val v = model.variablesOf("v", domain, (k: KeyedWeight) => k.name)
    val weights = spark.createDataset(Seq(KeyedWeight("a", 1.0), KeyedWeight("z", 2.0)))
    model += lpSum(v.weightedBy(weights)(_.w))
    model += (lpSum(v) >= 1.0).named("floor")

    val error = intercept[LpModelException](model.solve())

    assert(error.getMessage.contains("absent from the domain"))
  }

  test("a domain key absent from the weightedBy source contributes zero") {
    implicit val ss: SparkSession = spark
    val ssLocal = spark
    import ssLocal.implicits._
    val model = LpProblem("zeroWeights", Minimize)
    val domain = spark.createDataset(Seq(KeyedWeight("a", 0.0), KeyedWeight("b", 0.0)))
    val v = model.variablesOf("v", domain, (k: KeyedWeight) => k.name)
    // Only member a has a coefficient in the weighted constraint.
    val weights = spark.createDataset(Seq(KeyedWeight("a", 1.0)))
    model += lpSum(v)
    model += (lpSum(v.weightedBy(weights)(_.w)) >= 3.0).named("floor")

    val solution = model.solve()

    try {
      assert(solution.status == LpStatus.Optimal)
      assert(math.abs(solution.objectiveValue - 3.0) < 1e-4)
    } finally solution.close()
  }

  test("a NaN coefficient is rejected during model compilation") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("nonFinite", Minimize)
    val x = model.variable("x")
    model += lpSum(x)
    model += (Double.NaN * x >= 1.0).named("nanCoeff")

    val error = intercept[LpModelException](model.solve())

    assert(error.getMessage.contains("non-finite coefficient"))
  }

  test("an infinite right-hand side is rejected during model compilation") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("nonFiniteRhs", Minimize)
    val x = model.variable("x")
    model += lpSum(x)
    model += (x >= Double.PositiveInfinity).named("infRhs")

    val error = intercept[LpModelException](model.solve())

    assert(error.getMessage.contains("right-hand side"))
  }

  test("exceeding maxLocalConstraints with explicit Cholesky names the asymmetry and the way out") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("tooMany", Minimize)
    val x = model.variable("x")
    model += lpSum(x)
    model += (x >= 1.0).named("c1")
    model += (x <= 5.0).named("c2")
    model += (x <= 6.0).named("c3")

    val error = intercept[LpModelException](
      model.solve(SolveConfig(maxLocalConstraints = 2, newtonSolver = NewtonSolver.Cholesky)))

    assert(error.getMessage.contains("maxLocalConstraints"))
    assert(error.getMessage.contains("driver-local"))
    assert(error.getMessage.contains("16*m*m"))
    assert(error.getMessage.contains("ConjugateGradient"))
  }

  test("bound rows count against maxLocalConstraints and name the responsible variable set") {
    implicit val ss: SparkSession = spark
    val ssLocal = spark
    import ssLocal.implicits._
    val model = LpProblem("boundRows", Minimize)
    val domain = (1 to 4).map(_.toString).toDF("k")
    val v = model.variables("v", domain, $"k", upperBound = Some(1.0))
    model += lpSum(v)
    model += (lpSum(v) >= 1.0).named("floor")

    val error = intercept[LpModelException](
      model.solve(SolveConfig(maxLocalConstraints = 3, newtonSolver = NewtonSolver.Cholesky)))

    assert(error.getMessage.contains("upper bound rows"))
    assert(error.getMessage.contains("'v'"))
  }

  test("variables from a different problem are rejected") {
    implicit val ss: SparkSession = spark
    val model1 = LpProblem("one", Minimize)
    val model2 = LpProblem("two", Minimize)
    val foreign = model2.variable("foreign")
    val x = model1.variable("x")
    model1 += lpSum(x)
    model1 += (x + foreign >= 1.0).named("mixed")

    val error = intercept[LpModelException](model1.solve())

    assert(error.getMessage.contains("different problem"))
  }

  test("duplicate constraint names after expansion are rejected") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("dupNames", Minimize)
    val x = model.variable("x")
    model += lpSum(x)
    model += (x >= 1.0).named("same")
    model += (x <= 5.0).named("same")

    val error = intercept[LpModelException](model.solve())

    assert(error.getMessage.contains("same"))
    assert(error.getMessage.toLowerCase.contains("duplicate"))
  }
}
