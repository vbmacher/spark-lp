package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.dsl.implicits._
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.SparkSession
import org.scalatest.funsuite.AnyFunSuite

class LpCandidateValidationSuite extends AnyFunSuite with DataFrameSuiteBase {
  test("independent candidates measure original constraints, bounds, integrality and objective") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("candidate", Maximize)
    val x = model.variable("x", upperBound = Some(4.0), category = Integer)
    val y = model.variable("y", lowerBound = -2.0)
    model += 2.0 * x + y + 5.0
    model += (x + y <= 3.0).named("limit")
    for ((xv, yv, expected) <- Seq((2.0, 1.0, true), (2.5, 0.0, false), (5.0, -2.0, false), (2.0, 2.0, false))) {
      val report = model.validateCandidate(model.candidateValues(Seq(x -> xv, y -> yv)))
      try {
        assert(report.feasible == expected)
        assert(report.objectiveValue.contains(2.0 * xv + yv + 5.0))
        assert(report.sense == Maximize)
      } finally report.close()
    }
    val relaxed = model.validateCandidate(model.candidateValues(Seq(x -> 2.5, y -> 0.0)),
      CandidateValidationConfig(relaxIntegrality = true))
    try assert(relaxed.feasible && relaxed.relaxationOnly) finally relaxed.close()
    val near = model.validateCandidate(model.candidateValues(Seq(x -> 2.0000001, y -> 0.0)))
    try assert(near.feasible) finally near.close()
    intercept[LpModelException](model.candidateValues(Seq(LpProblem("foreign").variable("z") -> 0.0)))
  }

  test("malformed distributed keyed assignments are rejected without inventing an objective") {
    implicit val ss: SparkSession = spark
    val localSpark = spark
    import localSpark.implicits._
    val model = LpProblem("keys")
    val family = model.variables("x", Seq("a", "b").toDF("key"), $"key")
    model += family.sum
    val good = model.candidateValues(Seq(family("a") -> 1.0, family("b") -> 2.0)).cache()
    val malformed = Seq(good.filter(_.value == 1.0), good.union(good),
      good.map(v => v.copy(value = Double.NaN)), good.union(spark.sparkContext.parallelize(Seq(
        LpCandidateValue(LpVariableId(99, "foreign"), 0.0)))))
    malformed.foreach { candidate =>
      val report = model.validateCandidate(candidate)
      try assert(!report.feasible && report.objectiveValue.isEmpty && report.maxViolation.isPosInfinity)
      finally report.close()
    }
    val report = model.validateCandidate(good)
    try assert(report.feasible && report.objectiveValue.contains(3.0)) finally report.close()
    assert(good.getStorageLevel.useMemory && good.count() == 2)
    good.unpersist()
    assert(spark.range(2).count() == 2)
  }

  test("quadratic candidate objectives retain curvature and sparse factor constants") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("qp")
    val x = model.variable("x")
    model += QpObjective.separable(2.0 * x, x + 3.0) + QpObjective.squared(x - 1.0, 2.0)
    val report = model.validateCandidate(model.candidateValues(Seq(x -> 2.0)))
    try assert(report.feasible && report.objectiveValue.contains(11.0)) finally report.close()
  }
}
