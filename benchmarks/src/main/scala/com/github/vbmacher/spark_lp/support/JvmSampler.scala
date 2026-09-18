package com.github.vbmacher.spark_lp.support

import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{Executors, TimeUnit}

/** Driver JVM sampling and per-solve watchdog, independent of algorithm and campaign.
  *
  * A daemon scheduler samples peak heap and resident set size (RSS) every 20 ms and
  * retains the maxima observed since the last [[reset]]. RSS is read from the Linux
  * `/proc` filesystem and remains unavailable on other systems. Heap is a sampled JVM
  * measurement, not a bound on total process memory or any remote executor.
  *
  * Instances own background threads and must be released via [[close]].
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

  /** Discards previously recorded peaks and takes an immediate baseline sample. */
  def reset(): Unit = {
    heap.set(0);
    rss.set(0);
    sample()
  }

  /** Peak heap and RSS bytes since the last [[reset]]; RSS is `None` when unavailable. */
  def snapshot: Map[String, Any] = Map("peak_heap_bytes" -> heap.get(),
    "peak_rss_bytes" -> (if (rss.get() == 0) None else Some(rss.get())))

  def measurements(scope: String): Map[String, Double] = {
    require(Set("local", "driver")(scope))
    sample()
    Map(s"$scope-heap-bytes-max" -> heap.get().toDouble) ++
      (if (rss.get() > 0) Map(s"$scope-rss-bytes-max" -> rss.get().toDouble) else Map.empty[String, Double])
  }

  /** Runs `onTimeout` and halts the JVM with code 124 if not cancelled within 30 minutes.
    *
    * @return a handle whose `close` cancels the pending timeout.
    */
  def watchdog(onTimeout: => Unit): AutoCloseable = {
    val scheduled = scheduler.schedule(new Runnable {
      override def run(): Unit = {
        onTimeout;
        Runtime.getRuntime.halt(124)
      }
    }, 30, TimeUnit.MINUTES)
    () => scheduled.cancel(false)
  }

  /** Cancels sampling and shuts down the scheduler, terminating its daemon threads. */
  override def close(): Unit = {
    sampling.cancel(false)
    scheduler.shutdownNow()
  }
}
