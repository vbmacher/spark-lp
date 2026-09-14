package com.github.vbmacher.spark_lp.examples

import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.dsl.implicits._
import org.apache.spark.sql.SparkSession

/** Finds the cheapest nutritionally acceptable recipe for a 100-gram can of Whiskas cat food.
  *
  * Choose a nonnegative, continuous number of grams of chicken, beef, mutton, rice, wheat,
  * and gel. Ingredients have the following cost per gram and grams of each nutrient per
  * gram of ingredient:
  * {{{
  * Ingredient  Cost ($/g)  Protein  Fat    Fibre  Salt
  * chicken     0.013       0.100    0.080  0.001  0.002
  * beef        0.008       0.200    0.100  0.005  0.005
  * mutton      0.010       0.150    0.110  0.003  0.007
  * rice        0.002       0.000    0.010  0.100  0.002
  * wheat       0.005       0.040    0.010  0.150  0.008
  * gel         0.001       0.000    0.000  0.000  0.000
  * }}}
  *
  * The ingredients must total exactly 100 grams. Each can must contain at least 8 grams
  * of protein and 6 grams of fat, at most 2 grams of fibre, and at most 0.4 grams of salt.
  * Minimize the sum of each ingredient's amount times its cost. There are no additional
  * ingredient availability limits.
  *
  * The optimum uses 60 grams of beef and 40 grams of gel, with all other amounts zero,
  * costing USD 0.52 per can. The example prints ingredient amounts and constraint activities
  * and slacks. It demonstrates weighted sums over a Spark ingredient table in the DSL.
  *
  * Source: [[https://coin-or.github.io/pulp/CaseStudies/a_blending_problem.html PuLP blending case study]].
  */
object ExampleWhiskas extends App {

  implicit val spark: SparkSession = SparkSession.builder()
    .appName("ExampleWhiskas")
    .master("local[2]")
    .config("spark.sql.shuffle.partitions", "2")
    .getOrCreate()

  import spark.implicits._

  try {
    spark.sparkContext.setLogLevel("ERROR")

    // ingredient nutrition data owned by Spark, exactly as it would arrive from a real table
    val ingredients = Seq(
      ("chicken", 0.013, 0.100, 0.080, 0.001, 0.002),
      ("beef", 0.008, 0.200, 0.100, 0.005, 0.005),
      ("mutton", 0.010, 0.150, 0.110, 0.003, 0.007),
      ("rice", 0.002, 0.000, 0.010, 0.100, 0.002),
      ("wheat", 0.005, 0.040, 0.010, 0.150, 0.008),
      ("gel", 0.001, 0.000, 0.000, 0.000, 0.000)
    ).toDF("ingredient", "cost", "protein", "fat", "fibre", "salt")

    val model = LpProblem("Whiskas", Minimize)
    val amount = model.variables("amount", domain = ingredients, key = $"ingredient")

    model += amount.sum($"cost")
    model += (amount.sum === 100.0).named("total_weight")
    model += (amount.sum($"protein") >= 8.0).named("protein_min")
    model += (amount.sum($"fat") >= 6.0).named("fat_min")
    model += (amount.sum($"fibre") <= 2.0).named("fibre_max")
    model += (amount.sum($"salt") <= 0.4).named("salt_max")

    val solution = model.solve()
    try {
      require(solution.status == LpStatus.Optimal, s"unexpected status: ${solution.status}")

      // an interior-point method returns values like 33.999999999; round at the point of use
      println(f"Optimal cost: ${solution.objectiveValue}%.4f (expected 0.5200)")
      solution.values(amount)
        .select("ingredient", "lp_variable", "lp_value")
        .orderBy("ingredient")
        .show()

      solution.constraints
        .select("name", "activity", "sense", "rhs", "slack")
        .show()
    } finally solution.close()
  } finally spark.stop()
}
