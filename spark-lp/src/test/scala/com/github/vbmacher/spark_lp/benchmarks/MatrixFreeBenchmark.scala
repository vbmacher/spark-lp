package com.github.vbmacher.spark_lp.benchmarks

import java.io.{File, PrintWriter}
import java.lang.management.ManagementFactory
import java.util.concurrent.{Executors, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicLong
import com.github.vbmacher.spark_lp._
import com.github.vbmacher.spark_lp.dsl.LpNumericalException
import org.apache.spark.mllib.linalg.{DenseVector, Vector => SparkVector}
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.json4s.{DefaultFormats, Extraction}
import org.json4s.jackson.JsonMethods.{compact, render}

/** One case/backend per fresh application. Ordinary ScalaTest discovery never runs this object. */
object MatrixFreeBenchmark {
  def main(args: Array[String]): Unit = {
    require(args.length == 5, "output-directory cases.csv case-id cholesky|cg partitions")
    val output = new File(args(0))
    require(output.isAbsolute && output.isDirectory)
    val spec = Fixtures.read(args(1)).find(_.id == args(2)).getOrElse(sys.error("Unknown case"))
    val backend = args(3) match {
      case "cholesky" => NewtonSolver.Cholesky
      case "cg" => NewtonSolver.ConjugateGradient
      case other => throw new IllegalArgumentException(s"Unknown backend: $other")
    }
    val partitions = args(4).toInt
    require(partitions > 0)
    implicit val formats: DefaultFormats.type = DefaultFormats
    val writer = new PrintWriter(new File(output, "records.jsonl"))
    def write(record: Map[String, Any]): Unit = writer.synchronized {
      writer.println(compact(render(Extraction.decompose(record)))); writer.flush()
    }
    val eventDirectory = new File(output, "events")
    eventDirectory.mkdirs()
    val sparkConf = new SparkConf()
    if (!sparkConf.contains("spark.master")) sparkConf.setMaster("local[4]")
    implicit val spark: SparkSession = SparkSession.builder().config(sparkConf)
      .appName(s"matrix-free-lp-${spec.id}-${args(3)}")
      .config("spark.ui.enabled", "false")
      .config("spark.eventLog.enabled", "true")
      .config("spark.eventLog.compress", "false")
      .config("spark.eventLog.dir", sparkConf.getOption("spark.eventLog.dir").getOrElse(eventDirectory.toURI.toString))
      .config("spark.dynamicAllocation.enabled", "false")
      .config("spark.speculation", "false").getOrCreate()
    val sc = spark.sparkContext
    sc.setLogLevel("ERROR")
    val generatedAt = System.nanoTime()
    val data = Fixtures.generate(spec)
    val generationSeconds = (System.nanoTime() - generatedAt) / 1e9
    val base: Map[String, Any] = Map("schema" -> 1, "case" -> spec.id, "seed" -> spec.seed,
      "hash" -> data.hash, "backend" -> args(3), "m" -> spec.m, "n" -> spec.n,
      "nnz" -> data.nnz, "tolerance" -> spec.tolerance, "heap_gib" -> spec.heapGiB,
      "partitions" -> partitions, "generation_seconds" -> generationSeconds,
      "known_objective" -> data.objective, "implementation_sha" -> sys.props.getOrElse("benchmark.sha", "unrecorded"),
      "source_hash" -> sys.props.getOrElse("benchmark.sourceHash", "unrecorded"),
      "outer_limit" -> 100, "cg_tolerance" -> 1e-10, "cg_limit_per_rank" -> 1000,
      "primal_regularization" -> (if (args(3) == "cg") 1e-8 else 0.0),
      "dual_regularization" -> (if (args(3) == "cg") 1e-8 else 0.0),
      "preconditioner_memory_bytes" -> (256L * 1024 * 1024))
    val environment = new PrintWriter(new File(output, "environment.json"))
    val rowNnz = Array.fill(spec.m)(0)
    data.columns.foreach(_.indices.foreach(i => rowNnz(i) += 1))
    val coefficients = data.columns.iterator.flatMap(_.values.iterator).map(math.abs)
    val minCoefficient = coefficients.min
    val maxCoefficient = data.columns.iterator.flatMap(_.values.iterator).map(math.abs).max
    try environment.println(compact(render(Extraction.decompose(base ++ Map(
      "java" -> System.getProperty("java.runtime.version"), "jvm" -> System.getProperty("java.vm.name"),
      "os" -> System.getProperty("os.name"), "architecture" -> System.getProperty("os.arch"),
      "spark_version" -> spark.version, "scala_version" -> scala.util.Properties.versionNumberString,
      "blas" -> com.github.fommil.netlib.BLAS.getInstance().getClass.getName,
      "lapack" -> com.github.fommil.netlib.LAPACK.getInstance().getClass.getName,
      "jvm_args" -> ManagementFactory.getRuntimeMXBean.getInputArguments.toString,
      "spark_conf" -> sc.getConf.getAll.toMap, "min_coefficient" -> minCoefficient,
      "max_coefficient" -> maxCoefficient, "max_row_nnz" -> rowNnz.max,
      "generated_bound_rows" -> 0, "generated_slack_rows" -> 0))))) finally environment.close()

    val scheduler = Executors.newScheduledThreadPool(2, new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val t = new Thread(r, "benchmark-watchdog"); t.setDaemon(true); t
      }
    })
    val peakHeap = new AtomicLong()
    val peakRss = new AtomicLong()
    def peak(target: AtomicLong, value: Long): Unit = {
      var old = target.get()
      while (value > old && !target.compareAndSet(old, value)) old = target.get()
    }
    def sample(): Unit = {
      peak(peakHeap, ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getUsed)
      val status = new File("/proc/self/status")
      if (status.exists()) {
        val source = scala.io.Source.fromFile(status)
        try source.getLines().find(_.startsWith("VmRSS:")).foreach { line =>
          peak(peakRss, line.trim.split("\\s+")(1).toLong * 1024)
        } finally source.close()
      }
    }
    val sampling = scheduler.scheduleAtFixedRate(new Runnable {
      override def run(): Unit = sample()
    }, 0, 20, TimeUnit.MILLISECONDS)
    try { (0 to 5).foreach { repetition =>
      val group = s"${spec.id}-${args(3)}-$repetition"
      val identity = base ++ Map("repetition" -> repetition, "warmup" -> (repetition == 0), "job_group" -> group)
      val prepStart = System.nanoTime()
      val rows = sc.parallelize(data.columns.toSeq.map(v => v: SparkVector), partitions).cache()
      val costs = sc.parallelize(data.c.toSeq, partitions).glom().map(new DenseVector(_)).cache()
      rows.count(); costs.count()
      val preparation = (System.nanoTime() - prepStart) / 1e9
      sc.setJobGroup(group, group, interruptOnCancel = true)
      peakHeap.set(0); peakRss.set(0); sample()
      val started = System.nanoTime()
      val timeout = scheduler.schedule(new Runnable {
        override def run(): Unit = {
          write(identity ++ Map("status" -> "Timeout", "solve_seconds" -> 1800.0))
          Runtime.getRuntime.halt(124)
        }
      }, 30, TimeUnit.MINUTES)
      var validation = 0.0
      var residuals = Map.empty[String, Double]
      var initSeconds = Option.empty[Double]
      var previousPhase = "Initialization"
      var previousTime = 0.0
      val phaseTimes = scala.collection.mutable.Map.empty[String, Double].withDefaultValue(0.0)
      val ranks = scala.collection.mutable.ArrayBuffer.empty[Int]
      def progress(event: SolveProgress): Unit = {
        val name = event.phase.toString
        if (event.iteration > 0 && initSeconds.isEmpty) initSeconds = Some(event.elapsedSeconds)
        if (name != previousPhase) {
          phaseTimes(previousPhase) += event.elapsedSeconds - previousTime
          previousPhase = name; previousTime = event.elapsedSeconds
        }
        event.preconditionerRank.foreach { rank => if (!ranks.lastOption.contains(rank)) ranks += rank }
      }
      try {
        val result = LP.solveSummary(costs, rows, new DenseVector(data.b), tolerance = spec.tolerance,
          maxIter = 100, solver = backend, cgTolerance = 1e-10, cgMaxIterations = 1000,
          control = SolveControl(onProgress = Some(progress)),
          inspectConverged = Some((x, y, s) => {
            val validationStart = System.nanoTime()
            sc.setJobGroup(group + "-validation", "independent validation")
            try residuals = data.residuals(x.flatMap(_.values).collect(), y.values, s.flatMap(_.values).collect())
            finally { validation = (System.nanoTime() - validationStart) / 1e9; sc.setJobGroup(group, group) }
          }))
        val elapsed = (System.nanoTime() - started) / 1e9 - validation
        phaseTimes(previousPhase) += elapsed - previousTime
        try {
          val status = if (result.termination == LP.Termination.Converged) {
            if (Fixtures.passes(residuals, spec.tolerance)) "Success" else "AccuracyFailure"
          } else result.termination.toString
          write(identity ++ Map("status" -> status, "residuals" -> residuals,
            "solve_seconds" -> elapsed, "preparation_seconds" -> preparation,
            "validation_seconds" -> validation, "initialization_seconds" -> initSeconds,
            "phase_seconds" -> phaseTimes.toMap, "rank_events" -> ranks.toVector,
            "outer_iterations" -> result.iterations, "cg_steps" -> result.innerIterations,
            "cg_restarts" -> result.innerRestarts,
            "maximum_rank" -> result.preconditionerRank, "peak_heap_bytes" -> peakHeap.get(),
            "peak_rss_bytes" -> peakRss.get()))
          println(s"BENCHMARK $group $status ${elapsed}s")
        } finally result.x.unpersist(blocking = true)
      } catch {
        case error: LpNumericalException => write(identity ++ Map("status" -> "NumericalFailure",
          "error" -> error.getMessage, "outer_iterations" -> error.completedIterations,
          "solve_seconds" -> ((System.nanoTime() - started) / 1e9)))
      } finally {
        timeout.cancel(false)
        sc.clearJobGroup()
        sc.getPersistentRDDs.values.foreach(_.unpersist(blocking = true))
      }
    } } finally {
      sampling.cancel(false); scheduler.shutdownNow(); writer.close(); spark.stop()
    }
  }
}
