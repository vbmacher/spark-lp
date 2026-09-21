package com.github.vbmacher.spark_lp.dsl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._

/**
  * Filesystem and output limits for a command-line solver process.
  *
  * @param retainArtifacts preserve the temporary model, solution, and log files after closing.
  * @param temporaryRoot directory in which workspaces are created; the system temporary directory
  *                      is used when absent.
  * @param maxLogBytes maximum solver-log size before the process is terminated as invalid.
  */
final case class LpCommandOptions(
  retainArtifacts: Boolean = false,
  temporaryRoot: Option[Path] = None,
  maxLogBytes: Long = 10L * 1024 * 1024
) {
  require(maxLogBytes > 0, "Log size limit must be positive")
}

/**
  * Termination details for one command-line solver process.
  *
  * @param exitCode process exit code, absent when spark-lp terminated the process.
  * @param stopped true when the adapter's cooperative stop callback requested termination.
  * @param timedOut true when the adapter time limit expired.
  * @param log path containing merged standard output and standard error.
  * @param elapsedSeconds wall-clock process duration.
  */
final case class LpCommandOutcome(
  exitCode: Option[Int],
  stopped: Boolean,
  timedOut: Boolean,
  log: Path,
  elapsedSeconds: Double
)

/** Argument-safe process execution. No shell expansion or global environment mutation. */
object LpCommandRunner {

  def run(arguments: Seq[String], directory: Path, options: LpAdapterOptions,
    commandOptions: LpCommandOptions = LpCommandOptions()): LpCommandOutcome = {
    require(arguments.nonEmpty && arguments.forall(_ != null), "Command arguments must be nonempty and non-null")
    val log = directory.resolve("solver.log")
    val started = System.nanoTime()

    def elapsed: Long = System.nanoTime() - started

    if (options.shouldStop()) return LpCommandOutcome(None, stopped = true, timedOut = false, log, 0.0)
    val process = new ProcessBuilder(arguments.asJava).directory(directory.toFile)
      .redirectErrorStream(true).redirectOutput(log.toFile).start()
    var stopped = false
    var timedOut = false
    try {
      var finished = false
      while (!finished && !stopped && !timedOut) {
        finished = process.waitFor(50, TimeUnit.MILLISECONDS)
        stopped = options.shouldStop()
        timedOut = options.timeLimit.exists(_.toNanos <= elapsed)
        if (Files.size(log) > commandOptions.maxLogBytes)
          throw new IllegalStateException(s"Solver log exceeds ${commandOptions.maxLogBytes} bytes: $log")
      }
      if (stopped || timedOut) terminate(process)
      LpCommandOutcome(if (stopped || timedOut) None else Some(process.exitValue()), stopped, timedOut,
        log, elapsed.toDouble / 1e9)
    } finally if (process.isAlive) terminate(process)
  }

  private[dsl] def terminate(process: Process): Unit = {
    // Java 11 is the supported runtime. Stop descendants before the parent to avoid orphaned workers.
    val descendants = process.descendants()
    val children = try descendants.iterator().asScala.toVector finally descendants.close()
    children.reverse.foreach(_.destroy())
    process.destroy()
    if (!process.waitFor(200, TimeUnit.MILLISECONDS)) process.destroyForcibly()
    children.reverse.filter(_.isAlive).foreach(_.destroyForcibly())
    process.waitFor()
  }

  def logTail(path: Path, maxBytes: Int = 4096): String = {
    require(maxBytes > 0, "Log tail size must be positive")
    if (!Files.exists(path)) "" else {
      val file = new java.io.RandomAccessFile(path.toFile, "r")
      try {
        val length = math.min(file.length(), maxBytes.toLong).toInt
        file.seek(file.length() - length)
        val bytes = new Array[Byte](length);
        file.readFully(bytes)
        new String(bytes, StandardCharsets.UTF_8)
      } finally file.close()
    }
  }

  private[dsl] def removeDirectory(path: Path): Unit = {
    val stream = Files.walk(path)
    try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
    finally stream.close()
  }

  /** Create a fresh adapter workspace directory, honouring an optional caller-provided root. */
  private[dsl] def createWorkspace(options: LpCommandOptions, prefix: String): Path =
    options.temporaryRoot.map(Files.createTempDirectory(_, prefix)).getOrElse(Files.createTempDirectory(prefix))

  /** Close the export mapping (if present), then delete the workspace unless the caller retains artifacts. */
  private[dsl] def discardWorkspace(mapping: Option[LpExportMapping], directory: Path, retainArtifacts: Boolean): Unit =
    try mapping.foreach(_.close()) finally if (!retainArtifacts) removeDirectory(directory)
}

/** Local LP-file adapter template. Implement command construction and parsing using public mappings.
  * Export streams sorted model records to disk; any parser-side collection must obey explicit limits.
  */
abstract class LpCommandAdapter(val commandOptions: LpCommandOptions = LpCommandOptions()) extends LpSolverAdapter {
  protected def command(modelFile: Path, solutionFile: Path, options: LpAdapterOptions): Seq[String]

  protected def parse(solutionFile: Path, mapping: LpExportMapping, options: LpAdapterOptions): LpAdapterResult

  final override def prepare(model: LpModelView, options: LpAdapterOptions): LpAdapterSession = {
    if (model.hasQuadraticObjective) throw new IllegalArgumentException("LP command adapter cannot export a quadratic objective")
    val directory = LpCommandRunner.createWorkspace(commandOptions, "spark-lp-adapter-")
    var mapping: Option[LpExportMapping] = None
    try {
      val input = directory.resolve("model.lp")
      val output = directory.resolve("solution.txt")
      mapping = Some(LpExport.lp(model, input))
      val identities = mapping.get
      new LpAdapterSession {
        private var closed = false
        private var solved = false

        override def solve(): LpAdapterResult = {
          if (closed || solved) throw new IllegalStateException("Command session is closed or already solved")
          solved = true
          val outcome = LpCommandRunner.run(command(input, output, options), directory, options, commandOptions)
          val diagnostics = Map("logTail" -> LpCommandRunner.logTail(outcome.log),
            "elapsedSeconds" -> outcome.elapsedSeconds.toString) ++
            (if (commandOptions.retainArtifacts) Map("artifacts" -> directory.toString) else Map.empty[String, String])
          if (outcome.stopped || outcome.timedOut)
            LpAdapterResult(LpStatus.Stopped, diagnostics = diagnostics +
              ("termination" -> (if (outcome.timedOut) "TimeLimit" else "UserStop")))
          else if (!outcome.exitCode.contains(0))
            throw new IllegalStateException(s"Solver exited with ${outcome.exitCode}: ${diagnostics("logTail")}; artifacts: $directory")
          else {
            if (!Files.isRegularFile(output)) throw new IllegalStateException("Solver did not create a solution file")
            val result = parse(output, identities, options)
            result.copy(diagnostics = result.diagnostics ++ diagnostics)
          }
        }

        override def close(): Unit = if (!closed) {
          closed = true
          LpCommandRunner.discardWorkspace(Some(identities), directory, commandOptions.retainArtifacts)
        }
      }
    } catch {
      case scala.util.control.NonFatal(e) =>
        LpCommandRunner.discardWorkspace(mapping, directory, commandOptions.retainArtifacts)
        throw e
    }
  }
}
