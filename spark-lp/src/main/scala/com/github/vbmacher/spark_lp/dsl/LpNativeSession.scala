package com.github.vbmacher.spark_lp.dsl

import java.nio.file.Path

final case class LpUnsupported(operation: String, detail: String)
final case class LpNativeEvent(kind: String, message: String, fields: Map[String, String] = Map.empty)

/** Adapter-side driver handle. Structural model edits are deliberately absent: identities stay stable. */
trait LpNativeAccess {
  def mapping: LpExportMapping
  def setParameter(name: String, value: String): Either[LpUnsupported, Unit]
  def parameter(name: String): Either[LpUnsupported, String]
  def information(name: String): Either[LpUnsupported, String]
  def callback(handler: LpNativeEvent => Unit): Either[LpUnsupported, Unit] =
    Left(LpUnsupported("callback", "Backend does not expose callbacks"))
  def readSolution(path: Path): Either[LpUnsupported, Unit] =
    Left(LpUnsupported("readSolution", "Backend does not read solution/start files"))
  def writeSolution(path: Path): Either[LpUnsupported, Unit] =
    Left(LpUnsupported("writeSolution", "Backend does not write solution files"))
}

/** A thread-affine prepared model. The session owns every returned solution until close. */
final class LpNativeSession private[dsl](problem: LpProblem, adapter: LpSolverAdapter,
  options: LpAdapterOptions, prepared: LpAdapterSession, access: LpNativeAccess,
  release: () => Unit) extends AutoCloseable {
  private val owner = Thread.currentThread()
  private var closed = false
  private var busy = false
  private var revision = 0L
  private var current: Option[LpSolution] = None
  private val results = scala.collection.mutable.ArrayBuffer.empty[LpSolution]
  private def check(): Unit = {
    if (Thread.currentThread() ne owner) throw new IllegalStateException("Native session must be used on its creating driver thread")
    if (closed) throw new IllegalStateException("Native session is closed")
    if (busy) throw new IllegalStateException("Native session operations cannot be reentered from a callback")
  }
  private def invalidate(): Unit = { revision += 1; current = None }
  def modelRevision: Long = { check(); revision }
  def mapping: LpExportMapping = { check(); access.mapping }
  def latestSolution: Option[LpSolution] = { check(); current }
  def isCurrent(solution: LpSolution): Boolean = { check(); current.exists(_ eq solution) }
  def setParameter(name: String, value: String): Either[LpUnsupported, Unit] = {
    check()
    val result = access.setParameter(name, value)
    if (result.isRight) invalidate()
    result
  }
  def parameter(name: String): Either[LpUnsupported, String] = { check(); access.parameter(name) }
  def information(name: String): Either[LpUnsupported, String] = { check(); access.information(name) }
  def callback(handler: LpNativeEvent => Unit): Either[LpUnsupported, Unit] = {
    check(); access.callback(handler)
  }
  def readSolution(path: Path): Either[LpUnsupported, Unit] = {
    check()
    val result = access.readSolution(path)
    if (result.isRight) invalidate()
    result
  }
  def writeSolution(path: Path): Either[LpUnsupported, Unit] = {
    check()
    if (current.isEmpty) throw new IllegalStateException("Solve the current native session before writing its solution")
    access.writeSolution(path)
  }
  def solve(): LpSolution = {
    check(); invalidate(); busy = true
    try {
      val result = LpAdapterSolve.normalize(problem, adapter, options, prepared.solve())
      current = Some(result); results += result; result
    } catch {
      case scala.util.control.NonFatal(e) =>
        busy = false
        try close() catch { case scala.util.control.NonFatal(cleanup) => e.addSuppressed(cleanup) }
        throw e
    } finally busy = false
  }
  override def close(): Unit = {
    if (Thread.currentThread() ne owner) throw new IllegalStateException("Native session must be closed on its creating driver thread")
    if (busy) throw new IllegalStateException("Native session cannot be closed inside a callback")
    if (!closed) {
      closed = true; current = None
      try prepared.close() finally try results.foreach(_.close()) finally release()
    }
  }
}

private[dsl] object LpNativeSession {
  def open(problem: LpProblem, adapter: LpSolverAdapter, options: LpAdapterOptions,
    release: () => Unit): Either[LpUnsupported, LpNativeSession] = {
    var prepared: Option[LpAdapterSession] = None
    try {
      LpAdapterSolve.check(problem.inspect, adapter.capabilities, options)
      prepared = Some(adapter.prepare(problem.inspect, options))
      prepared.get.nativeAccess match {
        case Some(access) => Right(new LpNativeSession(problem, adapter, options, prepared.get, access, release))
        case None =>
          try prepared.get.close() finally release()
          Left(LpUnsupported("nativeSession", s"${adapter.name} did not provide native access"))
      }
    } catch {
      case scala.util.control.NonFatal(e) =>
        try prepared.foreach(_.close()) catch { case scala.util.control.NonFatal(cleanup) => e.addSuppressed(cleanup) }
        release(); throw e
    }
  }
}
