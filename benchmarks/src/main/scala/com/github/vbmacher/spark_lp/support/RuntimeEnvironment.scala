package com.github.vbmacher.spark_lp.support

import org.apache.spark.sql.SparkSession

import java.lang.management.ManagementFactory
import scala.util.Try

/** Captures the actual runtime for environment attribution, without assuming EMR or a host type. */
object RuntimeEnvironment {
  /** Describes the live Spark, JVM and OS environment as a flat, serialisable attribute map.
    *
    * Executor and per-executor task counts are inferred from the master URL when running
    * locally and otherwise read from the Spark configuration. Hostname and EMR name fall
    * back to the `benchmark.computer` and `benchmark.emrName` system properties.
    *
    * @param spark the session whose context and configuration are inspected.
    * @return runtime attributes keyed by name (versions, topology, BLAS/LAPACK, JVM args, Spark conf).
    */
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
