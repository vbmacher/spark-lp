package com.github.vbmacher.spark_lp

import java.nio.file.{Files, Paths}
import com.github.vbmacher.spark_lp.support.{BenchmarkName, BenchmarkResults, Scenarios, Workloads}
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

/** Exercises every migrated DSL mode against its independent original-model optimum. */
class BenchmarkWorkloadSuite extends AnyFunSuite with BeforeAndAfterAll {
  private implicit var spark: SparkSession = _
  override def beforeAll(): Unit = {
    super.beforeAll()
    spark = SparkSession.builder().master("local[4]").appName("benchmark-workloads")
      .config("spark.ui.enabled", "false").config("spark.sql.shuffle.partitions", "4")
      .config("spark.default.parallelism", "4").getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")
    spark.sparkContext.setCheckpointDir(Files.createTempDirectory("benchmark-workload-checkpoints-").toString)
  }
  override def afterAll(): Unit = {
    try if (spark != null) spark.stop() finally super.afterAll()
  }
  private val scenarios = Scenarios.read(Paths.get("benchmarks/scenarios"), "capabilities").filterNot(_.kind == "lp") ++
    Scenarios.read(Paths.get("benchmarks/scenarios"), "kernels").filter(_.nativeThreads == 1).map(_.copy(caseId = "32"))
  scenarios.foreach { scenario =>
    test(BenchmarkName(scenario)) {
      val workload = Workloads.open(scenario, _ => ())
      try {
        val metrics = workload.measure()
        assert(metrics("latency") > 0)
        assert(metrics.values.forall(BenchmarkResults.finite))
      } finally workload.close()
    }
  }
}
