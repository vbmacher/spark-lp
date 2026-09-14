package com.github.vbmacher.spark_lp.examples

import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.dsl.implicits._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{explode, lit}

/** Partitions 17 wedding guests into at most five tables, with one to four guests per table.
  *
  * The guests are A, B, C, D, E, F, G, I, J, K, L, M, N, O, P, Q, and R. Every guest
  * must sit at exactly one table. Any subset of up to four guests is an allowed table;
  * there are no additional restrictions on who may sit together.
  *
  * A table's cost is its alphabetical span: the character-code difference between its
  * last and first guest when sorted. For example, A/B/C/D costs 3, G/I costs 2, and a
  * singleton costs zero. As in PuLP's executable model, minimize the sum of these spans
  * across selected tables, even though the original case study calls this score happiness.
  *
  * Enumerating all subsets of sizes one through four produces 3,213 candidate tables.
  * Each candidate has a binary selection variable. A global constraint selects at most
  * five tables, and a grouped constraint for each guest selects exactly one table that
  * contains that guest. This demonstrates set partitioning using an exploded membership
  * table in the DSL.
  *
  * The optimal total span is 12: tables A/B/C/D, E/F/G, I/J/K/L, M/N/O, and P/Q/R attain
  * it. A table of k distinct letters has span at least k - 1, so seating 17 guests at
  * at most five tables cannot cost less than 17 - 5 = 12.
  *
  * The full model can reach IterationLimit with the current default solver settings.
  * The example always prints the status and displays a seating plan only for Optimal;
  * a fractional LP relaxation must not be interpreted as an integer seating plan.
  *
  * Source: [[https://coin-or.github.io/pulp/CaseStudies/a_set_partitioning_problem.html PuLP set partitioning case study]].
  */
object ExampleSetPartitioning extends App {
  implicit val spark: SparkSession = SparkSession.builder()
    .appName("ExampleSetPartitioning")
    .master("local[2]")
    .config("spark.sql.shuffle.partitions", "2")
    .getOrCreate()
  import spark.implicits._

  try {
    spark.sparkContext.setLogLevel("ERROR")
    val guests = "A B C D E F G I J K L M N O P Q R".split(" ").toVector
    // Enumerate subsets for this fixed guest list; each candidate becomes one binary variable.
    val tables = (1 to 4).flatMap(size => guests.combinations(size).map { members =>
      (members.mkString("_"), members, (members.last.charAt(0) - members.head.charAt(0)).toDouble)
    }).toDF("table", "guests", "span")
    val memberships = tables.select($"table", explode($"guests").as("guest"))

    val model = LpProblem("Wedding seating", Minimize)
    val selected = model.variables("selected", tables, key = $"table", category = Binary)
    model += selected.sum($"span")
    model += (selected.sum <= 5.0).named("table_limit")
    model += (lpSumBy(selected.terms(memberships, Seq($"guest"), lit(1.0)), Seq("guest")) === 1.0)
      .named("seat_each_guest")

    val solution = model.solve()
    try {
      println(s"Seating status: ${solution.status}")
      if (solution.status == LpStatus.Optimal) {
        println(f"Total seating span: ${solution.objectiveValue}%.0f (expected 12)")
        solution.values(selected).filter($"lp_value" > 0.5)
          .select("table", "guests", "span").orderBy("table").show(truncate = false)
      } else {
        // A retained LP relaxation need not be integral; do not display it as a seating plan.
        println("No optimal seating was proved. Inspect the solver status before using any values.")
      }
    } finally solution.close()
  } finally spark.stop()
}
