package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.dsl.implicits._
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.struct
import org.scalatest.funsuite.AnyFunSuite

class LpPortableModelSuite extends AnyFunSuite with DataFrameSuiteBase {
  test("a portable mixed-integer model reconstructs its optimum and keyed identities") {
    implicit val ss: SparkSession = spark
    val localSpark = spark
    import localSpark.implicits._
    val model = LpProblem("portable")
    val xs = model.variables("x", Seq(("a", 1), ("b", 2)).toDF("g", "i"), struct($"g", $"i"), category = Binary)
    val free = model.variable("free", Double.NegativeInfinity)
    val fixed = model.variable("fixed", 2.0, Some(2.0), Integer)
    model += xs.sum + free + fixed + 4.0
    model += (xs.sumBy("g")() >= 1.0).named("minimum")
    model += (free === -1.0).named("free")
    val selectedMember = LpPortableModel.variable(xs(("a", 1)))

    val imported = LpPortableModel.fromView(model.inspect).toProblem()
    val copied = imported.model.solve()
    try {
      assert(copied.status == LpStatus.Optimal)
      assert(math.abs(copied.objectiveValue - 7.0) < 1e-6)
      assert(copied.value(imported.variable(selectedMember.id)) == 1.0)
      assert(imported.constraintIds.size == 3)
      assert(model.constraints.size == 2)

      // Export the reconstructed candidate using the same portable identities.
      val solutionData = LpSolutionData.fromSolution(copied)

      assert(solutionData.values.exists(_.count() == 4))
      assert(solutionData.objective.exists(objective => math.abs(objective - 7.0) < 1e-6))
    } finally copied.close()

    val original = model.solve()
    try {
      assert(original.status == LpStatus.Optimal)
      assert(math.abs(original.objectiveValue - 7.0) < 1e-6)
    } finally original.close()
  }

  test("portable expressions and pending constraints bind to the reconstructed model") {
    implicit val ss: SparkSession = spark
    val localSpark = spark
    import localSpark.implicits._
    val model = LpProblem("portable components")
    val items = model.variables("item", Seq("a", "b").toDF("key"), $"key")
    val free = model.variable("free", Double.NegativeInfinity)
    val itemId = LpPortableModel.variable(items("a")).id
    val freeId = LpPortableModel.variable(free).id
    val expression = LpPortableModel.expression(items.sum + 3.0 * free + 8.0)
    val constraint = LpPortableModel.constraint((free <= 0.0).named("pending"))

    val imported = LpPortableModel.fromView(model.inspect).toProblem()
    val reboundExpression = imported.expression(expression)
    val reboundConstraint = imported.constraint(constraint)

    assert(reboundExpression.constant == 8.0)
    assert(reboundExpression.coefficient(imported.variable(freeId)) == 3.0)
    assert(reboundExpression.coefficient(imported.variable(itemId)) == 1.0)
    assert(reboundConstraint.rhs == 0.0)
  }

  test("import rejects unsupported schema, duplicate identities, invalid coefficients and excess rows") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("invalid")
    val x = model.variable("x", upperBound = Some(1.0))
    model += lpSum(x)
    model += (x >= 0.0)
    val data = LpPortableModel.fromView(model.inspect)

    val schemaError = intercept[IllegalArgumentException](data.copy(schemaVersion = 2).toProblem())

    assert(schemaError.getMessage.contains("Unsupported portable model schema 2"))

    val missingVariable = LpCoefficient(LpVariableId(9, "missing"), 1.0)
    val missingReference = LpAffineData(sc.parallelize(Seq(missingVariable)), 0.0)
    val nonFiniteCoefficient = data.objective.copy(coefficients = data.objective.coefficients.map(_.copy(value = Double.NaN)))
    val invalidModels = Seq(
      "duplicate variable" -> data.copy(variables = data.variables.union(data.variables)),
      "missing variable reference" -> data.copy(objective = missingReference),
      "non-finite objective coefficient" -> data.copy(objective = nonFiniteCoefficient))

    invalidModels.foreach { case (scenario, invalid) =>
      withClue(s"$scenario: ") {
        intercept[LpModelException](invalid.toProblem())
      }
    }

    val rowLimitError = intercept[LpModelException](data.toProblem(maxLocalRows = 0))

    assert(rowLimitError.getMessage.contains("exceeds 0 local constraint rows"))
  }
}
