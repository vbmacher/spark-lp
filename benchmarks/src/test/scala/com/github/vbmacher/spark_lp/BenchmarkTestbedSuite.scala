package com.github.vbmacher.spark_lp

import com.github.vbmacher.spark_lp.support.BenchmarkTestbed
import org.scalatest.funsuite.AnyFunSuite

class BenchmarkTestbedSuite extends AnyFunSuite {
  private val identity = Map("os" -> "Linux", "architecture" -> "aarch64", "cpu" -> "ARM model",
    "cores" -> "8", "memory_bytes" -> "34359738368", "java" -> "11.0.26", "lapack" -> "native")

  test("local testbeds are stable and distinguish hardware and runtime changes") {
    val name = BenchmarkTestbed.localName(identity)
    assert(name.startsWith("local-linux-aarch64-8cpu-") && name.length <= 64)
    assert(name.matches("local-linux-aarch64-8cpu-[0-9a-f]{7}"))
    assert(name == BenchmarkTestbed.localName(identity.toSeq.reverse.toMap))
    identity.keys.foreach { key =>
      assert(name != BenchmarkTestbed.localName(identity.updated(key, identity(key) + "-changed")))
    }
    intercept[IllegalArgumentException](BenchmarkTestbed.localName(Map.empty))
    intercept[IllegalArgumentException](BenchmarkTestbed.localName(identity.updated("cpu", "")))
  }

  test("Linux CPU identity supports x86 and ARM while ignoring frequency and processor ordering") {
    assert(BenchmarkTestbed.linuxCpu("processor: 0\nmodel name: Example CPU\ncpu MHz: 3200\n") ==
      BenchmarkTestbed.linuxCpu("cpu MHz: 800\nmodel name: Example CPU\nprocessor: 4\nmodel name: Example CPU\n"))
    assert(BenchmarkTestbed.linuxCpu("CPU implementer: 0x41\nCPU part: 0xd0c\n") == "cpu implementer=0x41;cpu part=0xd0c")
    intercept[IllegalArgumentException](BenchmarkTestbed.linuxCpu("processor: 0\n"))
  }
}
