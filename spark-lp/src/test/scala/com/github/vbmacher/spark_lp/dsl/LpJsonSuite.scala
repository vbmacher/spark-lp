package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.CandidateInfo
import com.github.vbmacher.spark_lp.dsl.implicits._
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.SparkSession
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Path, Paths, StandardWatchEventKinds}
import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

private object LpJsonPublicationSignals {
  def awaitFile(path: Path, timeout: FiniteDuration): Unit = {
    val watcher = path.getFileSystem.newWatchService()
    try {
      path.getParent.register(watcher, StandardWatchEventKinds.ENTRY_CREATE)
      val deadline = timeout.fromNow
      while (!Files.exists(path) && deadline.hasTimeLeft()) {
        val key = watcher.poll(deadline.timeLeft.toNanos.max(0L), TimeUnit.NANOSECONDS)
        if (key != null) {
          key.pollEvents()
          key.reset()
        }
      }
      require(Files.exists(path), s"Timed out waiting for $path")
    } finally watcher.close()
  }
}

class LpJsonSuite extends AnyFunSuite with DataFrameSuiteBase {
  test("JSON preserves model algebra and separately marked solution metadata") {
    implicit val ss: SparkSession = spark
    val localSpark = spark
    import localSpark.implicits._
    val model = LpProblem("json \"model\"", Maximize)
    val xs = model.variablesOf("items", Seq(("a", 1), ("b", 2)).toDS(), (x: (String, Int)) => x, category = Binary)
    val free = model.variable("free", Double.NegativeInfinity)
    val fixed = model.variable("fixed", 2.0, Some(2.0))
    model += xs.sum + free + fixed + 0.1234567890123456
    model += (xs.sum <= 1.5)
    model += (free === -1.0)

    val original = model.solve()
    val directory = Files.createTempDirectory("spark-lp-json-").resolve("model").toString
    try {
      LpJson.write(model, directory, Some(LpSolutionData.fromSolution(original)))

      intercept[LpModelException](LpJson.write(model, directory))
      val document = LpJson.read(directory)

      assert(!document.solutionMetadataVerified)
      assert(document.model.name == model.name)
      assert(document.model.objective.constant == 0.1234567890123456)
      assert(document.solution.get.residuals.nonEmpty)
      val imported = document.model.toProblem()

      val result = imported.model.solve()
      try {
        assert(math.abs(result.objectiveValue - 2.1234567890123456) < 1e-6)
        assert(result.status == LpStatus.Optimal)

        val report = imported.model.validateCandidate(document.solution.get.values.get)
        try {
          assert(report.feasible)
        } finally report.close()
      } finally result.close()
      LpJson.write(model, directory, overwrite = true)

      assert(LpJson.read(directory).solution.isEmpty)
    } finally {
      original.close()
      val path = new org.apache.hadoop.fs.Path(directory)
      path.getFileSystem(spark.sparkContext.hadoopConfiguration).delete(path.getParent, true)
    }
  }

  test("invalid schema and malformed categories are rejected by the JSON reader") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("schema")
    model.variable("x")
    val directory = Files.createTempDirectory("spark-lp-json-invalid-").resolve("model").toString
    try {
      LpJson.write(model, directory)
      val header = Files.list(java.nio.file.Paths.get(directory, "header"))
      val file = try header.iterator().asScala.find(_.getFileName.toString.startsWith("part-")).get finally header.close()
      val original = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8)
      // Replace through Hadoop so its checksum sidecar stays consistent.
      def replace(text: String): Unit = {
        val path = new org.apache.hadoop.fs.Path(file.toString)
        val out = path.getFileSystem(spark.sparkContext.hadoopConfiguration).create(path, true)
        try out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) finally out.close()
      }
      replace(original.replace("\"schemaVersion\":1", "\"schemaVersion\":99"))

      intercept[LpModelException](LpJson.read(directory))
      replace(original.replace("\"Continuous\"", "\"InvalidCategory\""))

      intercept[LpModelException](LpJson.read(directory))
    } finally {
      val path = new org.apache.hadoop.fs.Path(directory)
      path.getFileSystem(spark.sparkContext.hadoopConfiguration).delete(path.getParent, true)
    }
  }

  test("no-overwrite publication preserves a destination created during the write") {
    implicit val ss: SparkSession = spark
    implicit val executionContext: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    val model = LpProblem("concurrent publication")
    val x = model.variable("x")
    val root = Files.createTempDirectory("spark-lp-json-race-")
    val directory = root.resolve("model")
    val signalDirectory = root.toString
    val writerIsStaging = root.resolve("writer-is-staging")
    val allowPublication = root.resolve("allow-publication")
    val markerBytes = Array[Byte](1)
    val timeout = 30.seconds
    val jobGroup = root.getFileName.toString

    // The local Spark task writes solution values after staging starts and before publication.
    val stagedValues = model.candidateValues(Seq(x -> 0.0)).map { value =>
      val signals = Paths.get(signalDirectory)
      Files.write(signals.resolve("writer-is-staging"), Array.emptyByteArray)
      LpJsonPublicationSignals.awaitFile(signals.resolve("allow-publication"), timeout)
      value
    }
    val candidate = CandidateInfo(available = true, feasible = true, iteration = None)
    val solution = LpSolutionData(LpStatus.Optimal, Some(0.0), candidate, Some(stagedValues))
    val context = sc

    val writing = Future {
      context.setJobGroup(jobGroup, "JSON publication race", interruptOnCancel = true)
      try Try(LpJson.write(model, directory.toString, Some(solution)))
      finally context.clearJobGroup()
    }
    try {
      // A competing writer creates the destination after the first writer has passed its initial check.
      LpJsonPublicationSignals.awaitFile(writerIsStaging, timeout)
      Files.createDirectory(directory)
      val marker = Files.write(directory.resolve("concurrent-marker"), markerBytes)
      Files.write(allowPublication, Array.emptyByteArray)
      val result = Await.result(writing, timeout)

      result match {
        case Failure(error: LpModelException) =>
          assert(error.getMessage.contains("JSON destination already exists"))
        case Failure(error) => fail("Expected the existing destination to block publication", error)
        case Success(_) => assert(false, "The writer published over the competing destination")
      }
      assert(Files.readAllBytes(marker).sameElements(markerBytes))
    } finally {
      try {
        Files.write(allowPublication, Array.emptyByteArray)
        Await.ready(writing, timeout)
      } catch {
        case _: scala.concurrent.TimeoutException =>
          context.cancelJobGroup(jobGroup)
          Await.ready(writing, timeout)
      } finally {
        val path = new org.apache.hadoop.fs.Path(root.toString)
        path.getFileSystem(spark.sparkContext.hadoopConfiguration).delete(path, true)
      }
    }
  }
}
