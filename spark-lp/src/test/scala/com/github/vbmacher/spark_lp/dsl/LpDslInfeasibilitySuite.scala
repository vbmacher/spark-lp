package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.dsl.implicits._
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.SparkSession
import org.scalatest.funsuite.AnyFunSuite

class LpDslInfeasibilitySuite extends AnyFunSuite with DataFrameSuiteBase {

  test("Infeasible: conflicting inequalities yield a certificate-backed status with NaN objective") {
    implicit val ss: SparkSession = spark
    // x <= 1 and x >= 2: distinct rows after slack introduction, so presolve does not catch it
    val model = LpProblem("infeasible", Minimize)
    val x = model.variable("x")
    model += 1.0 * x
    model += (x <= 1.0).named("cap")
    model += (x >= 2.0).named("floor")

    val solution = model.solve()
    assert(solution.status == LpStatus.Infeasible)
    assert(solution.objectiveValue.isNaN)
    assert(solution.iterations > 0)

    // slack diagnostics on the last iterate locate the conflict: the two rows cannot both hold,
    // so their slacks sum to -1 and at least one is materially negative
    val slacks = solution.constraints.select("name", "slack").collect()
      .map(r => r.getString(0) -> r.getDouble(1)).toMap
    assert(slacks.keySet == Set("cap", "floor"))
    assert(math.abs(slacks("cap") + slacks("floor") + 1.0) < 1e-6)
    assert(slacks.values.min < -0.4)
  }

  test("Unbounded: dual certificate plus a primal-feasible iterate maps to +Infinity for Maximize") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("unbounded", Maximize)
    val x = model.variable("x")
    model += 1.0 * x
    model += (x >= 1.0).named("floor")

    val solution = model.solve(SolveConfig(tolerance = 1e-4))
    assert(solution.status == LpStatus.Unbounded)
    assert(solution.objectiveValue == Double.PositiveInfinity)
    assert(!solution.residuals.primal.isNaN)
  }

  test("Unbounded: signed infinity respects the objective sense (-Infinity for Minimize)") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("unbounded_min", Minimize)
    val x = model.variable("x")
    model += -1.0 * x
    model += (x >= 1.0).named("floor")

    val solution = model.solve(SolveConfig(tolerance = 1e-4))
    assert(solution.status == LpStatus.Unbounded)
    assert(solution.objectiveValue == Double.NegativeInfinity)
  }

  test("InfeasibleOrUnbounded: dual certificate without a primal-feasible point within tolerance") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("unbounded_strict", Maximize)
    val x = model.variable("x")
    model += 1.0 * x
    model += (x >= 1.0).named("floor")

    // at the default tolerance the last iterate does not qualify as primal-feasible, so the
    // dual-infeasibility certificate alone cannot distinguish infeasible from unbounded
    val solution = model.solve()
    assert(solution.status == LpStatus.InfeasibleOrUnbounded)
    assert(solution.objectiveValue.isNaN)
  }

  test("an infeasible instance that previously threw LpNumericalException is reported as Infeasible") {
    implicit val ss: SparkSession = spark
    // without certificate detection this instance degenerates the Cholesky step and dies with
    // LpNumericalException; the certificate tests reclassify it truthfully
    val model = LpProblem("reclassified", Maximize)
    val w = model.variable("w")
    val z = model.variable("z")
    model += w + z
    model += (w + z === -1.0).named("impossible")
    model += (w === 5.0).named("pin")

    val solution = model.solve()
    assert(solution.status == LpStatus.Infeasible)
    assert(solution.objectiveValue.isNaN)

    // the violated row is visible in the diagnostics
    val slacks = solution.constraints.select("name", "slack").collect()
      .map(r => r.getString(0) -> r.getDouble(1)).toMap
    assert(slacks("impossible") < -0.4)
  }
}
