package com.github.vbmacher.spark_lp.support

import java.nio.file.{Files, Path}
import org.json4s._
import org.json4s.jackson.JsonMethods.parse

/**
  * One fully expanded benchmark execution scenario.
  *
  * @param kind workload kind, such as solver or factorization.
  * @param caseId case identifier from the selected inventory.
  * @param backend solver or kernel implementation under test.
  * @param mode backend configuration mode.
  * @param cores local Spark worker threads; ignored for YARN scenarios.
  * @param partitions Spark partition count used by the workload.
  * @param warmups unmeasured repetitions executed before samples.
  * @param repetitions measured repetitions.
  * @param heapGiB driver heap size in GiB.
  * @param spec mathematical LP case, absent for workloads without generated LP data.
  * @param baseline whether this scenario supplies the one-core comparison baseline.
  * @param executors YARN executor count; zero selects local mode.
  * @param executorCores cores assigned to each YARN executor.
  * @param executorHeapGiB heap size in GiB for each YARN executor.
  * @param nativeThreads native BLAS thread count.
  */
final case class Scenario(kind: String, caseId: String, backend: String, mode: String,
                          cores: Int, partitions: Int, warmups: Int, repetitions: Int,
                          heapGiB: Int, spec: Option[BenchmarkCase], baseline: Boolean = false,
                          executors: Int = 0, executorCores: Int = 4, executorHeapGiB: Int = 16,
                          nativeThreads: Int = 1) {
  def computeCores: Int = if (executors == 0) cores else executors * executorCores
  def master: String = if (executors == 0) s"local[$cores]" else "yarn"
}

/** Only workload dimensions belong in names; commits and machines belong in report metadata. */
object BenchmarkName {
  def apply(s: Scenario): String = {
    val workload = s.spec.map(c =>
      s"/${c.family}/m=${c.m}/n=${c.n}/w=${c.width}/seed=${c.seed}/tol=${c.tolerance}").getOrElse("")
    val identity = s"${s.kind}/${s.caseId}$workload/${s.backend}/${s.mode}"
    if (s.kind == "factorization") s"$identity/heap=${s.heapGiB}g/native-threads=${s.nativeThreads}"
    else {
      val topology = if (s.executors == 0) s"cores=${s.cores}" else s"executors=${s.executors}x${s.executorCores}"
      s"$identity/$topology/partitions=${s.partitions}/heap=${s.heapGiB}g" +
        (if (s.executors == 0) "" else s"/executor-heap=${s.executorHeapGiB}g") +
        (if (s.nativeThreads == 1) "" else s"/native-threads=${s.nativeThreads}")
    }
  }
  def comparison(s: Scenario): String = apply(s.copy(cores = 1, executors = 0))
}

object Scenarios {
  private val common = Set("kind", "inventory", "cases", "backends", "modes", "cores", "partitions",
    "warmups", "repetitions", "heapGiB", "baseline", "executors", "executorCores", "executorHeapGiB", "nativeThreads")
  private def fields(value: JValue, allowed: Set[String]): Unit = value match {
    case JObject(xs) =>
      require(xs.map(_._1).distinct.size == xs.size, "Duplicate configuration key")
      require(xs.forall(x => allowed(x._1)), s"Unknown fields: ${xs.map(_._1).filterNot(allowed).mkString(", ")}")
    case _ => throw new IllegalArgumentException("Expected a configuration object")
  }
  private def string(value: JValue): String = value match {
    case JString(s) if s.nonEmpty => s
    case _ => throw new IllegalArgumentException("Expected a nonempty string")
  }
  private def int(value: JValue): Int = value match {
    case JInt(n) if n.isValidInt => n.toInt
    case _ => throw new IllegalArgumentException("Expected an integer")
  }
  private def strings(value: JValue): Vector[String] = value match {
    case JArray(xs) if xs.nonEmpty => xs.map(string).toVector
    case _ => throw new IllegalArgumentException("Expected a nonempty string array")
  }
  private def ints(value: JValue): Vector[Int] = value match {
    case JArray(xs) if xs.nonEmpty => xs.map(int).toVector
    case _ => throw new IllegalArgumentException("Expected a nonempty integer array")
  }
  private def number(value: JValue, key: String, default: Int): Int =
    (value \ key) match { case JNothing => default; case n => int(n) }
  private def names(value: JValue, key: String, default: Vector[String]): Vector[String] =
    (value \ key) match { case JNothing => default; case v => strings(v) }

  def read(directory: Path, suite: String): Vector[Scenario] = {
    def load(name: String, stack: Set[String]): Vector[Scenario] = {
      require(name.matches("[a-z][a-z0-9-]*"), "Suite must be a lowercase slug")
      require(!stack(name), s"Cyclic suite include: $name")
      val document = parse(new String(Files.readAllBytes(directory.resolve(s"$name.json")), "UTF-8"))
      fields(document, Set("include", "scenarios"))
      val included = names(document, "include", Vector.empty).flatMap(load(_, stack + name))
      val own = (document \ "scenarios") match {
        case JNothing => Vector.empty
        case JArray(rows) => rows.toVector.flatMap(expand(_, directory))
        case _ => throw new IllegalArgumentException("scenarios must be an array")
      }
      val result = included ++ own
      require(result.nonEmpty, s"Empty suite: $name")
      result
    }
    val result = load(suite, Set.empty)
    require(result.map(BenchmarkName.apply).distinct.size == result.size, "Duplicate benchmark identity")
    result.filter(_.baseline).groupBy(BenchmarkName.comparison).foreach { case (_, group) =>
      require(group.exists(_.computeCores == 1), "Parallelism group requires a one-core baseline")
      require(group.forall(_.executors == 0), "Derived speedup currently requires controlled local threads")
      require(group.map(s => (s.warmups, s.repetitions)).distinct.size == 1, "Baseline sampling differs")
    }
    result
  }

  private def expand(row: JValue, directory: Path): Vector[Scenario] = {
    fields(row, common)
    val kind = string(row \ "kind")
    val cases = strings(row \ "cases")
    val cores = (row \ "cores") match { case JNothing => Vector(4); case v => ints(v) }
    val partitions = (row \ "partitions") match { case JNothing => Vector(4); case v => ints(v) }
    val nativeThreads = (row \ "nativeThreads") match { case JNothing => Vector(1); case v => ints(v) }
    val backends = names(row, "backends", Vector("auto"))
    val modes = names(row, "modes", Vector("default"))
    val warmups = number(row, "warmups", 1)
    val repetitions = number(row, "repetitions", 5)
    val heap = number(row, "heapGiB", 4)
    val executors = number(row, "executors", 0)
    val executorCores = number(row, "executorCores", 4)
    val executorHeap = number(row, "executorHeapGiB", 16)
    val baseline = (row \ "baseline") match {
      case JNothing => false; case JBool(v) => v
      case _ => throw new IllegalArgumentException("baseline must be boolean")
    }
    require(cores.forall(_ > 0) && partitions.forall(_ > 0) && nativeThreads.forall(_ > 0) && heap > 0 && executorCores > 0 && executorHeap > 0,
      "Cores, partitions, native threads and heaps must be positive")
    require(warmups >= 0 && repetitions > 0 && executors >= 0 && warmups.toLong + repetitions <= Int.MaxValue,
      "Invalid warmup, repetition or executor count")
    require(Set("lp", "presolve", "start", "mip", "qp", "factorization")(kind), s"Unknown workload: $kind")
    require(kind != "factorization" || (executors == 0 && !baseline), "Kernel scenarios do not use Spark resources or Spark speedup baselines")
    require(backends.forall(Set("auto", "cg", "cholesky", "lapack")), "Unknown backend")
    val specs: Vector[(String, Option[BenchmarkCase])] = if (kind == "lp") {
      require(!backends.contains("auto") && !backends.contains("lapack") && modes == Vector("default"),
        "LP scenarios require cholesky/cg backends and default mode")
      val inventory = string(row \ "inventory")
      require(inventory.matches("(?:cases/)?[a-z0-9-]+\\.csv"), "Inventory must be a CSV filename, optionally under cases/")
      val all = CaseInventory.read(directory.resolve(inventory).toString)
      require(cases == Vector("*") || cases.forall(id => all.exists(_.id == id)), "Unknown inventory case")
      all.filter(c => cases == Vector("*") || cases.contains(c.id)).map(c => c.id -> Some(c))
    } else {
      require((row \ "inventory") == JNothing, "Only LP scenarios accept inventory")
      val allowed = kind match {
        case "presolve" => Set("fixed_rows", "singleton_columns", "irreducible")
        case "start" => Set("lp_coordinates", "mip_knapsack")
        case "mip" => Set("knapsack4", "knapsack6")
        case "qp" => Set("4", "12")
        case "factorization" => Set("32", "5000")
      }
      val allowedModes = kind match {
        case "presolve" => Set("off", "full")
        case "start" => Set("cold", "started")
        case "mip" => Set("baseline", "cuts", "strong", "cuts+strong", "parallel", "cuts+parallel", "strong+parallel", "cuts+strong+parallel")
        case "qp" => Set("separable", "factor")
        case "factorization" => Set("full", "packed")
      }
      require(cases.forall(allowed) && modes.forall(allowedModes), s"Invalid $kind case or mode")
      require(if (kind == "qp") backends.forall(Set("cg", "cholesky"))
        else if (kind == "factorization") backends == Vector("lapack") else backends == Vector("auto"), "Invalid workload backend")
      cases.map(_ -> None)
    }
    for ((id, spec) <- specs; backend <- backends; mode <- modes; core <- cores; partition <- partitions; threads <- nativeThreads) yield {
      val fixture = spec.map(c => if ((row \ "heapGiB") == JNothing) c else c.copy(heapGiB = heap))
      Scenario(kind, id, backend, mode, core, partition, warmups, repetitions,
        fixture.map(_.heapGiB).getOrElse(heap), fixture, baseline, executors, executorCores, executorHeap, threads)
    }
  }
}
