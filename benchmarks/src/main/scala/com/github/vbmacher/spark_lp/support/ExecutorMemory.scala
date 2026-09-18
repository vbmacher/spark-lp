package com.github.vbmacher.spark_lp.support

import org.apache.spark.scheduler.{SparkListener, SparkListenerExecutorMetricsUpdate}

/** Observed whole-application peaks per executor, never a sum of unrelated peaks. */
final class ExecutorMemory extends SparkListener {
  private var peaks = Map.empty[String, Double]

  private[spark_lp] def observe(executor: String, heap: Long, rss: Long): Unit = synchronized {
    if (executor != "driver") {
      Map("executor-heap-bytes-max" -> heap, "executor-rss-bytes-max" -> rss).foreach { case (key, value) =>
        if (value > 0) peaks += key -> math.max(peaks.getOrElse(key, 0.0), value.toDouble)
      }
    }
  }

  override def onExecutorMetricsUpdate(event: SparkListenerExecutorMetricsUpdate): Unit =
    event.executorUpdates.values.filter(_.isSet()).foreach { metrics =>
      observe(event.execId, metrics.getMetricValue("JVMHeapMemory"), metrics.getMetricValue("ProcessTreeJVMRSSMemory"))
    }

  def snapshot: Map[String, Double] = synchronized { peaks }
}
