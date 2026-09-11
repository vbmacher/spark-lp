package com.github.vbmacher.spark_lp

import java.io.{File, PrintWriter}
import java.lang.management.ManagementFactory
import java.util.concurrent.{Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicLong

import com.github.vbmacher.spark_lp.dsl.LpNumericalException
import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.apache.spark.sql.SparkSession

/** Reproducible, planted-optimum LP benchmark. Run through Test/runMain (Spark is Provided).
  * Arguments: output.csv, comma-separated m:variableMultiplier:nonzerosPerColumn:rowScaleRatio,
  * repeats (default 2). See benchmarks/README.md.
  */
object NewtonBenchmark {
  def main(args: Array[String]): Unit = {
    // Project-matrix forks have a synthetic working directory; use an explicit absolute path.
    val output = new File(args.headOption.getOrElse(
      throw new IllegalArgumentException("Supply an absolute output CSV path")))
    require(output.isAbsolute, "Output CSV path must be absolute")
    val cases = args.lift(1).getOrElse(
      "32:2:1:1,256:4:8:1,1000:2:4:0.001,4500:2:1:1,5000:2:4:1,5500:2:1:0.001,6500:4:8:1")
      .split(",").map(_.split(":"))
    val repeats = args.lift(2).map(_.toInt).getOrElse(2)
    implicit val spark: SparkSession = SparkSession.builder().master("local[4]")
      .appName("spark-lp issue 25 benchmark").config("spark.ui.enabled", "false")
      .config("spark.ui.retainedJobs", "100000").config("spark.sql.shuffle.partitions", "4").getOrCreate()
    val sc = spark.sparkContext
    sc.setLogLevel("WARN")
    val writer = new PrintWriter(output)
    writer.println("m,n,nonzeros_per_column,row_scale_ratio,repeat,backend,status,primal,dual,gap,objective,objective_error,wall_seconds,outer_iterations,inner_iterations,preconditioner_rank,spark_jobs,peak_heap_bytes,peak_rss_bytes,error")
    writer.flush()
    val monitor = Executors.newSingleThreadScheduledExecutor()
    val peakHeap = new AtomicLong(0L)
    val peakRss = new AtomicLong(0L)
    def sample(): Unit = {
      val heap = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getUsed
      peakHeap.accumulateAndGet(heap, new java.util.function.LongBinaryOperator {
        override def applyAsLong(a: Long, b: Long): Long = math.max(a, b)
      })
      val source = scala.io.Source.fromFile("/proc/self/status")
      try {
        val rss = source.getLines().find(_.startsWith("VmRSS:")).map(_.split("\\s+")(1).toLong * 1024).getOrElse(0L)
        peakRss.accumulateAndGet(rss, new java.util.function.LongBinaryOperator {
          override def applyAsLong(a: Long, b: Long): Long = math.max(a, b)
        })
      } finally source.close()
    }
    val sampler = monitor.scheduleAtFixedRate(new Runnable {
      override def run(): Unit = sample()
    }, 0, 20, TimeUnit.MILLISECONDS)

    def run(m: Int, multiplier: Int, width: Int, ratio: Double, repeat: Int,
      backend: NewtonSolver, measured: Boolean): Unit = {
      val n = m * multiplier
      def scale(i: Int): Double = math.pow(ratio, i.toDouble / math.max(1, m - 1))
      val rows = sc.parallelize(0 until n, 4).map { j =>
        val entries = (0 until width).map { t =>
          val i = (j + t) % m
          i -> (scale(i) * (if (t == 0) 1.0 else 0.05 / width))
        }.sortBy(_._1)
        Vectors.sparse(m, entries)
      }.cache()
      val costs = rows.mapPartitionsWithIndex { case (partition, iterator) =>
        val start = partition * n / 4
        Iterator.single(new DenseVector(iterator.zipWithIndex.map { case (row, offset) =>
          row.toArray.sum + (if (start + offset < m) 0.0 else 1.0)
        }.toArray))
      }.cache()
      rows.count()
      costs.count()
      val rhs = Array.tabulate(m)(i => scale(i) * (1.0 + (width - 1) * 0.05 / width))
      val expected = rhs.sum
      val group = s"$m-$multiplier-$width-$ratio-$repeat-$backend-$measured"
      sc.setJobGroup(group, group)
      peakHeap.set(0L)
      peakRss.set(0L)
      sample()
      val start = System.nanoTime()
      var status = "NumericalFailure"
      var primal = Double.NaN
      var dual = Double.NaN
      var gap = Double.NaN
      var objective = Double.NaN
      var outer = 0
      var inner = "NA"
      var rank = "NA"
      var error = ""
      try {
        val result = LP.solveSummary(costs, rows, new DenseVector(rhs), tolerance = 1e-8,
          maxIter = 50, solver = backend, cgTolerance = 1e-10, cgMaxIterations = 1000)
        try {
          status = result.termination.toString
          primal = result.primalResidual
          dual = result.dualResidual
          gap = result.dualityGap
          objective = result.objectiveValue
          outer = result.iterations
          inner = result.innerIterations.toString
          rank = result.preconditionerRank.toString
        } finally result.x.unpersist(blocking = true)
      } catch {
        case e: LpNumericalException =>
          outer = e.completedIterations
          error = e.getMessage.replace(',', ';').replace('\n', ' ')
      }
      val seconds = (System.nanoTime() - start) / 1e9
      sample()
      val jobs = sc.statusTracker.getJobIdsForGroup(group).length
      if (measured) {
        writer.println(Seq(m, n, width, ratio, repeat, backend, status, primal, dual, gap, objective,
          math.abs(objective - expected), seconds, outer, inner, rank, jobs, peakHeap.get(), peakRss.get(), error).mkString(","))
        writer.flush()
        println(s"BENCHMARK $m/$n/$width/$ratio $backend $status ${seconds}s ($jobs jobs)")
      }
      sc.clearJobGroup()
      sc.getPersistentRDDs.values.foreach(_.unpersist(blocking = true))
    }
    try {
      Seq(NewtonSolver.Cholesky, NewtonSolver.ConjugateGradient).foreach(run(16, 2, 2, 1.0, 0, _, false))
      cases.foreach { fields =>
        val m = fields(0).toInt
        val multiplier = fields(1).toInt
        val width = fields(2).toInt
        val ratio = fields(3).toDouble
        require(m > 0 && multiplier >= 2 && width > 0 && width <= m && ratio > 0.0)
        (1 to repeats).foreach { repeat =>
          val backends = Seq(NewtonSolver.Cholesky, NewtonSolver.ConjugateGradient)
          (if (repeat % 2 == 1) backends else backends.reverse).foreach(
            run(m, multiplier, width, ratio, repeat, _, true))
        }
      }
    } finally {
      sampler.cancel(false)
      monitor.shutdown()
      writer.close()
      spark.stop()
    }
  }
}
