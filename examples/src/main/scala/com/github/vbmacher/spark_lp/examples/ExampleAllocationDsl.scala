package com.github.vbmacher.spark_lp.examples

// tag::allocation[]
import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.dsl.implicits._
import org.apache.spark.sql.SparkSession

object ExampleAllocationDsl extends App {
  implicit val spark: SparkSession = SparkSession.builder()
    .appName("Allocation").master("local[2]").getOrCreate()
  import spark.implicits._

  try {
    val offers = Seq(("a", "east", 1.0), ("b", "east", 3.0), ("c", "west", 2.0))
      .toDF("id", "region", "cost")
    val demand = Seq(("east", 4.0), ("west", 2.0)).toDF("region", "rhs")

    val model = LpProblem("allocation") // minimize; variables are nonnegative by default
    val amount = model.variables("amount", offers, $"id")
    model += amount.sum($"cost")
    model += (amount.sumBy("region")() === demand).named("demand")

    val solution = model.solve()
    try {
      require(solution.status == LpStatus.Optimal, s"Solve ended with ${solution.status}")
      require(math.abs(solution.objectiveValue - 8.0) < 1e-6)
      solution.values(amount).select("id", "lp_value").orderBy("id").show()
      // a = 4, b = 0, c = 2 (within tolerance); minimum cost = 8
      solution.constraints.select("name", "activity", "rhs", "slack").show()
    } finally solution.close()
  } finally spark.stop()
}
// end::allocation[]
