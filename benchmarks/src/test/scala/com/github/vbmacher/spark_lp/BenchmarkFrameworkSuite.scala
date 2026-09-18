package com.github.vbmacher.spark_lp

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import com.github.vbmacher.spark_lp.support._
import org.json4s.jackson.JsonMethods.parse
import org.scalatest.funsuite.AnyFunSuite

class BenchmarkFrameworkSuite extends AnyFunSuite {
  private val directory = Paths.get("benchmarks/scenarios")
  private def scenario: Scenario = Scenarios.read(directory, "smoke").head
  private def invalid(content: String): Unit = {
    val dir = Files.createTempDirectory("benchmark-invalid-")
    Files.write(dir.resolve("invalid.json"), content.getBytes(UTF_8))
    intercept[IllegalArgumentException](Scenarios.read(dir, "invalid"))
  }

  test("all declarative suites parse; the complete matrix retains every inventory and DSL mode") {
    val full = Scenarios.read(directory, "full")
    assert(full.map(_.kind).toSet == Set("lp", "presolve", "start", "mip", "qp", "factorization"))
    assert(full.count(_.kind == "mip") == 16)
    assert(full.count(_.kind == "qp") == 8)
    assert(full.count(_.kind == "presolve") == 6)
    assert(full.count(_.kind == "start") == 4)
    assert(full.size == 592)
    val smoke = Scenarios.read(directory, "smoke")
    Seq("local", "scaling", "accuracy", "distributed").foreach { inventory =>
      val expected = CaseInventory.read(directory.resolve(s"cases/$inventory.csv").toString)
      expected.foreach { c =>
        val backends = if (c.id.startsWith("partition-")) Set("cg") else Set("cholesky", "cg")
        assert(backends.forall(b => (full ++ smoke).exists(s => s.spec.contains(c) && s.backend == b)))
      }
    }
  }

  test("inventory paths reject traversal and absolute paths") {
    Seq("../cases/local.csv", "/cases/local.csv", "cases/../local.csv", "cases/nested/local.csv").foreach { path =>
      invalid(s"""{"scenarios":[{"kind":"lp","inventory":"$path","cases":["well"],"backends":["cg"]}]}""")
    }
  }

  test("incomplete, misspelled, empty, duplicate, cyclic and invalid configuration fails") {
    invalid("{}")
    invalid("{\"include\":[\"invalid\"]}")
    invalid("{\"include\":[],\"scenarios\":[]}")
    invalid("{\"scenarios\":[{}]}")
    invalid("{\"unknown\":1}")
    val valid = "{\"scenarios\":[{\"kind\":\"qp\",\"cases\":[\"4\"],\"backends\":[\"cg\"],\"modes\":[\"factor\"]}]}"
    Seq("\"cores\":[0]", "\"warmups\":-1", "\"repetitions\":0", "\"partitions\":[1.5]", "\"typo\":1",
      "\"baseline\":true", "\"heapGiB\":0").foreach(field => invalid(valid.replace("\"kind\"", field + ",\"kind\"")))
    invalid(valid.replace("\"4\"", "\"999\""))
    invalid(valid.replace("\"cg\"", "\"auto\""))
    invalid(valid.replace("\"factor\"", "\"unknown\""))
    invalid(valid.replace("\"4\"", "\"4\",\"4\""))
    invalid(valid.replace("\"kind\":", "\"kind\":\"qp\",\"kind\":"))
  }

  test("CSV rejects wrong headers, duplicate IDs, nonfinite tolerance and malformed dimensions") {
    Seq("bad\n", CaseInventory.Header + "\na,0,3,1,well,11,1e-8,1\n",
      CaseInventory.Header + "\na,1,3,1,well,11,NaN,1\n",
      CaseInventory.Header + "\na,1,3,1,well,11,1e-8,1\na,1,3,1,well,11,1e-8,1\n").foreach { content =>
      val path = Files.createTempFile("benchmark-inventory-", ".csv")
      Files.write(path, content.getBytes(UTF_8))
      intercept[IllegalArgumentException](CaseInventory.read(path.toString))
    }
  }

  test("benchmark identity is stable, distinguishes workload and topology, and excludes repetition policy") {
    val s = scenario
    assert(BenchmarkName(s) == "lp/well/well/m=4/n=9/w=3/seed=11/tol=1.0E-7/cholesky/default/cores=2/partitions=2/heap=1g")
    assert(BenchmarkName(s) == BenchmarkName(s.copy(repetitions = 9, warmups = 3)))
    Seq(s.copy(cores = 4), s.copy(partitions = 4), s.copy(backend = "cg"),
      s.copy(spec = s.spec.map(_.copy(seed = 12)))).foreach(other => assert(BenchmarkName(s) != BenchmarkName(other)))
    assert(BenchmarkName.comparison(s) == BenchmarkName.comparison(s.copy(cores = 4)))
    assert(BenchmarkName.comparison(s) != BenchmarkName.comparison(s.copy(partitions = 4)))
    val kernel = Scenarios.read(directory, "kernels").find(s => s.mode == "full" && s.heapGiB == 4).get
    assert(BenchmarkName(kernel) == "factorization/5000/lapack/full/heap=4g/native-threads=1")
    assert(BenchmarkName(kernel) == BenchmarkName(kernel.copy(cores = 8, partitions = 16)))
    assert(BenchmarkName(kernel) != BenchmarkName(kernel.copy(nativeThreads = 2)))
  }

  test("aggregation returns median and observed range for odd, even and singleton samples") {
    assert(BenchmarkResults.aggregate(Vector(9, 1, 3)) == Measurement(3, Some(1), Some(9)))
    assert(BenchmarkResults.aggregate(Vector(9, 1, 3, 5)) == Measurement(4, Some(1), Some(9)))
    assert(BenchmarkResults.aggregate(Vector(2)) == Measurement(2, Some(2), Some(2)))
    intercept[IllegalArgumentException](BenchmarkResults.aggregate(Vector.empty))
  }

  test("speedup and efficiency use cores, not Spark partitions; no synthetic confidence bounds") {
    val metrics = BenchmarkResults.parallel(Measurement(12), Measurement(4), 4)
    assert(metrics("speedup") == Measurement(3))
    assert(metrics("parallel-efficiency") == Measurement(0.75))
    intercept[IllegalArgumentException](BenchmarkResults.parallel(Measurement(1), Measurement(0), 2))
  }

  test("Bencher contains performance outcomes only, with observed memory and configured CPUs") {
    val s = scenario.copy(warmups = 0, repetitions = 1)
    val metrics = Map("solve-seconds" -> 1.5, "primal-max" -> 1e-9, "outer-iterations" -> 10.0,
      "local-heap-bytes-max" -> 1024.0)
    val result = BenchmarkResults.summarize(s, Vector(Attempt(0, false, "Success", metrics)))
    assert(result.keySet == Set("solve-seconds", "local-heap-bytes-max", "cpus", "converged"))
    assert(result("converged").value == 1 && result("cpus").value == s.cores)
    val excluded = BenchmarkResults.summarize(s, Vector(Attempt(0, false, "ResourceExcluded")))
    assert(excluded.keySet == Set("converged", "cpus") && excluded("converged").value == 0)
    val kernel = s.copy(kind = "factorization", nativeThreads = 2, cores = 8)
    assert(BenchmarkResults.summarize(kernel, Vector(Attempt(0, false, "Success", metrics)))("cpus").value == 2)
  }

  test("executor memory is observed, excludes the driver and never sums unrelated executor peaks") {
    val memory = new ExecutorMemory
    assert(memory.snapshot.isEmpty)
    memory.observe("driver", 9999, 9999)
    memory.observe("1", 100, 0)
    memory.observe("2", 80, 200)
    memory.observe("1", 90, 180)
    assert(memory.snapshot == Map("executor-heap-bytes-max" -> 100.0, "executor-rss-bytes-max" -> 200.0))
  }

  test("warmups and failures never improve solve-seconds, and missing attempts cannot publish") {
    val s = scenario.copy(warmups = 1, repetitions = 2)
    val attempts = Vector(Attempt(0, true, "Success", Map("solve-seconds" -> 999)),
      Attempt(1, false, "Success", Map("solve-seconds" -> 2)), Attempt(2, false, "Success", Map("solve-seconds" -> 4)))
    assert(BenchmarkResults.summarize(s, attempts)("solve-seconds").value == 3)
    assert(!BenchmarkResults.summarize(s, attempts.updated(2, Attempt(2, false, "Failure"))).contains("solve-seconds"))
    assert(!BenchmarkResults.summarize(s, attempts.updated(0, Attempt(0, true, "Failure"))).contains("solve-seconds"))
    intercept[IllegalArgumentException](BenchmarkResults.summarize(s, attempts.drop(1)))
  }

  test("BMF escapes names, orders keys deterministically and validates metrics, not just JSON syntax") {
    val name = "quoted\"name\\slash\nline"
    val a = Map(name -> Map("z" -> Measurement(3), "a" -> Measurement(2, Some(1), Some(3))), "first" -> Map("solve-seconds" -> Measurement(1)))
    val json = BenchmarkResults.json(a)
    assert(json == BenchmarkResults.json(a.toSeq.reverse.toMap))
    assert((parse(json) \ name \ "a" \ "value").values == 2.0)
    assert(json.indexOf("\"a\"") < json.indexOf("\"z\""))
    BenchmarkResults.validate(json)
    Seq("{}", "[]", "{\"b\":{}}", "{\"b\":{\"m\":{}}}",
      "{\"b\":{\"m\":{\"value\":\"1\"}}}", "{\"b\":{\"m\":{\"value\":1,\"lower_value\":2}}}",
      "{\"b\":{\"m\":{\"value\":1,\"value\":2}}}").foreach(content => intercept[IllegalArgumentException](BenchmarkResults.validate(content)))
  }

  test("NaN, infinity and overflowing numeric input are rejected without null or zero substitution") {
    Seq(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity).foreach { value =>
      intercept[IllegalArgumentException](Measurement(value))
      intercept[IllegalArgumentException](BenchmarkResults.aggregate(Vector(1, value)))
    }
    intercept[IllegalArgumentException](BenchmarkResults.validate("{\"b\":{\"solve-seconds\":{\"value\":1e999}}}"))
  }

  test("atomic output is absent until commit, remains absent on failure and refuses existing files") {
    val dir = Files.createTempDirectory("benchmark-atomic-")
    val file = dir.resolve("result.json")
    intercept[IllegalStateException] {
      AtomicOutput.reserve(file) { assert(!Files.exists(file)); throw new IllegalStateException("injected failure") }
    }
    assert(!Files.exists(file) && !Files.exists(dir.resolve("result.json.lock")))
    AtomicOutput.reserve(file) { AtomicOutput.write(file, "complete") }
    intercept[IllegalArgumentException](AtomicOutput.reserve(file) { AtomicOutput.write(file, "replacement") })
    assert(new String(Files.readAllBytes(file), UTF_8) == "complete")
    val paths = Files.list(dir)
    try assert(paths.count() == 1) finally paths.close()
  }

  test("CLI rejects ambiguous, unknown and incomplete options") {
    Seq(Array.empty[String], Array("run", "--suite"), Array("run", "--typo", "x"),
      Array("run", "--suite", "smoke", "--suite", "full"), Array("run", "--bmf", "x")).foreach { args =>
      intercept[IllegalArgumentException](BenchmarkRunner.options(args))
    }
    assert(BenchmarkRunner.options(Array("run", "--suite", "smoke")).suite.contains("smoke"))
  }

  test("distributed checkpoints default to shared HDFS with an optional CLI override") {
    val defaults = BenchmarkRunner.options(Array("run", "--suite", "distributed"))
    assert(defaults.checkpoint == "hdfs:///spark-lp-benchmarks/checkpoints")
    val overridden = BenchmarkRunner.options(Array("run", "--checkpoint-uri", "hdfs:///custom/checkpoints"))
    assert(overridden.checkpoint == "hdfs:///custom/checkpoints")
    intercept[IllegalArgumentException](BenchmarkRunner.options(Array("run", "--checkpoint-uri", "")))
  }

  test("resource exclusions preserve large direct cases without attempting unsafe allocations") {
    val s = Scenarios.read(directory, "distributed").find(s => s.caseId.contains("rows-100000-") && s.backend == "cholesky").get
    assert(BenchmarkRunner.excluded(s))
    assert(!BenchmarkRunner.excluded(s.copy(backend = "cg")))
    assert(!BenchmarkRunner.excluded(scenario))
  }
}
