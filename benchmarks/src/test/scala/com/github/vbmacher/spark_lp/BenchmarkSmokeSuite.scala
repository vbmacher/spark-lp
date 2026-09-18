package com.github.vbmacher.spark_lp

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import com.github.vbmacher.spark_lp.support.{BenchmarkResults, Scenarios}
import org.json4s.jackson.JsonMethods.parse
import org.scalatest.funsuite.AnyFunSuite

class BenchmarkSmokeSuite extends AnyFunSuite {
  test("small deterministic Spark fixtures execute through fresh workers to complete valid BMF") {
    val output = Files.createTempDirectory("benchmark-smoke-").resolve("result.bmf.json")
    val scenarios = Scenarios.read(Paths.get("benchmarks/scenarios"), "smoke")
    BenchmarkRunner.run(scenarios, BenchmarkRunner.Options("run", output = output))
    val json = new String(Files.readAllBytes(output), UTF_8)
    BenchmarkResults.validate(json)
    assert(parse(json).children.size == 2)
    assert(parse(json).children.forall(v => (v \ "solve-seconds" \ "value").values.asInstanceOf[Double] > 0))
    BenchmarkResults.requireConverged(json)
    assert(parse(json).children.forall(v => (v \ "local-heap-bytes-max" \ "value").values.asInstanceOf[Double] > 0))
    assert(parse(json).children.forall(v => (v \ "cpus" \ "value").values == 2.0))
    assert(!Files.exists(output.resolveSibling("result.bmf.json.lock")))
  }

  test("worker failure records non-convergence without a misleading solve time and exits nonzero") {
    val output = Files.createTempDirectory("benchmark-failure-").resolve("failed.bmf.json")
    val scenario = Scenarios.read(Paths.get("benchmarks/scenarios"), "kernels").head
      .copy(caseId = "invalid", warmups = 0, repetitions = 1)
    intercept[IllegalStateException] {
      BenchmarkRunner.run(Vector(scenario), BenchmarkRunner.Options("run", output = output))
    }
    val json = new String(Files.readAllBytes(output), UTF_8)
    BenchmarkResults.validate(json)
    assert(parse(json).children.forall(v => (v \ "converged" \ "value").values == 0.0))
    assert(!json.contains("solve-seconds"))
    intercept[IllegalArgumentException](BenchmarkResults.requireConverged(json))
    val completed = output.resolveSibling("failed.bmf.json.raw").resolve("0000/complete.json")
    assert(new String(Files.readAllBytes(completed), UTF_8).contains("Failure"))
    assert(!Files.exists(output.resolveSibling("failed.bmf.json.lock")))
  }
}
