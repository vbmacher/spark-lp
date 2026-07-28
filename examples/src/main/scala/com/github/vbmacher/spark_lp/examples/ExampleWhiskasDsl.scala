package com.github.vbmacher.spark_lp.examples

import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.dsl.implicits._
import org.apache.spark.sql.SparkSession

/**
  * The Whiskas blending problem from PuLP
  * (https://coin-or.github.io/pulp/CaseStudies/a_blending_problem.html), expressed in the
  * DataFrame modelling DSL. Compare with [[ExampleWhiskas]], which builds the same model by
  * hand-crafting the equality-form matrix and slack variables.
  *
  * The DSL compiler introduces the slack variables, validates the model, and reports a truthful
  * status, so none of the manual standard-form preparation is needed here.
  */
object ExampleWhiskasDsl extends App {

  implicit val spark: SparkSession = SparkSession.builder()
    .appName("ExampleWhiskasDsl")
    .master("local[*]")
    .getOrCreate()

  import spark.implicits._

  spark.sparkContext.setLogLevel("ERROR")

  // ingredient nutrition data owned by Spark, exactly as it would arrive from a real table
  val ingredients = Seq(
    ("chicken", 0.013, 0.100, 0.080, 0.001, 0.002),
    ("beef", 0.008, 0.200, 0.100, 0.005, 0.005)
  ).toDF("ingredient", "cost", "protein", "fat", "fibre", "salt")

  val model = LpProblem("Whiskas", Minimize)
  val amount = model.variables("amount", domain = ingredients, key = $"ingredient")

  model += lpSum(amount * $"cost")
  model += (lpSum(amount) === 100.0).named("total_weight")
  model += (lpSum(amount * $"protein") >= 8.0).named("protein_min")
  model += (lpSum(amount * $"fat") >= 6.0).named("fat_min")
  model += (lpSum(amount * $"fibre") <= 2.0).named("fibre_max")
  model += (lpSum(amount * $"salt") <= 0.4).named("salt_max")

  val solution = model.solve()
  require(solution.status == LpStatus.Optimal, s"unexpected status: ${solution.status}")

  // an interior-point method returns values like 33.999999999; round at the point of use
  println(f"Optimal cost: ${solution.objectiveValue}%.4f (expected 0.9667)")
  solution.values(amount)
    .select("ingredient", "lp_variable", "lp_value")
    .orderBy("ingredient")
    .show()

  solution.constraints
    .select("name", "activity", "sense", "rhs", "slack")
    .show()

  spark.stop()
}
