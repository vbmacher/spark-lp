package com.github.vbmacher.spark_lp.support

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import java.util.Locale
import scala.sys.process._

/** Local hardware/runtime identity; EMR uses the full cluster instead of just its primary node. */
object BenchmarkTestbed {
  def automatic(): String = {
    if (Files.exists(EmrTestbed.JobFlow)) EmrTestbed.discover()
    else {
      val os = ManagementFactory.getOperatingSystemMXBean.asInstanceOf[com.sun.management.OperatingSystemMXBean]
      val cpu = System.getProperty("os.name") match {
        case "Linux" => linuxCpu(new String(Files.readAllBytes(Paths.get("/proc/cpuinfo")), UTF_8))
        case "Mac OS X" => Process(Seq("sysctl", "-n", "machdep.cpu.brand_string")).!!.trim
        case name if name.startsWith("Windows") => sys.env.getOrElse("PROCESSOR_IDENTIFIER", "")
        case other => throw new IllegalArgumentException(s"Automatic testbed CPU detection is unsupported on $other")
      }
      require(os.getTotalPhysicalMemorySize > 0, "Cannot identify testbed memory")
      localName(EmrTestbed.runtimeIdentity ++ Map("cpu" -> cpu, "cores" -> os.getAvailableProcessors.toString,
        "memory_bytes" -> os.getTotalPhysicalMemorySize.toString))
    }
  }

  def linuxCpu(cpuinfo: String): String = {
    val fields = Set("model name", "hardware", "cpu implementer", "cpu part")
    val identity = cpuinfo.split("\n").toVector.flatMap { line =>
      line.split(":", 2) match {
        case Array(key, value) if fields(key.trim.toLowerCase(Locale.ROOT)) && value.trim.nonEmpty =>
          Some(key.trim.toLowerCase(Locale.ROOT) + "=" + value.trim)
        case _ => None
      }
    }.distinct.sorted.mkString(";")
    require(identity.nonEmpty, "Cannot identify testbed CPU from /proc/cpuinfo")
    identity
  }

  def localName(identity: Map[String, String]): String = {
    require(Set("os", "architecture", "cpu", "cores", "memory_bytes").subsetOf(identity.keySet) &&
      identity.values.forall(_.nonEmpty), "Incomplete local testbed identity")
    val prefix = s"local-${identity("os")}-${identity("architecture")}-${identity("cores")}cpu"
      .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").stripSuffix("-")
    readableName(prefix, identity)
  }

  private[support] def readableName(prefix: String, runtime: Map[String, String]): String = {
    def native(key: String): Boolean = runtime.get(key).exists(_.toLowerCase(Locale.ROOT).contains("native"))
    val suffix = if (native("blas")) "-native-blas" else if (native("lapack")) "-native-lapack" else ""
    val name = prefix + suffix
    require(name.length <= 64 && name.matches("[a-z0-9]+(-[a-z0-9]+)*"), "Testbed name is not a valid Bencher slug")
    name
  }
}
