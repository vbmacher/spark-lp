package com.github.vbmacher.spark_lp.examples

import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.dsl.implicits._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.struct

/** Finds the cheapest way to deliver whole crates of beer from two warehouses to five bars.
  *
  * Warehouse A has 1,000 crates available and warehouse B has 4,000. Each warehouse can
  * ship directly to every bar. Bar demands and transport costs per crate are:
  * {{{
  * Bar                  1    2     3    4    5
  * Minimum demand     500  900  1800  200  700
  * Cost from A          2    4     5    2    1
  * Cost from B          3    1     3    2    3
  * }}}
  *
  * Choose one nonnegative integer shipment for each warehouse/bar pair. Total shipments
  * from a warehouse cannot exceed its supply; total shipments to a bar must meet or
  * exceed its demand. Each route also has a valid upper bound of 4,000 crates. Unused
  * supply is allowed and incurs no cost. Minimize the sum of shipment quantities times
  * their route costs, with no fixed delivery charges.
  *
  * The minimum cost is 8,600. One optimal plan sends 300 crates from A to bar 1 and
  * 700 from A to bar 5; B supplies the remaining 200 to bar 1, 900 to bar 2, 1,800 to
  * bar 3, and 200 to bar 4. The example prints shipments and constraint slacks. Composite
  * variable keys and grouped DSL constraints express the transport network.
  *
  * Source: [[https://coin-or.github.io/pulp/CaseStudies/a_transportation_problem.html PuLP transportation case study]].
  */
object ExampleTransportation extends App {
  implicit val spark: SparkSession = SparkSession.builder()
    .appName("ExampleTransportation")
    .master("local[2]")
    .config("spark.sql.shuffle.partitions", "2")
    .getOrCreate()
  spark.sparkContext.setCheckpointDir(java.nio.file.Files.createTempDirectory("spark-lp-checkpoints").toString)
  import spark.implicits._

  try {
    spark.sparkContext.setLogLevel("ERROR")
    val supply = Seq(("A", 1000.0), ("B", 4000.0)).toDF("warehouse", "rhs")
    val demand = Seq(
      ("1", 500.0), ("2", 900.0), ("3", 1800.0), ("4", 200.0), ("5", 700.0)
    ).toDF("bar", "rhs")
    val routes = Seq(
      ("A", "1", 2.0), ("A", "2", 4.0), ("A", "3", 5.0), ("A", "4", 2.0), ("A", "5", 1.0),
      ("B", "1", 3.0), ("B", "2", 1.0), ("B", "3", 3.0), ("B", "4", 2.0), ("B", "5", 3.0)
    ).toDF("warehouse", "bar", "cost")

    val model = LpProblem("Beer distribution", Minimize)
    // No route can exceed the largest warehouse supply. Integer variables need finite bounds.
    val shipment = model.variables("shipment", routes, key = struct($"warehouse", $"bar"),
      upperBound = Some(4000.0), category = Integer)
    model += shipment.sum($"cost")
    model += (shipment.sumBy("warehouse")() <= supply).named("supply")
    model += (shipment.sumBy("bar")() >= demand).named("demand")

    val solution = model.solve()
    try {
      require(solution.status == LpStatus.Optimal, s"Unexpected status: ${solution.status}")
      println(f"Optimal transport cost: ${solution.objectiveValue}%.2f (expected 8600.00)")
      solution.values(shipment).select("warehouse", "bar", "lp_value")
        .orderBy("warehouse", "bar").show()
      solution.constraints.select("name", "activity", "sense", "rhs", "slack").show()
    } finally solution.close()
  } finally spark.stop()
}
