package com.github.vbmacher.spark_lp.support

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import org.json4s._
import org.json4s.jackson.JsonMethods.{compact, parse, render}

final case class Measurement(value: Double, lower: Option[Double] = None, upper: Option[Double] = None) {
  require((Vector(value) ++ lower ++ upper).forall(BenchmarkResults.finite), "Non-finite BMF metric")
  require(lower.forall(_ <= value) && upper.forall(_ >= value), "Invalid metric bounds")
}

final case class Attempt(repetition: Int, warmup: Boolean, status: String,
                         metrics: Map[String, Double] = Map.empty, error: Option[String] = None)

object BenchmarkResults {
  type Bmf = Map[String, Map[String, Measurement]]
  def finite(value: Double): Boolean = !value.isNaN && !value.isInfinity

  /** Median and observed range, deliberately not a confidence interval. */
  def aggregate(values: Seq[Double]): Measurement = {
    require(values.nonEmpty && values.forall(finite), "Expected finite measurements")
    val sorted = values.sorted
    val middle = sorted.size / 2
    val median = if (sorted.size % 2 == 1) sorted(middle) else sorted(middle - 1) / 2 + sorted(middle) / 2
    Measurement(median, Some(sorted.head), Some(sorted.last))
  }

  def parallel(baseline: Measurement, candidate: Measurement, cores: Int): Map[String, Measurement] = {
    require(cores > 0 && baseline.value > 0 && candidate.value > 0)
    val speedup = baseline.value / candidate.value
    // Ratios of medians have no inferred uncertainty bounds.
    Map("speedup" -> Measurement(speedup), "parallel-efficiency" -> Measurement(speedup / cores))
  }

  def summarize(s: Scenario, attempts: Vector[Attempt]): Map[String, Measurement] = {
    require(attempts.size == s.warmups + s.repetitions, "Incomplete attempt set")
    require(attempts.map(_.repetition) == (0 until attempts.size).toVector, "Duplicate or unordered attempts")
    require(attempts.forall(a => a.warmup == (a.repetition < s.warmups)), "Incorrect warmup flags")
    val measured = attempts.filterNot(_.warmup)
    val valid = measured.filter(_.status == "Success")
    val counts = Map("measured-converged-count" -> Measurement(valid.size.toDouble),
      "measured-failed-count" -> Measurement(measured.count(a => a.status != "Success" && a.status != "ResourceExcluded").toDouble),
      "measured-excluded-count" -> Measurement(measured.count(_.status == "ResourceExcluded").toDouble))
    // A partial success set must never look like a faster benchmark.
    if (valid.size != s.repetitions || attempts.exists(a => a.warmup && a.status != "Success")) counts
    else {
      require(valid.forall(_.metrics.contains("latency")), "Missing runtime")
      val keys = valid.head.metrics.keySet
      require(valid.forall(_.metrics.keySet == keys), "Missing measured metrics")
      counts ++ keys.map { key =>
        val values = valid.map(_.metrics(key))
        key -> (if (key.endsWith("-max")) Measurement(values.max) else aggregate(values))
      }
    }
  }

  def json(results: Bmf): String = {
    require(results.nonEmpty, "Empty BMF result")
    val document = JObject(results.toList.sortBy(_._1).map { case (name, metrics) =>
      require(name.nonEmpty && metrics.nonEmpty, "Empty benchmark name or measures")
      name -> JObject(metrics.toList.sortBy(_._1).map { case (measure, metric) =>
        require(measure.nonEmpty, "Empty measure name")
        measure -> JObject(List("value" -> JDouble(metric.value)) ++
          metric.lower.map(v => "lower_value" -> JDouble(v)) ++ metric.upper.map(v => "upper_value" -> JDouble(v)))
      })
    })
    val result = compact(render(document)) + "\n"
    validate(result)
    result
  }

  /** Checks the vendored BMF schema, plus nonempty results and finite ordered bounds. */
  def validate(content: String): Unit = {
    def obj(value: JValue): List[JField] = value match {
      case JObject(xs) if xs.nonEmpty && xs.map(_._1).distinct.size == xs.size && xs.forall(_._1.nonEmpty) => xs
      case _ => throw new IllegalArgumentException("Expected a nonempty BMF object with unique names")
    }
    def number(value: JValue): Double = value match {
      case JDouble(n) if finite(n) => n
      case JDecimal(n) if finite(n.toDouble) => n.toDouble
      case JInt(n) if finite(n.toDouble) => n.toDouble
      case _ => throw new IllegalArgumentException("BMF values must be finite numbers")
    }
    obj(parse(content)).foreach { case (_, benchmarks) => obj(benchmarks).foreach { case (_, metric) =>
      val fields = obj(metric).toMap
      require(fields.keySet.subsetOf(Set("value", "lower_value", "upper_value")) && fields.contains("value"), "Invalid BMF metric fields")
      Measurement(number(fields("value")), fields.get("lower_value").map(number), fields.get("upper_value").map(number))
    } }
  }
}

object AtomicOutput {
  /** Reserve the destination throughout execution; no stale result may be consumed on failure. */
  def reserve[A](path: Path)(body: => A): A = {
    val absolute = path.toAbsolutePath
    Files.createDirectories(absolute.getParent)
    val lock = absolute.resolveSibling(absolute.getFileName.toString + ".lock")
    val channel = Files.newByteChannel(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    try {
      require(!Files.exists(absolute), s"Output already exists: $absolute")
      body
    } finally { channel.close(); Files.deleteIfExists(lock) }
  }

  def write(path: Path, content: String): Unit = {
    val absolute = path.toAbsolutePath
    Files.createDirectories(absolute.getParent)
    val temporary = Files.createTempFile(absolute.getParent, ".benchmark-", ".tmp")
    try {
      Files.write(temporary, content.getBytes(UTF_8))
      // Fail closed on filesystems without atomic rename support.
      require(!Files.exists(absolute), s"Output already exists: $absolute")
      Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE)
    } finally Files.deleteIfExists(temporary)
  }
}
