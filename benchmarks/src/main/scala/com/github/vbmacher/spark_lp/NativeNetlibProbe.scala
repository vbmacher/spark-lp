package com.github.vbmacher.spark_lp

import org.apache.spark.{SparkConf, SparkEnv, TaskContext}
import org.apache.spark.sql.SparkSession
import org.apache.spark.wrappers.NativeNetlib

/** Verifies netlib selection in the driver and separate Spark executor JVMs. */
object NativeNetlibProbe {
  def main(args: Array[String]): Unit = {
    val partitions = args.headOption.map(_.toInt).getOrElse(1)
    val requireNativeLapack = args.contains("--require-native") || args.contains("--require-native-lapack")
    val conf = new SparkConf()
    if (!conf.contains("spark.master")) conf.setMaster("local[2]")
    sys.env.get("SPARK_HOME").foreach(conf.set("spark.home", _))
    sys.env.get("SPARK_SCALA_VERSION").foreach(conf.setExecutorEnv("SPARK_SCALA_VERSION", _))
    val spark = SparkSession.builder().config(conf).appName("spark-lp-native-netlib-probe").getOrCreate()
    try {
      println(s"spark.home=${spark.sparkContext.getConf.getOption("spark.home").getOrElse("<unset>")}")
      val driver = implementations("driver")
      val executors = spark.sparkContext.parallelize(0 until partitions, partitions).barrier()
        .mapPartitions { _ =>
          Iterator(implementations(s"executor:${SparkEnv.get.executorId}:${TaskContext.get.partitionId()}"))
        }.collect().distinct.sorted
      println(driver)
      executors.foreach(println)
      if (requireNativeLapack) {
        require((driver +: executors).forall(nativeLapack), "Native LAPACK was not selected in every JVM")
      }
    } finally spark.stop()
  }

  private def implementations(process: String): String =
    s"$process\tblas=${NativeNetlib.blas.getClass.getName}\tlapack=${NativeNetlib.lapack.getClass.getName}"

  private def nativeLapack(observation: String): Boolean = observation.contains("NativeSystemLAPACK")
}
