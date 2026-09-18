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
    assert(parse(json).children.forall(v => (v \ "latency" \ "value").values.asInstanceOf[Double] > 0))
    assert(!Files.exists(output.resolveSibling("result.bmf.json.lock")))
  }

  test("worker failure leaves diagnostics and never commits an apparently valid BMF file") {
    val output = Files.createTempDirectory("benchmark-failure-").resolve("failed.bmf.json")
    val scenario = Scenarios.read(Paths.get("benchmarks/scenarios"), "kernels").head
      .copy(caseId = "invalid", warmups = 0, repetitions = 1)
    intercept[IllegalStateException] {
      BenchmarkRunner.run(Vector(scenario), BenchmarkRunner.Options("run", output = output))
    }
    assert(!Files.exists(output))
    val completed = output.resolveSibling("failed.bmf.json.raw").resolve("0000/complete.json")
    assert(new String(Files.readAllBytes(completed), UTF_8).contains("Failure"))
    assert(!Files.exists(output.resolveSibling("failed.bmf.json.lock")))
  }
}
