package com.github.vbmacher.spark_lp.dsl

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import scala.collection.JavaConverters._
import scala.concurrent.duration._

/**
  * Solves an [[LpProblem]] with a local HiGHS installation exposed by the Python `highspy` package.
  *
  * Each prepared session exports the model to a temporary LP file and starts a dedicated Python
  * process. The caller must install `highspy`; spark-lp neither installs Python packages nor sends
  * the model to executors. Closing the session stops the process and removes its temporary files
  * unless [[LpCommandOptions.retainArtifacts]] is enabled.
  *
  * @param python executable used to start the bridge process.
  * @param artifacts limits and retention policy for temporary files and solver logs.
  * @param startupTimeout maximum time allowed for bridge startup and non-solve requests.
  * @param maxResponseCharacters maximum accepted size of one JSON response from the bridge.
  */
final class HighsPythonAdapter(python: String = "python3", artifacts: LpCommandOptions = LpCommandOptions(),
  startupTimeout: FiniteDuration = 60.seconds, maxResponseCharacters: Int = 32 * 1024 * 1024) extends LpSolverAdapter {
  require(startupTimeout.toNanos > 0 && maxResponseCharacters > 0, "Bridge limits must be positive")
  override val name = "HiGHS/Python"
  override val capabilities = LpSolverCapabilities(mip = true, callbacks = true, duals = true,
    reducedCosts = true, nativeSession = true)

  override def prepare(model: LpModelView, options: LpAdapterOptions): LpAdapterSession = {
    val directory = LpCommandRunner.createWorkspace(artifacts, "spark-lp-highs-")
    var mapping: Option[LpExportMapping] = None
    var process: Option[Process] = None
    try {
      val input = directory.resolve("model.lp")
      mapping = Some(LpExport.lp(model, input))
      val script = directory.resolve("highs_session.py")
      val source = getClass.getResourceAsStream("/com/github/vbmacher/spark_lp/highs_session.py")
      if (source == null) throw new IllegalStateException("HiGHS bridge resource missing")
      try Files.copy(source, script) finally source.close()
      val p = new ProcessBuilder(python, "-u", script.toString, input.toString).directory(directory.toFile)
        .redirectError(directory.resolve("solver.log").toFile).start()
      process = Some(p)
      new HighsSession(p, directory, mapping.get, options)
    } catch {
      case scala.util.control.NonFatal(e) =>
        process.foreach(p => if (p.isAlive) LpCommandRunner.terminate(p))
        LpCommandRunner.discardWorkspace(mapping, directory, artifacts.retainArtifacts)
        throw e
    }
  }

  private final class InterruptedBridge(val reason: String) extends RuntimeException(reason)

  private final class HighsSession(process: Process, directory: Path, identities: LpExportMapping,
    options: LpAdapterOptions) extends LpAdapterSession with LpNativeAccess {
    private val owner = Thread.currentThread()
    private val mapper = new ObjectMapper()
    private val reader = new BufferedReader(new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
    private val writer = new OutputStreamWriter(process.getOutputStream, StandardCharsets.UTF_8)
    private var closed = false
    private var handler: Option[LpNativeEvent => Unit] = None
    private var solving = false
    private val provider = receive(startupTimeout, cancellable = false)
    if (!provider.path("ok").asBoolean()) throw new IllegalStateException("HiGHS bridge did not initialize")
    Seq("primal_feasibility_tolerance" -> options.validation.tolerance,
      "mip_feasibility_tolerance" -> math.min(options.validation.tolerance, options.validation.integralityTolerance))
      .foreach { case (name, tolerance) =>
        setParameter(name, tolerance.toString).left.foreach(e => throw new IllegalArgumentException(e.detail))
      }
    options.onProgress.foreach(f => callback(event => f(event.message)))

    private def check(): Unit = {
      if (Thread.currentThread() ne owner) throw new IllegalStateException("HiGHS session is driver-thread-affine")
      if (closed || !process.isAlive) throw new IllegalStateException("HiGHS session is closed or its process exited")
      if (solving) throw new IllegalStateException("HiGHS session cannot be reentered from a callback")
    }
    override def nativeAccess: Option[LpNativeAccess] = { check(); Some(this) }
    override def mapping: LpExportMapping = { check(); identities }
    private def receive(limit: FiniteDuration, cancellable: Boolean): JsonNode = {
      val started = System.nanoTime()
      val line = new StringBuilder
      var characters = 0
      while (true) {
        if (characters % 1024 == 0 || !reader.ready()) {
          if (Thread.currentThread().isInterrupted || cancellable && options.shouldStop()) {
            LpCommandRunner.terminate(process); throw new InterruptedBridge("UserStop")
          }
          if (System.nanoTime() - started >= limit.toNanos) {
            LpCommandRunner.terminate(process); throw new InterruptedBridge("TimeLimit")
          }
          val log = directory.resolve("solver.log")
          if (Files.exists(log) && Files.size(log) > artifacts.maxLogBytes) throw new IllegalStateException("HiGHS log size limit exceeded")
        }
        if (reader.ready()) {
          val c = reader.read()
          characters += 1
          if (c < 0) throw new IllegalStateException(s"HiGHS bridge exited: ${LpCommandRunner.logTail(directory.resolve("solver.log"))}")
          if (c == '\n') {
            val response = mapper.readTree(line.toString)
            line.clear()
            if (response.has("event")) {
              val fields = response.path("fields").fields().asScala.map(e => e.getKey -> e.getValue.asText()).toMap
              handler.foreach(_(LpNativeEvent(response.path("event").asText(), response.path("message").asText(), fields)))
            } else return response
          } else {
            line.append(c.toChar)
            if (line.length > maxResponseCharacters) throw new IllegalStateException("HiGHS response size limit exceeded")
          }
        } else if (!process.isAlive) throw new IllegalStateException(s"HiGHS bridge exited: ${LpCommandRunner.logTail(directory.resolve("solver.log"))}")
        else Thread.sleep(10)
      }
      throw new IllegalStateException("Unreachable bridge state")
    }
    private def request(operation: String, fields: Map[String, String] = Map.empty,
      solveRequest: Boolean = false): Either[LpUnsupported, JsonNode] = {
      check()
      writer.write(mapper.writeValueAsString((fields + ("op" -> operation)).asJava)); writer.write("\n"); writer.flush()
      solving = true
      try {
        val response = receive(if (solveRequest) options.timeLimit.getOrElse(365.days) else startupTimeout, solveRequest)
        if (response.path("ok").asBoolean()) Right(response)
        else Left(LpUnsupported(operation, response.path("error").asText("HiGHS rejected operation")))
      } finally solving = false
    }
    override def setParameter(name: String, value: String): Either[LpUnsupported, Unit] =
      request("setParameter", Map("name" -> name, "value" -> value)).map(_ => ())
    override def parameter(name: String): Either[LpUnsupported, String] =
      request("parameter", Map("name" -> name)).map(_.path("value").asText())
    override def information(name: String): Either[LpUnsupported, String] =
      request("information", Map("name" -> name)).map(_.path("value").asText())
    override def callback(f: LpNativeEvent => Unit): Either[LpUnsupported, Unit] = {
      check(); handler = Some(f); request("callback").map(_ => ())
    }
    override def readSolution(path: Path): Either[LpUnsupported, Unit] =
      request("readSolution", Map("path" -> path.toAbsolutePath.toString)).map(_ => ())
    override def writeSolution(path: Path): Either[LpUnsupported, Unit] = {
      if (Files.exists(path)) throw new IllegalArgumentException("Solution destination already exists")
      request("writeSolution", Map("path" -> path.toAbsolutePath.toString)).map(_ => ())
    }
    override def solve(): LpAdapterResult = {
      check()
      val diagnostics = Map("provider" -> provider.path("provider").asText(), "version" -> provider.path("version").asText()) ++
        (if (artifacts.retainArtifacts) Map("artifacts" -> directory.toString) else Map.empty[String, String])
      try {
        val response = request("solve", options.timeLimit.map(t => Map("seconds" -> (t.toNanos.toDouble / 1e9).toString)).getOrElse(Map.empty),
          solveRequest = true).fold(e => throw new IllegalStateException(e.detail), identity)
        val state = response.path("status").asText()
        def data(field: String): Map[String, Double] = response.path(field).fields().asScala.map(e => e.getKey -> e.getValue.asDouble()).toMap
        val assignments = if (response.path("values").isNull) None else {
          val values = data("values")
          Some(identities.variables.map(v => LpCandidateValue(v.id, values(v.exportedName))))
        }
        val status = if (state.endsWith("kOptimal") || state.endsWith("kModelEmpty")) LpStatus.Optimal
          else if (state.endsWith("kInfeasible")) LpStatus.Infeasible
          else if (state.endsWith("kUnbounded") && assignments.nonEmpty) LpStatus.Unbounded
          else if (state.endsWith("kUnboundedOrInfeasible") || state.endsWith("kUnbounded")) LpStatus.InfeasibleOrUnbounded
          else if (state.endsWith("kTimeLimit") || state.endsWith("kInterrupt")) LpStatus.Stopped
          else if (state.endsWith("kIterationLimit") || state.endsWith("kSolutionLimit") || state.endsWith("kObjectiveBound") ||
            state.endsWith("kObjectiveTarget")) LpStatus.IterationLimit
          else throw new IllegalStateException(s"Unsupported HiGHS status: $state")
        val duals = if (status != LpStatus.Optimal || response.path("duals").isNull) None else {
          val values = data("duals"); Some(identities.constraints.map(r => r.id -> values(r.exportedName)))
        }
        val costs = if (status != LpStatus.Optimal || response.path("costs").isNull) None else {
          val values = data("costs"); Some(identities.variables.map(v => v.id -> values(v.exportedName)))
        }
        def number(field: String): Option[Double] = if (response.path(field).isNumber) Some(response.path(field).asDouble()) else None
        LpAdapterResult(status, assignments, number("objective"), number("bestBound"), response.path("iterations").asInt(),
          duals, costs, diagnostics ++ Map("nativeStatus" -> state, "processedNodes" -> response.path("nodes").asText()))
      } catch {
        case e: InterruptedBridge => LpAdapterResult(LpStatus.Stopped, diagnostics = diagnostics + ("termination" -> e.reason))
      }
    }
    override def close(): Unit = {
      if (Thread.currentThread() ne owner) throw new IllegalStateException("HiGHS session must be closed on its creating driver thread")
      if (!closed) {
        closed = true
        try {
          if (process.isAlive) LpCommandRunner.terminate(process)
          reader.close(); writer.close()
        } finally LpCommandRunner.discardWorkspace(Some(identities), directory, artifacts.retainArtifacts)
      }
    }
  }
}
