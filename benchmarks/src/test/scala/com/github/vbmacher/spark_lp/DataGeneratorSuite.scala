package com.github.vbmacher.spark_lp

import org.apache.spark.mllib.linalg.{DenseVector, Vector => SparkVector}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import support.{BenchmarkCase, DataGenerator}

class DataGeneratorSuite extends AnyFunSuite with BeforeAndAfterAll {
  private implicit var spark: SparkSession = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    spark = SparkSession.builder().master("local[2]").appName("benchmark-regressions")
      .config("spark.ui.enabled", "false").config("spark.sql.shuffle.partitions", "2").getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")
  }

  override def afterAll(): Unit = {
    try if (spark != null) spark.stop() finally super.afterAll()
  }

  private def spec(family: String): BenchmarkCase = BenchmarkCase(family, 4, 9, 3, family, 11, 1e-7, 1)

  test("generation is deterministic across partition counts and changes with the seed") {
    val a = DataGenerator.generate(spec("well"), 1)
    val b = DataGenerator.generate(spec("well"), 3)
    val c = DataGenerator.generate(spec("well").copy(seed = 12), 2)
    try {
      assert(a.hash == b.hash)
      assert(a.coefficients.orderBy("i", "j").collect().sameElements(b.coefficients.orderBy("i", "j").collect()))
      assert(a.variables.select("j", "x", "s").orderBy("j").collect()
        .sameElements(b.variables.select("j", "x", "s").orderBy("j").collect()))
      assert(a.hash != c.hash)
      assert(!a.coefficients.orderBy("i", "j").collect().sameElements(c.coefficients.orderBy("i", "j").collect()))
    } finally { a.close(); b.close(); c.close() }
  }

  Seq("well", "wide", "dependent", "degenerate", "dense").foreach { family =>
    test(s"$family fixture has a valid planted primal-dual witness") {
      val data = DataGenerator.generate(spec(family), 2)
      try {
        assert(DataGenerator.passes(data.residuals(data.variables.select("j", "x", "s"),
          data.constraints.select("i", "y")), 1e-12))
        assert(data.variables.filter(col("x") * col("s") =!= 0.0).count() == 0)
        assert(data.coefficients.select("i", "j").distinct().count() == data.nnz)
        if (family == "dense") assert(data.nnz == 36)
        if (family == "well" || family == "wide" || family == "degenerate") assert(data.nnz == 12)
      } finally data.close()
    }
  }

  test("accuracy gate rejects nonfinite, negative, missing and inaccurate residuals") {
    val valid = Map("primal" -> 0.0, "dual" -> 0.0, "gap" -> 0.0, "objective_error" -> 0.0,
      "min_x" -> 0.0, "min_s" -> 0.0, "objective" -> -2.0, "dual_objective" -> -2.0)
    assert(DataGenerator.passes(valid, 1e-8))
    valid.keys.foreach { key =>
      Seq(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity).foreach { value =>
        assert(!DataGenerator.passes(valid.updated(key, value), 1e-8))
      }
    }
    Seq("primal", "dual", "gap", "objective_error").foreach { key =>
      Seq(-1.0, 1e-8, 1.0).foreach(value => assert(!DataGenerator.passes(valid.updated(key, value), 1e-8)))
      assert(!DataGenerator.passes(valid - key, 1e-8))
    }
    Seq("min_x", "min_s").foreach { key =>
      assert(!DataGenerator.passes(valid.updated(key, -1.0), 1e-8))
      assert(!DataGenerator.passes(valid - key, 1e-8))
    }
  }

  test("independent validation rejects incorrect primal, dual and slack candidates") {
    val data = DataGenerator.generate(spec("well"), 2)
    try {
      val actual = data.variables.select("j", "x", "s")
      val dual = data.constraints.select("i", "y")
      Seq("x", "s").foreach { name =>
        assert(!DataGenerator.passes(data.residuals(actual.withColumn(name, col(name) + 1.0), dual), 1e-8))
      }
      assert(!DataGenerator.passes(data.residuals(actual, dual.withColumn("y", col("y") + 1.0)), 1e-8))
    } finally data.close()
  }

  Benchmark.all.foreach { benchmark =>
    test(s"${benchmark.name} solves distributed fixtures through the benchmark adapter") {
      val data = DataGenerator.generate(spec("well"), 2)
      val input = data.columns(2).cache()
      val columns = input.map(v => v._3: SparkVector).cache()
      val costs = input.mapPartitions(it => Iterator(new DenseVector(it.map(_._2).toArray))).cache()
      try {
        var residuals = Map.empty[String, Double]
        val result = benchmark.solve(costs, columns, data.b, 1e-7, _ => (), (x, y, s) =>
          residuals = data.residuals(input.map(_._1), x, y, s))
        try {
          assert(result.termination == LP.Termination.Converged)
          assert(DataGenerator.passes(residuals, 1e-7), residuals.toString)
        } finally result.x.unpersist(blocking = true)
      } finally {
        costs.unpersist(blocking = true); columns.unpersist(blocking = true)
        input.unpersist(blocking = true); data.close()
      }
    }
  }
}
