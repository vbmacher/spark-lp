package com.github.vbmacher.spark_lp

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.dsl.implicits._
import org.apache.spark.sql.SparkSession

object StartBenchmark {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Usage: StartBenchmark new-absolute-output-directory")
    val output = Paths.get(args(0))
    require(output.isAbsolute && !Files.exists(output), "Output must be a new absolute directory")
    Files.createDirectories(output)
    implicit val spark: SparkSession = SparkSession.builder().master("local[4]").appName("start-benchmark")
      .config("spark.ui.enabled", "false").config("spark.sql.shuffle.partitions", "4")
      .config("spark.default.parallelism", "4").getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")
    val writer = Files.newBufferedWriter(output.resolve("records.csv"), StandardCharsets.UTF_8)
    val environment = s"Spark=${spark.version}\nScala=${util.Properties.versionNumberString}\nJava=${System.getProperty("java.version")}\n" +
      s"OS=${System.getProperty("os.name")} ${System.getProperty("os.arch")}\nCPUs=${Runtime.getRuntime.availableProcessors()}\n" +
      "master=local[4]\npartitions=4\ntolerance=1e-8\nvalidationTolerance=1e-6\nwarmups=1\nrepetitions=3\ninteriorFloor=0.001\n"
    Files.write(output.resolve("environment.txt"), environment.getBytes(StandardCharsets.UTF_8))
    writer.write("case,mode,repetition,warmup,seconds,snapshot_seconds,validation_preparation_seconds,status,objective,expected,valid,iterations,nodes,start_used,seeded_incumbent\n")
    var failures = 0
    try {
      spark.range(1).count()
      for (kind <- Vector("lp_coordinates", "mip_knapsack"); repetition <- 0 to 3;
           mode <- (if (repetition % 2 == 0) Vector("cold", "started") else Vector("started", "cold"))) {
        val model = LpProblem(kind, Maximize)
        val (assignments, expected, config) = if (kind == "lp_coordinates") {
          val xs = Vector.tabulate(12)(i => model.variable(s"x$i", -2.0, Some(3.0)))
          val ys = Vector.tabulate(12)(i => model.variable(s"upper$i", Double.NegativeInfinity, Some(2.0)))
          val zs = Vector.tabulate(12)(i => model.variable(s"free$i", Double.NegativeInfinity))
          xs.zip(ys).foreach { case (x, y) => model += (x + y <= 3.0) }
          zs.foreach(z => model += (z === -1.0))
          model += lpDot(Vector.fill(12)(1.0), xs) + lpDot(Vector.fill(12)(1.0), ys) + 7.0
          (xs.map(_ -> 1.0) ++ ys.map(_ -> 2.0) ++ zs.map(_ -> -1.0), 43.0,
            SolveConfig(newtonSolver = NewtonSolver.ConjugateGradient))
        } else {
          val xs = Vector.tabulate(6)(i => model.variable(s"item$i", category = Binary))
          val weights = Vector(2.0, 3.0, 4.0, 5.0, 7.0, 9.0)
          val profits = Vector(4.0, 5.0, 7.0, 8.0, 11.0, 13.0)
          model += lpDot(profits, xs) + 7.0
          model += (lpDot(weights, xs) <= 12.0)
          val optimum = (0 until (1 << xs.size)).map { mask =>
            val bits = xs.indices.map(i => if ((mask & (1 << i)) != 0) 1.0 else 0.0)
            (weights.zip(bits).map { case (a, b) => a * b }.sum, profits.zip(bits).map { case (a, b) => a * b }.sum, bits)
          }.filter(_._1 <= 12.0).maxBy(_._2)
          (xs.zip(optimum._3), optimum._2 + 7.0, SolveConfig())
        }
        val begin = System.nanoTime()
        val start = if (mode == "started") Some(model.start(assignments)) else None
        val snapshot = (System.nanoTime() - begin).toDouble / 1e9
        val result = model.solve(config.copy(start = start))
        val seconds = (System.nanoTime() - begin).toDouble / 1e9
        try {
          val report = model.validateCandidate(model.candidateValues(assignments.map { case (v, _) => v -> result.value(v) }),
            CandidateValidationConfig(tolerance = 1e-6))
          val valid = try report.feasible && result.status == LpStatus.Optimal && math.abs(result.objectiveValue - expected) <= 1e-6
            finally report.close()
          if (!valid) failures += 1
          val prepared = result.start.map(_.preparationSeconds).getOrElse(0.0)
          writer.write(s"$kind,$mode,$repetition,${repetition == 0},$seconds,$snapshot,$prepared,${result.status},${result.objectiveValue},$expected,$valid,${result.iterations},${result.mip.map(_.processedNodes).getOrElse(0)},${result.start.exists(_.used)},${result.start.exists(_.seededIncumbent)}\n")
          writer.flush()
        } finally { result.close(); start.foreach(_.close()) }
      }
      require(failures == 0, s"$failures results failed independent validation")
    } finally { writer.close(); spark.stop() }
  }
}
