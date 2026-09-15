package com.github.vbmacher.spark_lp.examples

import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.dsl.implicits._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{struct, when}

/** Maximizes Gemstone Tools' expected profit when steel must be bought before demand
  * earnings and available assembly capacity are known.
  *
  * The first decision is a nonnegative, continuous steel purchase at USD 58 per unit.
  * Once a scenario is known, choose nonnegative, continuous quantities of wrenches and
  * pliers to produce. Their resource requirements per product unit and production caps are:
  * {{{
  * Product   Steel  Molding  Assembly  Maximum production per scenario
  * wrenches  1.5    1.0      0.3       15
  * pliers    1.0    1.0      0.5       16
  * }}}
  * Four mutually exclusive scenarios each have probability 0.25:
  * {{{
  * Scenario  Wrench earnings ($/unit)  Plier earnings ($/unit)  Assembly capacity
  * 0         160                       100                      8
  * 1         160                       100                     10
  * 2          90                       100                      8
  * 3          90                       100                     10
  * }}}
  *
  * In every scenario, steel consumption cannot exceed the same first-stage purchase,
  * molding consumption cannot exceed 21, assembly consumption cannot exceed that
  * scenario's capacity, and each product must respect its production cap. The scenarios
  * are alternative outcomes, so their resource consumption is not added together.
  * Steel has no separate purchase cap, and unused steel earns no salvage revenue.
  *
  * Maximize probability-weighted production earnings minus the steel purchase cost,
  * charged once. The optimal purchase is 27.25 units of steel and the expected profit
  * is USD 863.25. The example prints this shared decision and the four contingent production
  * plans, demonstrating how scalar and grouped DSL expressions couple scenario models.
  *
  * Source: [[https://coin-or.github.io/pulp/CaseStudies/a_two_stage_production_planning_problem.html PuLP two-stage production case study]].
  */
object ExampleTwoStageProduction extends App {
  implicit val spark: SparkSession = SparkSession.builder()
    .appName("ExampleTwoStageProduction")
    .master("local[2]")
    .config("spark.sql.shuffle.partitions", "2")
    .getOrCreate()
  spark.sparkContext.setCheckpointDir(java.nio.file.Files.createTempDirectory("spark-lp-checkpoints").toString)
  import spark.implicits._

  try {
    spark.sparkContext.setLogLevel("ERROR")
    val products = Seq(
      ("wrenches", 1.5, 1.0, 0.3, 15.0),
      ("pliers",   1.0, 1.0, 0.5, 16.0)
    ).toDF("product", "steel", "molding", "assembly", "capacity")
    val scenarios = Seq(
      (0, 0.25, 160.0, 100.0, 8.0),
      (1, 0.25, 160.0, 100.0, 10.0),
      (2, 0.25, 90.0, 100.0, 8.0),
      (3, 0.25, 90.0, 100.0, 10.0)
    ).toDF("scenario", "probability", "wrench_earnings", "plier_earnings", "assembly_capacity")
    val plans = scenarios.crossJoin(products)
      .withColumn("earnings", when($"product" === "wrenches", $"wrench_earnings")
        .otherwise($"plier_earnings"))

    val model = LpProblem("Gemstone Tools", Maximize)
    val steelPurchase = model.variable("steel_purchase")
    val production = model.variables("production", plans, key = struct($"scenario", $"product"))

    // Pay for steel once; revenue is the probability-weighted outcome of the four scenarios.
    model += production.sum($"probability" * $"earnings") - 58.0 * steelPurchase
    for (scenario <- 0 until 4) {
      model += (production.sum(when($"scenario" === scenario, $"steel").otherwise(0.0)) <= steelPurchase)
        .named(s"steel_capacity_$scenario")
    }
    model += (production.sumBy("scenario")($"molding") <= 21.0).named("molding_capacity")
    model += (production.sumBy("scenario")($"assembly") <=
      scenarios.select($"scenario", $"assembly_capacity".as("rhs"))).named("assembly_capacity")
    model += (production.sumBy("scenario", "product")() <=
      plans.select($"scenario", $"product", $"capacity".as("rhs"))).named("product_capacity")

    val solution = model.solve()
    try {
      require(solution.status == LpStatus.Optimal, s"Unexpected status: ${solution.status}")
      println(f"Expected profit: ${solution.objectiveValue}%.2f (expected 863.25)")
      println(f"Steel purchase: ${solution.value(steelPurchase)}%.2f (expected 27.25)")
      solution.values(production).select("scenario", "product", "lp_value")
        .orderBy("scenario", "product").show()
      solution.constraints.select("name", "activity", "sense", "rhs", "slack").show(24, truncate = false)
    } finally solution.close()
  } finally spark.stop()
}
