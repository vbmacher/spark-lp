package com.github.vbmacher.spark_lp

import java.io.File
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import com.github.vbmacher.spark_lp.support._
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.json4s.{DefaultFormats, Extraction}
import org.json4s.jackson.JsonMethods.{compact, parse, render}
import scala.collection.JavaConverters._
import scala.util.control.NonFatal

/** Public CLI and private worker protocol. BMF contains complete outcomes, never partial timings. */
object BenchmarkRunner {
  private implicit val formats: DefaultFormats.type = DefaultFormats
  private val MainClass = "com.github.vbmacher.spark_lp.BenchmarkRunner"
  private val Usage = "bench list|validate|run|testbed [--suite NAME] [--scenarios DIR] [--case ID] [--backend NAME] " +
    "[--output FILE] [--bmf FILE --require-converged true|false] [--jar FILE --spark-submit PATH --checkpoint-uri URI]"

  /**
    * Validated command-line arguments for the benchmark coordinator.
    *
    * @param command operation: `list`, `validate`, or `run`.
    * @param suite scenario-suite name selected for listing or running.
    * @param directory directory containing scenario and case-inventory files.
    * @param output destination for a completed BMF result document.
    * @param caseId optional single case selected from the suite.
    * @param backend optional single solver backend selected from the suite.
    * @param bmf existing BMF document validated by the `validate` command.
    * @param jar benchmark assembly JAR used for isolated worker launches.
    * @param sparkSubmit executable used to launch Spark workers.
    * @param checkpoint HDFS or compatible URI used for Spark checkpoint data.
    * @param requireConverged require every validated BMF scenario to report convergence.
    */
  final case class Options(command: String, suite: Option[String] = None, directory: Path = Paths.get("benchmarks/scenarios"),
                           output: Path = Paths.get("benchmarks/output/results.bmf.json"), caseId: Option[String] = None,
                           backend: Option[String] = None, bmf: Option[Path] = None, jar: Option[Path] = None,
                           sparkSubmit: String = "spark-submit", checkpoint: String = "hdfs:///spark-lp-benchmarks/checkpoints",
                           requireConverged: Boolean = false)

  def options(args: Array[String]): Options = {
    require(args.nonEmpty && Set("list", "validate", "run")(args(0)), Usage)
    require(args.drop(1).length % 2 == 0, Usage)
    val pairs = args.drop(1).grouped(2).map(a => a(0) -> a(1)).toVector
    require(pairs.map(_._1).distinct.size == pairs.size, "Duplicate CLI option")
    pairs.foldLeft(Options(args(0))) { case (o, (key, value)) =>
      require(value.nonEmpty, s"Missing value: $key")
      key match {
        case "--suite" => o.copy(suite = Some(value))
        case "--scenarios" => o.copy(directory = Paths.get(value))
        case "--output" => o.copy(output = Paths.get(value))
        case "--case" => o.copy(caseId = Some(value))
        case "--backend" => o.copy(backend = Some(value))
        case "--bmf" => require(o.command == "validate", "--bmf requires validate"); o.copy(bmf = Some(Paths.get(value)))
        case "--require-converged" =>
          require(o.command == "validate" && Set("true", "false")(value), "--require-converged requires validate and true|false")
          o.copy(requireConverged = value.toBoolean)
        case "--jar" => o.copy(jar = Some(Paths.get(value).toAbsolutePath))
        case "--spark-submit" => o.copy(sparkSubmit = value)
        case "--checkpoint-uri" => o.copy(checkpoint = value)
        case _ => throw new IllegalArgumentException(s"Unknown option: $key. $Usage")
      }
    }
  }

  def main(arguments: Array[String]): Unit = {
    try {
      val args = if (arguments.nonEmpty) arguments else sys.env.get("SPARK_LP_BENCH_ARGS").map(_.split("\n", -1)).getOrElse(Array.empty[String])
      if (args.headOption.contains("_worker")) {
        require(args.length == 3, "Invalid worker invocation")
        worker(Paths.get(args(1)), Paths.get(args(2)))
      } else if (args.headOption.exists(Set("_testbed", "testbed"))) {
        val internal = args(0) == "_testbed"
        require(args.length == (if (internal) 2 else 1), "Usage: bench testbed")
        val testbed = BenchmarkTestbed.automatic()
        if (internal) AtomicOutput.write(Paths.get(args(1)), testbed + "\n") else println(testbed)
        System.err.println(s"Automatic testbed: $testbed")
      } else {
        val o = options(args)
        require(!o.requireConverged || o.bmf.nonEmpty, "--require-converged requires --bmf")
        o.bmf match {
          case Some(path) =>
            val content = new String(Files.readAllBytes(path), UTF_8)
            BenchmarkResults.validate(content)
            if (o.requireConverged) BenchmarkResults.requireConverged(content)
            println("BMF valid")
          case None =>
            val suites = o.suite.toVector match {
              case names if names.nonEmpty => names
              case _ if o.command == "run" => Vector("smoke")
              case _ =>
                val paths = Files.list(o.directory)
                try paths.iterator().asScala.map(_.getFileName.toString).filter(_.endsWith(".json")).map(_.stripSuffix(".json")).toVector.sorted
                finally paths.close()
            }
            require(suites.nonEmpty, "No suites found")
            suites.foreach { suite =>
              val all = Scenarios.read(o.directory, suite)
              val selected = all.filter(s => o.caseId.forall(_ == s.caseId) && o.backend.forall(_ == s.backend))
              require(selected.nonEmpty, "No matching scenarios")
              if (o.command == "run") run(selected, o)
              else {
                println(s"$suite: ${selected.size} scenarios")
                if (o.command == "list" && o.suite.nonEmpty) selected.foreach(s => println(BenchmarkName(s)))
              }
            }
        }
      }
    } catch {
      case error: IllegalArgumentException => System.err.println(error.getMessage); sys.exit(2)
      case NonFatal(error) => System.err.println(s"Benchmark failed: ${error.getMessage}"); sys.exit(1)
    }
  }

  private def encode(value: Any): String = compact(render(Extraction.decompose(value))) + "\n"

  /** Snapshot compiled directories before launching workers; cached dependency JARs are immutable inputs. */
  private def snapshotClasspath(directory: Path): String = {
    Files.createDirectories(directory)
    System.getProperty("java.class.path").split(File.pathSeparator).zipWithIndex.map { case (entry, index) =>
      val path = Paths.get(entry).toAbsolutePath
      if (!Files.isDirectory(path)) path.toString else {
        val destination = directory.resolve(index.toString)
        val files = Files.walk(path)
        try files.iterator().asScala.foreach { source =>
          val target = destination.resolve(path.relativize(source))
          if (Files.isDirectory(source)) Files.createDirectories(target)
          else Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
        } finally files.close()
        destination.toString
      }
    }.mkString(File.pathSeparator)
  }

  private def digest(path: Path): String = {
    val hash = MessageDigest.getInstance("SHA-256")
    val input = Files.newInputStream(path)
    try {
      val buffer = new Array[Byte](65536)
      var count = input.read(buffer)
      while (count >= 0) { hash.update(buffer, 0, count); count = input.read(buffer) }
    } finally input.close()
    hash.digest().map(b => f"${b & 0xff}%02x").mkString
  }

  def excluded(s: Scenario): Boolean = s.spec.exists { c =>
    val m = c.m.toDouble
    val n = c.n.toDouble
    val z = if (c.family == "dense") m * n else m * math.min(c.width, c.n - c.m + 1) * (if (c.family == "dependent") 2 else 1)
    val driver = 16 * m * m + 64 * m
    val executor = (12 * z + 104 * n) / math.max(1, s.executors) + 8 * s.executorCores * m * (m + 1) + 8 * s.executorCores * m
    val local = 16 * m * m + 8 * s.cores * m * (m + 1)
    s.backend == "cholesky" && (if (s.executors == 0) local > s.heapGiB * math.pow(1024, 3) / 2
      else driver > s.heapGiB * math.pow(1024, 3) / 2 || executor > s.executorHeapGiB * math.pow(1024, 3) / 2)
  }

  def run(scenarios: Vector[Scenario], o: Options): Unit = AtomicOutput.reserve(o.output) {
    require(scenarios.nonEmpty && scenarios.map(BenchmarkName.apply).distinct.size == scenarios.size, "Empty or duplicate scenario selection")
    require(!scenarios.exists(_.executors > 0) || (o.jar.exists(p => Files.isRegularFile(p)) && o.checkpoint.nonEmpty),
      "Distributed scenarios require --jar and a nonempty shared checkpoint URI")
    val raw = o.output.toAbsolutePath.resolveSibling(o.output.getFileName.toString + ".raw")
    require(!Files.exists(raw), s"Raw output already exists: $raw")
    Files.createDirectories(raw)
    val classpath = snapshotClasspath(raw.resolve("classpath"))
    val jarSnapshot = o.jar.map { path =>
      val destination = raw.resolve("benchmarks.jar")
      Files.copy(path, destination)
      destination
    }
    val nativeOptions = ManagementFactory.getRuntimeMXBean.getInputArguments.asScala
      .filter(_.startsWith("-Dcom.github.fommil.netlib.")).toVector
    val nativeJavaOptions = nativeOptions.map(value => "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").mkString(" ")
    val nativeSparkOptions = if (nativeOptions.isEmpty) Vector.empty[String] else
      Vector("--driver-java-options", nativeJavaOptions, "--conf", s"spark.executor.extraJavaOptions=$nativeJavaOptions")
    val inputPaths = classpath.split(File.pathSeparator).map(Paths.get(_)).filter(p => Files.isRegularFile(p)).toVector
    val copiedPaths = {
      val paths = Files.walk(raw.resolve("classpath"))
      try paths.iterator().asScala.filter(p => Files.isRegularFile(p)).toVector finally paths.close()
    }
    AtomicOutput.write(raw.resolve("manifest.json"), encode(Map("scenarios" -> scenarios,
      "runtime_sha256" -> (inputPaths ++ copiedPaths ++ jarSnapshot).map(p => p.toString -> digest(p)).toMap,
      "source_revision" -> sys.env.getOrElse("GITHUB_SHA", "local-unpublished"), "native_options" -> nativeOptions)))
    val outcomes = scenarios.zipWithIndex.map { case (scenario, index) =>
      runScenario(scenario, index, scenarios.size, o, raw, classpath, jarSnapshot, nativeOptions, nativeSparkOptions)
    }
    val failed = outcomes.count(_._2.exists(a => a.status != "Success" && a.status != "ResourceExcluded"))
    val omitted = outcomes.count(_._2.exists(_.status == "ResourceExcluded"))
    System.err.println(s"${outcomes.size - failed - omitted} passed; $failed failed; $omitted resource-excluded; diagnostics: $raw")
    val bmf = outcomes.map { case (s, _, metrics) =>
      if (s.baseline && metrics.contains("solve-seconds")) {
        val base = outcomes.find(x => x._1.computeCores == 1 && BenchmarkName.comparison(x._1) == BenchmarkName.comparison(s))
          .getOrElse(throw new IllegalArgumentException("Missing selected one-core baseline"))
        base._3.get("solve-seconds").foreach { baseline =>
          val ratios = BenchmarkResults.parallel(baseline, metrics("solve-seconds"), s.computeCores)
          System.err.println(s"${BenchmarkName(s)}: speedup=${ratios("speedup").value}, efficiency=${ratios("parallel-efficiency").value}")
        }
      }
      BenchmarkName(s) -> metrics
    }.toMap
    AtomicOutput.write(o.output, BenchmarkResults.json(bmf))
    System.err.println(s"BMF: ${o.output.toAbsolutePath}")
    if (failed != 0) throw new IllegalStateException("At least one scenario failed; BMF records non-convergence without partial timings")
  }

  private def runScenario(s: Scenario, index: Int, total: Int, options: Options, raw: Path,
    classpath: String, jarSnapshot: Option[Path], nativeOptions: Vector[String],
    nativeSparkOptions: Vector[String]): (Scenario, Vector[Attempt], Map[String, Measurement]) = {
    val batch = raw.resolve(f"$index%04d")
    Files.createDirectory(batch)
    val spec = batch.resolve("scenario.json")
    AtomicOutput.write(spec, encode(s))
    System.err.println(s"[${index + 1}/$total] ${BenchmarkName(s)}")
    val attempts = if (excluded(s))
      Vector.tabulate(s.warmups + s.repetitions)(i => Attempt(i, i < s.warmups, "ResourceExcluded"))
    else {
      val workerArgs = Vector("_worker", spec.toString, batch.toString)
      val command = if (s.executors == 0)
        Vector(Paths.get(System.getProperty("java.home"), "bin", "java").toString,
          s"-Xms${s.heapGiB}g", s"-Xmx${s.heapGiB}g") ++ nativeOptions ++
          Vector("-cp", classpath, MainClass) ++ workerArgs
      else Vector(options.sparkSubmit, "--master", "yarn", "--deploy-mode", "client",
        "--driver-memory", s"${s.heapGiB}g", "--num-executors", s.executors.toString,
        "--executor-cores", s.executorCores.toString, "--executor-memory", s"${s.executorHeapGiB}g",
        "--conf", "spark.driver.maxResultSize=0",
        "--conf", s"spark.checkpoint.dir=${options.checkpoint}/benchmark-$index-${java.util.UUID.randomUUID()}",
        "--conf", s"spark.executorEnv.OPENBLAS_NUM_THREADS=${s.nativeThreads}",
        "--conf", s"spark.executorEnv.OMP_NUM_THREADS=${s.nativeThreads}",
        "--conf", s"spark.executorEnv.MKL_NUM_THREADS=${s.nativeThreads}") ++ nativeSparkOptions ++
        Vector("--class", MainClass, jarSnapshot.get.toString) ++ workerArgs
      AtomicOutput.write(batch.resolve("command.json"), encode(command))
      val process = new ProcessBuilder(command.asJava).redirectErrorStream(true)
        .redirectOutput(batch.resolve("application.log").toFile)
      Seq("OPENBLAS_NUM_THREADS", "OMP_NUM_THREADS", "MKL_NUM_THREADS")
        .foreach(process.environment().put(_, s.nativeThreads.toString))
      if (s.executors == 0) process.environment().put("SPARK_LOCAL_IP", "127.0.0.1")
      val child = process.start()
      val shutdown = new Thread(() => child.destroy(), "benchmark-worker-shutdown")
      Runtime.getRuntime.addShutdownHook(shutdown)
      val finished = try {
        val done = child.waitFor((s.warmups.toLong + s.repetitions) * 1800 + 600, TimeUnit.SECONDS)
        if (!done) {
          child.destroy()
          if (!child.waitFor(10, TimeUnit.SECONDS)) child.destroyForcibly().waitFor()
        }
        done
      } finally Runtime.getRuntime.removeShutdownHook(shutdown)
      val exit = if (finished) child.exitValue() else 124
      AtomicOutput.write(batch.resolve("exit.json"), encode(Map("exit_code" -> exit)))
      completedAttempts(s, batch, exit)
    }
    AtomicOutput.write(batch.resolve("complete.json"), encode(attempts))
    (s, attempts, BenchmarkResults.summarize(s, attempts))
  }

  private def completedAttempts(s: Scenario, batch: Path, exit: Int): Vector[Attempt] = {
    val file = batch.resolve("attempts.jsonl")
    val completed = if (Files.exists(file)) {
      val lines = Files.readAllLines(file, UTF_8).asScala.toVector
      lines.zipWithIndex.flatMap { case (line, i) =>
        try Some(parse(line).extract[Attempt])
        catch { case NonFatal(error) =>
          require(i == lines.size - 1 && exit != 0, s"Corrupt attempt record: ${error.getMessage}")
          None
        }
      }
    } else Vector.empty
    require(completed.size <= s.warmups + s.repetitions, "Unexpected extra attempts")
    val missing = (completed.size until s.warmups + s.repetitions).map { i =>
      val status = if (i == completed.size) { if (exit == 124) "Timeout" else "ProcessFailure" } else "Unrun"
      Attempt(i, i < s.warmups, status, error = Some(s"Worker exit $exit; see application.log"))
    }
    val all = completed ++ missing
    if (exit != 0 && missing.isEmpty)
      all.updated(all.size - 1, all.last.copy(status = "ProcessFailure", metrics = Map.empty,
        error = Some(s"Worker exited $exit after recording attempts")))
    else all
  }

  private def worker(spec: Path, directory: Path): Unit = {
    val s = parse(new String(Files.readAllBytes(spec), UTF_8)).extract[Scenario]
    val writer = Files.newBufferedWriter(directory.resolve("attempts.jsonl"), UTF_8, StandardOpenOption.CREATE_NEW)
    val detail = Files.newBufferedWriter(directory.resolve("diagnostics.jsonl"), UTF_8, StandardOpenOption.CREATE_NEW)
    def diagnostic(value: Map[String, Any]): Unit = detail.synchronized { detail.write(encode(value)); detail.flush() }
    val sampler = new JvmSampler
    val executorMemory = new ExecutorMemory
    val conf = new SparkConf().setMaster(s.master).setAppName(s"benchmark-${s.caseId}")
      .set("spark.ui.enabled", "false").set("spark.sql.shuffle.partitions", s.partitions.toString)
      .set("spark.default.parallelism", s.partitions.toString).set("spark.dynamicAllocation.enabled", "false")
      .set("spark.speculation", "false").set("spark.task.cpus", "1")
      .set("spark.scheduler.minRegisteredResourcesRatio", "1.0")
      .set("spark.executor.processTreeMetrics.enabled", "true").set("spark.executor.metrics.pollingInterval", "20")
      .set("spark.executor.heartbeatInterval", "1s")
    if (s.executors > 0) conf.set("spark.executor.instances", s.executors.toString).set("spark.executor.cores", s.executorCores.toString)
    // Kernel measurements do not start Spark.
    implicit val spark: SparkSession = if (s.kind == "factorization") null else SparkSession.builder().config(conf).getOrCreate()
    try {
      if (spark != null) {
        if (s.executors > 0) spark.sparkContext.addSparkListener(executorMemory)
        spark.sparkContext.setLogLevel("ERROR")
        spark.sparkContext.setCheckpointDir(conf.getOption("spark.checkpoint.dir").getOrElse(directory.resolve("checkpoints").toString))
        diagnostic(RuntimeEnvironment.describe(spark))
        spark.range(1).count()
        if (s.executors > 0) {
          val observed = spark.sparkContext.statusTracker.getExecutorInfos.length - 1
          diagnostic(Map("observed_executors" -> observed, "configured_executors" -> s.executors))
          require(observed == s.executors, s"Expected ${s.executors} executors, observed $observed")
        }
      } else diagnostic(Map("java" -> System.getProperty("java.version"), "lapack" -> org.apache.spark.wrappers.NativeNetlib.lapack.getClass.getName))
      val workload = Workloads.open(s, diagnostic)
      try { (0 until s.warmups + s.repetitions).foreach { repetition =>
        sampler.reset()
        val timer = sampler.watchdog { System.err.println("Benchmark solve timed out") }
        val attempt = try {
          val metrics = workload.measure()
          require(metrics.values.forall(BenchmarkResults.finite), "Non-finite measurement")
          Attempt(repetition, repetition < s.warmups, "Success", metrics)
        } catch {
          case NonFatal(error) =>
            error.printStackTrace(System.err)
            Attempt(repetition, repetition < s.warmups, "Failure", error = Some(error.toString))
        } finally timer.close()
        diagnostic(sampler.snapshot ++ Map("repetition" -> repetition))
        val memory = sampler.measurements(if (s.executors > 0) "driver" else "local") ++ executorMemory.snapshot
        writer.write(encode(attempt.copy(metrics = attempt.metrics ++ memory))); writer.flush()
      } } finally workload.close()
    } finally {
      if (spark != null) spark.stop()
      sampler.close(); writer.close(); detail.close()
    }
  }
}
