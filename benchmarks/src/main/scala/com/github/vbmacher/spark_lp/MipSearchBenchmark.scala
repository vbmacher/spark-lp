package com.github.vbmacher.spark_lp

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.concurrent.{Executors, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicLong
import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.dsl.implicits._
import org.apache.spark.sql.SparkSession

object MipSearchBenchmark {
  private final class MemorySample extends AutoCloseable {
    val heap = new AtomicLong(0L)
    val rss = new AtomicLong(0L)
    private val scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory {
      override def newThread(task: Runnable): Thread = { val thread = new Thread(task, "mip-memory-sample"); thread.setDaemon(true); thread }
    })
    private def retain(target: AtomicLong, value: Long): Unit = target.accumulateAndGet(value,
      new java.util.function.LongBinaryOperator { override def applyAsLong(a: Long, b: Long): Long = math.max(a, b) })
    scheduler.scheduleAtFixedRate(new Runnable {
      override def run(): Unit = {
        retain(heap, ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getUsed)
        val status = Paths.get("/proc/self/status")
        if (Files.exists(status)) {
          val source = scala.io.Source.fromFile(status.toFile)
          try source.getLines().find(_.startsWith("VmRSS:")).foreach { line =>
            retain(rss, line.split("\\s+")(1).toLong * 1024L)
          } finally source.close()
        }
      }
    }, 0L, 50L, TimeUnit.MILLISECONDS)
    override def close(): Unit = { scheduler.shutdown(); scheduler.awaitTermination(1L, TimeUnit.SECONDS); () }
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Usage: MipSearchBenchmark new-absolute-output-directory")
    val output = Paths.get(args(0))
    require(output.isAbsolute && !Files.exists(output), "Output must be a new absolute directory")
    Files.createDirectories(output)
    implicit val spark: SparkSession = SparkSession.builder().master("local[4]").appName("mip-search-benchmark")
      .config("spark.ui.enabled", "false").config("spark.sql.shuffle.partitions", "4")
      .config("spark.default.parallelism", "4").getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")
    val writer = Files.newBufferedWriter(output.resolve("records.csv"), StandardCharsets.UTF_8)
    val progress = Files.newBufferedWriter(output.resolve("progress.csv"), StandardCharsets.UTF_8)
    val environment = s"Spark=${spark.version}\nScala=${util.Properties.versionNumberString}\nJava=${System.getProperty("java.version")}\n" +
      s"OS=${System.getProperty("os.name")} ${System.getProperty("os.arch")}\nCPUs=${Runtime.getRuntime.availableProcessors()}\n" +
      "master=local[4]\npartitions=4\ntolerance=1e-8\nvalidationTolerance=1e-6\nwarmups=1\nrepetitions=3\n" +
      "cuts=maxRounds:1,maxCutsPerNode:4,maxCuts:8\nstrong=maxCandidates:2,maxIterations:8,maxProbes:8\nparallelNodes=2\nmemorySampleMilliseconds=50\n"
    Files.write(output.resolve("environment.txt"), environment.getBytes(StandardCharsets.UTF_8))
    writer.write("case,mode,repetition,warmup,seconds,status,objective,expected,valid,nodes,iterations,relaxations,cut_rounds,global_cuts,local_cuts,strong_probes,peak_concurrent,estimated_local_bytes,peak_heap_bytes,peak_rss_bytes,best_bound,absolute_gap\n")
    progress.write("case,mode,repetition,seconds,processed_nodes,open_nodes,incumbent,best_bound,absolute_gap\n")
    var failures = 0
    try {
      spark.range(1).count()
      for (kind <- Vector("knapsack4", "knapsack6"); repetition <- 0 to 3;
           mask <- (if (repetition % 2 == 0) 0 until 8 else (0 until 8).reverse)) {
        val cut = (mask & 1) != 0
        val strong = (mask & 2) != 0
        val parallel = (mask & 4) != 0
        val mode = if (mask == 0) "baseline" else Vector(if (cut) "cuts" else "", if (strong) "strong" else "",
          if (parallel) "parallel" else "").filter(_.nonEmpty).mkString("+")
        val weights = if (kind == "knapsack4") Vector(2.0, 3.0, 4.0, 5.0) else Vector(2.0, 3.0, 4.0, 5.0, 7.0, 9.0)
        val profits = if (kind == "knapsack4") Vector(3.0, 4.0, 5.0, 8.0) else Vector(4.0, 5.0, 7.0, 8.0, 11.0, 13.0)
        val capacity = if (kind == "knapsack4") 8.0 else 12.0
        val model = LpProblem(kind, Maximize)
        val xs = weights.indices.map(i => model.variable(s"item$i", category = Binary)).toVector
        model += lpDot(profits, xs) + 7.0
        model += (lpDot(weights, xs) <= capacity)
        val expected = (0 until (1 << xs.size)).map { bits =>
          val selected = xs.indices.filter(i => (bits & (1 << i)) != 0)
          selected.map(weights).sum -> selected.map(profits).sum
        }.filter(_._1 <= capacity).map(_._2).max + 7.0
        val policy = MipSearchConfig(cuts = MipCutsConfig(enabled = cut, maxRounds = 1, maxCutsPerNode = 4, maxCuts = 8),
          strongBranching = StrongBranchingConfig(enabled = strong, maxCandidates = 2, maxIterations = 8, maxProbes = 8),
          parallelNodes = if (parallel) 2 else 1)
        val control = MipControl(onProgress = p => {
          progress.write(s"$kind,$mode,$repetition,${p.elapsedSeconds},${p.processedNodes},${p.openNodes},${p.incumbent.map(_.toString).getOrElse("")},${p.bestBound.map(_.toString).getOrElse("")},${p.absoluteGap.map(_.toString).getOrElse("")}\n")
          progress.flush()
        })
        val memory = new MemorySample()
        val started = System.nanoTime()
        val result = try model.solve(SolveConfig(mip = MipConfig(maxNodes = 256, search = policy, control = control)))
          finally memory.close()
        val seconds = (System.nanoTime() - started).toDouble / 1e9
        try {
          val report = model.validateCandidate(model.candidateValues(xs.map(v => v -> result.value(v))),
            CandidateValidationConfig(tolerance = 1e-6))
          val valid = try report.feasible && result.status == LpStatus.Optimal && math.abs(result.objectiveValue - expected) <= 1e-6
            finally report.close()
          if (!valid) failures += 1
          val m = result.mip.get
          val s = m.search
          writer.write(s"$kind,$mode,$repetition,${repetition == 0},$seconds,${result.status},${result.objectiveValue},$expected,$valid,${m.processedNodes},${result.iterations},${s.relaxations},${s.cutRounds},${s.globalCuts},${s.localCuts},${s.strongProbes},${s.peakConcurrentNodes},${s.estimatedPeakLocalBytes},${memory.heap.get()},${memory.rss.get()},${m.bestBound.map(_.toString).getOrElse("")},${m.absoluteGap.map(_.toString).getOrElse("")}\n")
          writer.flush()
        } finally result.close()
      }
      require(failures == 0, s"$failures benchmark results failed independent validation")
    } finally { writer.close(); progress.close(); spark.stop() }
  }
}
