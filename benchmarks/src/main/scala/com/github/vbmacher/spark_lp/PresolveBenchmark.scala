package com.github.vbmacher.spark_lp

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.dsl.implicits._
import org.apache.spark.sql.SparkSession

/** End-to-end solve timing; independent original-model validation is outside the timed interval. */
object PresolveBenchmark {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Usage: PresolveBenchmark new-output-directory")
    val output = Paths.get(args(0))
    require(!Files.exists(output), "Benchmark output must be a new directory")
    Files.createDirectories(output)
    implicit val spark: SparkSession = SparkSession.builder().master("local[4]").appName("presolve-benchmark")
      .config("spark.ui.enabled", "false").config("spark.sql.shuffle.partitions", "4")
      .config("spark.default.parallelism", "4").getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")
    val writer = Files.newBufferedWriter(output.resolve("records.csv"), StandardCharsets.UTF_8)
    val environment = s"Spark=${spark.version}\nScala=${util.Properties.versionNumberString}\nJava=${System.getProperty("java.version")}\n" +
      s"OS=${System.getProperty("os.name")} ${System.getProperty("os.arch")}\nCPUs=${Runtime.getRuntime.availableProcessors()}\n" +
      "master=local[4]\npartitions=4\ntolerance=1e-8\nvalidationTolerance=1e-6\nwarmups=1\nrepetitions=3\nvariablesPerBlock=12\n"
    Files.write(output.resolve("environment.txt"), environment.getBytes(StandardCharsets.UTF_8))
    writer.write("case,mode,repetition,warmup,seconds,status,objective,expected,valid,iterations,solver_columns,solver_rows,fixed,bound_changes,substitutions\n")
    var failures = 0
    try {
      spark.range(1).count()
      for (kind <- Vector("fixed_rows", "singleton_columns", "irreducible"); repetition <- 0 to 3;
           mode <- (if (repetition % 2 == 0) Vector("off", "full") else Vector("full", "off"))) {
        val n = 12
        val model = LpProblem(kind)
        val xs = Vector.tabulate(n)(i => model.variable(s"x$i", upperBound = Some(if (kind == "fixed_rows") 10.0 else 1.0)))
        var variables = xs
        val expected = kind match {
          case "fixed_rows" =>
            xs.zipWithIndex.foreach { case (x, i) => model += (x === (i % 3).toDouble).named(s"fix$i") }
            model += lpDot(Vector.fill(n)(1.0), xs) + 7.0
            7.0 + (0 until n).map(_ % 3).sum
          case "singleton_columns" =>
            val ys = Vector.tabulate(n)(i => model.variable(s"free$i", Double.NegativeInfinity))
            variables = xs ++ ys
            xs.zip(ys).zipWithIndex.foreach { case ((x, y), i) => model += (y + 2.0 * x === (i + 3.0)).named(s"definition$i") }
            model += lpDot(Vector.fill(n)(1.0), xs) + 7.0
            7.0
          case _ =>
            model += lpDot((1 to n).map(_.toDouble), xs) + 7.0
            model += (lpDot(Vector.fill(n)(1.0), xs) === (n / 2.0)).named("total")
            7.0 + (1 to n / 2).sum
        }
        val config = SolveConfig(presolve = PresolveConfig(enabled = mode != "off", effort = PresolveEffort.Full))
        val start = System.nanoTime()
        val result = model.solve(config)
        val seconds = (System.nanoTime() - start).toDouble / 1e9
        try {
          val report = model.validateCandidate(model.candidateValues(variables.map(v => v -> result.value(v))),
            CandidateValidationConfig(tolerance = 1e-6))
          val valid = try report.feasible && result.status == LpStatus.Optimal && math.abs(result.objectiveValue - expected) <= 1e-6
            finally report.close()
          if (!valid) failures += 1
          val p = result.presolve.get
          writer.write(s"$kind,$mode,$repetition,${repetition == 0},$seconds,${result.status},${result.objectiveValue},$expected,$valid,${result.iterations},${p.solverColumns},${p.solverRows},${p.fixedVariables},${p.bounds.size},${p.substitutions.size}\n")
          writer.flush()
        } finally result.close()
      }
      require(failures == 0, s"$failures benchmark results failed independent validation")
    } finally { writer.close(); spark.stop() }
  }
}
