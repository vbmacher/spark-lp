package com.github.vbmacher.spark_lp

import java.io.{File, PrintWriter}
import com.github.vbmacher.spark_lp.support.{CaseInventory, DataGenerator, JvmSampler, RuntimeEnvironment, SolveMeasurements, Stopwatch}
import com.github.vbmacher.spark_lp.dsl.LpNumericalException
import org.apache.spark.mllib.linalg.{DenseVector, Vector => SparkVector}
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.json4s.{DefaultFormats, Extraction}
import org.json4s.jackson.JsonMethods.{compact, render}

/** Runs an algorithm Benchmark against a CSV case through shared distributed support.
  * Main arguments: cholesky|cg, existing absolute output directory, cases.csv,
  * case ID, partitions, measured repetitions (default 5), warmups (0 or 1, default 1).
  * A library caller can pass a Benchmark and RunConfig directly. Failed attempts
  * are retained; preparation and independent validation are excluded from solve
  * timing. The driver watchdog limits each solve to 30 minutes.
  */
object BenchmarkRunner {
  final case class RunConfig(output: File, inventory: String, caseId: String, partitions: Int,
                             repetitions: Int = 5, warmups: Int = 1) {
    require(output.isAbsolute && output.isDirectory, "Output must be an existing absolute directory")
    require(partitions > 0 && repetitions > 0 && Set(0, 1).contains(warmups))
  }

  def main(args: Array[String]): Unit = {
    require(args.length >= 5 && args.length <= 7,
      "cholesky|cg output-directory cases.csv case-id partitions [repetitions] [warmups]")
    run(Benchmark.named(args(0)), RunConfig(new File(args(1)), args(2), args(3), args(4).toInt,
      args.lift(5).map(_.toInt).getOrElse(5), args.lift(6).map(_.toInt).getOrElse(1)))
  }

  def run(benchmark: Benchmark, config: RunConfig): Unit = {
    val output = config.output
    val spec = CaseInventory.read(config.inventory).find(_.id == config.caseId).getOrElse(sys.error("Unknown case"))
    val campaign = new File(config.inventory).getName.stripSuffix(".csv")
    val partitions = config.partitions
    val repetitions = config.repetitions
    val warmups = config.warmups
    implicit val formats: DefaultFormats.type = DefaultFormats
    val recordsFile = new File(output, "records.jsonl")
    require(!recordsFile.exists(), "Refusing to overwrite benchmark records")
    val writer = new PrintWriter(recordsFile)
    def write(record: Map[String, Any]): Unit = writer.synchronized {
      writer.println(compact(render(Extraction.decompose(record)))); writer.flush()
    }
    val eventDirectory = new File(output, "events")
    eventDirectory.mkdirs()
    val sparkConf = new SparkConf()
    if (!sparkConf.contains("spark.master")) sparkConf.setMaster("local[4]")
    if (!sparkConf.contains("spark.sql.shuffle.partitions")) sparkConf.set("spark.sql.shuffle.partitions", partitions.toString)
    implicit val spark: SparkSession = SparkSession.builder().config(sparkConf)
      .appName(s"lp-benchmark-${spec.id}-${benchmark.name}")
      .config("spark.ui.enabled", "false")
      .config("spark.eventLog.enabled", "true")
      .config("spark.eventLog.compress", "false")
      .config("spark.eventLog.dir", sparkConf.getOption("spark.eventLog.dir").getOrElse(eventDirectory.toURI.toString))
      .config("spark.dynamicAllocation.enabled", "false")
      .config("spark.speculation", "false").getOrCreate()
    val sc = spark.sparkContext
    sc.setLogLevel("ERROR")
    sc.setCheckpointDir(sparkConf.getOption("spark.checkpoint.dir").getOrElse {
      require(sc.isLocal, "Set spark.checkpoint.dir to a distributed filesystem for cluster benchmarks")
      new File(output, "checkpoints").toURI.toString
    })
    val generationClock = new Stopwatch
    val data = DataGenerator.generate(spec, partitions)
    val fingerprint = data.hash
    val nonzeros = data.nnz
    val objective = data.objective
    val (minCoefficient, maxCoefficient, maxRowNnz) = data.coefficientStats
    val generationSeconds = generationClock.seconds
    val base: Map[String, Any] = Map("schema" -> 1, "campaign" -> campaign, "suite" -> benchmark.suite,
      "warmups" -> warmups, "repetitions" -> repetitions, "case" -> spec.id, "seed" -> spec.seed,
      "hash" -> fingerprint, "backend" -> benchmark.name, "m" -> spec.m, "n" -> spec.n,
      "nnz" -> nonzeros, "tolerance" -> spec.tolerance, "heap_gib" -> spec.heapGiB,
      "partitions" -> partitions, "family" -> spec.family, "nonzeros_per_row" -> spec.width, "generation_seconds" -> generationSeconds,
      "known_objective" -> objective, "implementation_sha" -> sys.props.getOrElse("benchmark.sha", "unrecorded"),
      "source_hash" -> sys.props.getOrElse("benchmark.sourceHash", "unrecorded"),
      "outer_limit" -> 100, "cg_tolerance" -> 1e-10, "cg_limit_per_rank" -> 1000,
      "primal_regularization" -> (if (benchmark.name == "cg") 1e-8 else 0.0),
      "dual_regularization" -> (if (benchmark.name == "cg") 1e-8 else 0.0),
      "preconditioner_memory_bytes" -> (256L * 1024 * 1024))
    val environment = new PrintWriter(new File(output, "environment.json"))
    try environment.println(compact(render(Extraction.decompose(base ++ RuntimeEnvironment.describe(spark) ++ Map(
      "min_coefficient" -> minCoefficient, "max_coefficient" -> maxCoefficient,
      "max_row_nnz" -> maxRowNnz, "generated_bound_rows" -> 0, "generated_slack_rows" -> 0)))))
    finally environment.close()
    val sampler = new JvmSampler
    val fixtureCaches = sc.getPersistentRDDs.keySet
    try { ((if (warmups == 1) 0 else 1) to repetitions).foreach { repetition =>
      val group = s"${spec.id}-${benchmark.name}-$repetition"
      val identity = base ++ Map("repetition" -> repetition, "warmup" -> (repetition == 0), "job_group" -> group)
      val preparationClock = new Stopwatch
      val input = data.columns(partitions).cache()
      val rows = input.map(v => v._3: SparkVector).cache()
      val costs = input.mapPartitions(it => Iterator(new DenseVector(it.map(_._2).toArray))).cache()
      val rhs = data.b
      rows.count(); costs.count()
      val preparation = preparationClock.seconds
      sc.setJobGroup(group, group, interruptOnCancel = true)
      sampler.reset()
      val metrics = new SolveMeasurements
      val timeout = sampler.watchdog {
        write(identity ++ Map("status" -> "Timeout", "solve_seconds" -> 1800.0))
      }
      var residuals = Map.empty[String, Double]
      try {
        val result = benchmark.solve(costs, rows, rhs, spec.tolerance, metrics.progress, (x, y, s) => {
            metrics.validate {
              sc.setJobGroup(group + "-validation", "independent validation")
              try residuals = data.residuals(input.map(_._1), x, y, s)
              finally sc.setJobGroup(group, group)
            }
          })
        val elapsed = metrics.solveSeconds
        try {
          val status = if (result.termination == LP.Termination.Converged) {
            if (DataGenerator.passes(residuals, spec.tolerance)) "Success" else "AccuracyFailure"
          } else result.termination.toString
          write(identity ++ metrics.snapshot(elapsed) ++ sampler.snapshot ++ Map(
            "status" -> status, "residuals" -> residuals, "preparation_seconds" -> preparation,
            "outer_iterations" -> result.iterations, "cg_steps" -> result.innerIterations,
            "cg_restarts" -> result.innerRestarts,
            "maximum_rank" -> result.preconditionerRank))
          println(s"BENCHMARK $group $status ${elapsed}s")
        } finally result.x.unpersist(blocking = true)
      } catch {
        case error: LpNumericalException => write(identity ++ Map("status" -> "NumericalFailure",
          "error" -> error.getMessage, "outer_iterations" -> error.completedIterations,
          "solve_seconds" -> metrics.solveSeconds))
      } finally {
        timeout.close()
        sc.clearJobGroup()
        // Keep immutable distributed fixture caches across repetitions; release solve work only.
        sc.getPersistentRDDs.values.filterNot(rdd => fixtureCaches.contains(rdd.id)).foreach(_.unpersist(blocking = true))
      }
    } } finally {
      sampler.close(); data.close(); writer.close(); spark.stop()
    }
  }
}
