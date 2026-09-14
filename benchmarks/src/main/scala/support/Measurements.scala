package support

import com.github.vbmacher.spark_lp.SolveProgress
import org.apache.spark.sql.SparkSession

import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{Executors, TimeUnit}
import scala.util.Try

/** Monotonic wall-clock timer for generation, preparation and solver work. */
final class Stopwatch {
  private val started = System.nanoTime()

  def seconds: Double = (System.nanoTime() - started) / 1e9
}

/** Driver JVM sampling and per-solve watchdog, independent of algorithm and campaign.
  * RSS uses Linux /proc and remains unavailable on other systems. Heap is a sampled
  * JVM measurement, not a bound on total process memory or any remote executor.
  */
final class JvmSampler extends AutoCloseable {
  private val scheduler = Executors.newScheduledThreadPool(2, (r: Runnable) => {
    val thread = new Thread(r, "benchmark-measurements")
    thread.setDaemon(true)
    thread
  })
  private val heap = new AtomicLong()
  private val rss = new AtomicLong()

  private def peak(target: AtomicLong, value: Long): Unit = {
    var old = target.get()
    while (value > old && !target.compareAndSet(old, value)) old = target.get()
  }

  private def sample(): Unit = {
    peak(heap, ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getUsed)
    val file = new File("/proc/self/status")
    if (file.exists()) {
      val source = scala.io.Source.fromFile(file)
      try source.getLines().find(_.startsWith("VmRSS:")).foreach { line =>
        peak(rss, line.trim.split("\\s+")(1).toLong * 1024)
      } finally source.close()
    }
  }

  private val sampling = scheduler.scheduleAtFixedRate(new Runnable {
    override def run(): Unit = sample()
  }, 0, 20, TimeUnit.MILLISECONDS)

  def reset(): Unit = {
    heap.set(0);
    rss.set(0);
    sample()
  }

  def snapshot: Map[String, Any] = Map("peak_heap_bytes" -> heap.get(),
    "peak_rss_bytes" -> (if (rss.get() == 0) None else Some(rss.get())))

  def watchdog(onTimeout: => Unit): AutoCloseable = {
    val scheduled = scheduler.schedule(new Runnable {
      override def run(): Unit = {
        onTimeout;
        Runtime.getRuntime.halt(124)
      }
    }, 30, TimeUnit.MINUTES)
    () => scheduled.cancel(false)
  }

  override def close(): Unit = {
    sampling.cancel(false)
    scheduler.shutdownNow()
  }
}

/** Tracks core time separately from independent validation and progress phases. */
final class SolveMeasurements {
  private val clock = new Stopwatch
  private var validation = 0.0
  private var initialization = Option.empty[Double]
  private var phase = "Initialization"
  private var previous = 0.0
  private val phases = scala.collection.mutable.Map.empty[String, Double].withDefaultValue(0.0)
  private val ranks = scala.collection.mutable.ArrayBuffer.empty[Int]

  def progress(event: SolveProgress): Unit = {
    if (event.iteration > 0 && initialization.isEmpty) initialization = Some(event.elapsedSeconds)
    val name = event.phase.toString
    if (name != phase) {
      phases(phase) += event.elapsedSeconds - previous
      phase = name;
      previous = event.elapsedSeconds
    }
    event.work.flatMap(_.preconditionerRank).foreach(r => if (!ranks.lastOption.contains(r)) ranks += r)
  }

  def validate[A](body: => A): A = {
    val clock = new Stopwatch
    try body finally validation += clock.seconds
  }

  def solveSeconds: Double = clock.seconds - validation

  def snapshot(elapsed: Double): Map[String, Any] = Map("solve_seconds" -> elapsed,
    "validation_seconds" -> validation, "initialization_seconds" -> initialization,
    "phase_seconds" -> (phases.toMap + (phase -> (phases(phase) + elapsed - previous))),
    "rank_events" -> ranks.toVector)
}

/** Captures the actual runtime for environment attribution, without assuming EMR or a host type. */
object RuntimeEnvironment {
  def describe(spark: SparkSession): Map[String, Any] = {
    val sc = spark.sparkContext
    val local = sc.master.startsWith("local")
    val localCores = "local\\[(\\d+)\\]".r.findFirstMatchIn(sc.master).map(_.group(1).toInt)
      .getOrElse(Runtime.getRuntime.availableProcessors())
    Map("java" -> System.getProperty("java.runtime.version"), "jvm" -> System.getProperty("java.vm.name"),
      "computer" -> sys.props.getOrElse("benchmark.computer", Try(java.net.InetAddress.getLocalHost.getHostName).getOrElse("unrecorded")),
      "emr_name" -> sys.props.getOrElse("benchmark.emrName", ""),
      "os" -> System.getProperty("os.name"), "architecture" -> System.getProperty("os.arch"),
      "spark_version" -> spark.version, "scala_version" -> scala.util.Properties.versionNumberString,
      "master" -> sc.master,
      "executors" -> (if (local) Some(1) else sc.getConf.getOption("spark.executor.instances").map(_.toInt)),
      "concurrent_tasks_per_executor" -> (if (local) Some(localCores / sc.getConf.getInt("spark.task.cpus", 1))
      else sc.getConf.getOption("spark.executor.cores").map(_.toInt / sc.getConf.getInt("spark.task.cpus", 1))),
      "blas" -> com.github.fommil.netlib.BLAS.getInstance().getClass.getName,
      "lapack" -> com.github.fommil.netlib.LAPACK.getInstance().getClass.getName,
      "jvm_args" -> ManagementFactory.getRuntimeMXBean.getInputArguments.toString,
      "driver_max_heap_bytes" -> Runtime.getRuntime.maxMemory(), "spark_conf" -> sc.getConf.getAll.toMap)
  }
}
