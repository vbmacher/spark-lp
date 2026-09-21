package com.github.vbmacher.spark_lp

import java.nio.file.{Files, Paths}
import com.github.vbmacher.spark_lp.support.{BenchmarkName, BenchmarkResults, Scenarios, Workloads}
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

/** Executes one feature-rich representative per workload kind against its independent original-model optimum. */
class BenchmarkWorkloadSuite extends AnyFunSuite with BeforeAndAfterAll {
  private implicit var spark: SparkSession = _
  override def beforeAll(): Unit = {
    super.beforeAll()
    spark = SparkSession.builder().master("local[2]").appName("benchmark-workloads")
      .config("spark.ui.enabled", "false").config("spark.sql.shuffle.partitions", "2")
      .config("spark.default.parallelism", "2").getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")
    spark.sparkContext.setCheckpointDir(Files.createTempDirectory("benchmark-workload-checkpoints-").toString)
  }
  override def afterAll(): Unit = {
    try if (spark != null) spark.stop() finally super.afterAll()
  }
  private val representatives = Set(
    ("presolve", "fixed_rows", "auto", "full"),
    ("start", "lp_coordinates", "auto", "started"),
    ("mip", "knapsack4", "auto", "cuts+strong+parallel"),
    ("qp", "4", "cholesky", "factor"))
  private val capabilities = Scenarios.read(Paths.get("benchmarks/scenarios"), "capabilities")
    .filter(s => representatives((s.kind, s.caseId, s.backend, s.mode))).map(_.copy(cores = 2, partitions = 2))
  require(capabilities.map(s => (s.kind, s.caseId, s.backend, s.mode)).toSet == representatives,
    "Representative benchmark scenarios are missing")
  private val kernels = Scenarios.read(Paths.get("benchmarks/scenarios"), "kernels")
    .filter(s => s.nativeThreads == 1 && s.heapGiB == 4).map(_.copy(caseId = "32"))
  require(kernels.map(_.mode).toSet == Set("full", "packed"), "Representative kernel scenarios are missing")
  private val scenarios = capabilities ++ kernels
  scenarios.foreach { scenario =>
    test(BenchmarkName(scenario)) {
      val workload = Workloads.open(scenario, _ => ())
      try {
        val metrics = workload.measure()
        assert(metrics("solve-seconds") > 0)
        assert(metrics.values.forall(BenchmarkResults.finite))
      } finally workload.close()
    }
  }
}
