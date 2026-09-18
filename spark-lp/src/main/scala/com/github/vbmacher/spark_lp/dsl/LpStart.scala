package com.github.vbmacher.spark_lp.dsl

import org.apache.spark.rdd.RDD

sealed trait LpStartDisposition
object LpStartDisposition {
  case object Accepted extends LpStartDisposition
  case object Repaired extends LpStartDisposition
  case object Rejected extends LpStartDisposition
  case object Unsupported extends LpStartDisposition
}
final case class LpStartConfig(allowPartial: Boolean = true, repairBounds: Boolean = true,
  requireFeasible: Boolean = false, interiorFloor: Double = 1e-3) {
  require(java.lang.Double.isFinite(interiorFloor) && interiorFloor > 0.0, "Start interior floor must be finite and positive")
}
final case class LpStartSummary(disposition: LpStartDisposition, detail: String, suppliedValues: Long,
  complete: Boolean, feasible: Boolean, used: Boolean = false, seededIncumbent: Boolean = false,
  preparationSeconds: Double = 0.0)

/** Materialized distributed assignment snapshot, owned independently of a source solution. */
final class LpStart private[dsl](private[dsl] val problem: LpProblem,
  private val data: RDD[LpCandidateValue], val config: LpStartConfig) extends AutoCloseable {
  private var closed = false
  def values: RDD[LpCandidateValue] = {
    if (closed) throw new LpModelException("Start is closed")
    data
  }
  override def close(): Unit = if (!closed) { closed = true; data.unpersist(false) }
}
private[dsl] object LpStart {
  def create(problem: LpProblem, values: RDD[LpCandidateValue], config: LpStartConfig): LpStart = {
    val copy = values.map(identity).localCheckpoint()
    try { copy.count(); new LpStart(problem, copy, config) }
    catch { case scala.util.control.NonFatal(e) => copy.unpersist(false); throw e }
  }
}

private[dsl] final class PreparedStart(val values: RDD[((Int, String), Double)],
  val objective: Option[Double], val maxViolation: Double, var summary: LpStartSummary, val config: LpStartConfig) extends AutoCloseable {
  def usable: Boolean = summary.disposition != LpStartDisposition.Rejected && summary.disposition != LpStartDisposition.Unsupported
  def applied(incumbent: Boolean = false): Unit = summary = summary.copy(used = true, seededIncumbent = incumbent || summary.seededIncumbent)
  override def close(): Unit = values.unpersist(false)
}

private[dsl] object LpStartProcessing {
  def prepare(problem: LpProblem, start: LpStart, validation: CandidateValidationConfig): PreparedStart = {
    if (start.problem ne problem) throw new LpModelException("Start belongs to a different model; remap identities explicitly")
    val began = System.nanoTime()
    val source = start.values.map(v => v.variable -> v.value)
    val view = problem.inspect
    val variables = view.variables.map(v => v.id -> v)
    val grouped = variables.cogroup(source)
    val supplied = source.count()
    val missing = grouped.filter(_._2._2.isEmpty).take(1).nonEmpty
    val invalid = grouped.flatMap { case (_, (metadata, candidates)) =>
      val xs = candidates.toVector
      if (metadata.isEmpty && xs.nonEmpty) Some("foreign variable identity")
      else if (xs.size > 1) Some("duplicate assignment")
      else if (xs.exists(v => !java.lang.Double.isFinite(v))) Some("non-finite assignment") else None
    }.take(1).headOption
    val config = start.config
    val joined = variables.join(source)
    val outside = joined.filter { case (_, (v, x)) =>
      val (lower, upper) = VariableCategory.domainBoundsFinite(v.category, v.lower, v.upper)
      x < lower || x > upper
    }.take(1).nonEmpty
    val rejected = invalid.orElse(if (missing && !config.allowPartial) Some("full start is missing variable assignments") else None)
      .orElse(if (outside && (!config.repairBounds || config.requireFeasible)) Some("start violates variable bounds") else None)
    val normalized = if (rejected.nonEmpty) problem.spark.sparkContext.emptyRDD[((Int, String), Double)]
      else joined.map { case (id, (v, x)) =>
        val (lower, upper) = VariableCategory.domainBoundsFinite(v.category, v.lower, v.upper)
        val clipped = math.max(lower, math.min(upper, x))
        val rounded = if (!validation.relaxIntegrality && v.category != Continuous &&
          math.abs(clipped - math.rint(clipped)) <= validation.integralityTolerance) math.rint(clipped) else clipped
        (id.family -> id.key) -> rounded
      }
    val values = normalized.persist()
    try {
      values.count()
      val report = if (rejected.isEmpty && !missing) Some(problem.validateCandidate(values.map { case ((family, key), x) =>
        LpCandidateValue(LpVariableId(family, key), x)
      }, validation)) else None
      val (feasible, objective, violation) = try report.map(r => (r.feasible, r.objectiveValue, r.maxViolation)).getOrElse((false, None, Double.NaN))
        finally report.foreach(_.close())
      val changed = outside || values.map { case ((family, key), x) => LpVariableId(family, key) -> x }
        .join(source).filter { case (_, (a, b)) => a != b }.take(1).nonEmpty
      val rejection = rejected.orElse(if (config.requireFeasible && !feasible) Some("start is not a complete feasible assignment") else None)
      val disposition = if (rejection.nonEmpty) LpStartDisposition.Rejected
        else if (changed) LpStartDisposition.Repaired else LpStartDisposition.Accepted
      val detail = rejection.getOrElse(if (changed) "Bounds clipped or near-integral values rounded; complete points are revalidated before incumbent use"
        else if (feasible) "Complete feasible assignment" else "Tentative primal hint; no incumbent feasibility claim")
      new PreparedStart(values, objective, violation, LpStartSummary(disposition, detail, supplied, !missing, feasible,
        preparationSeconds = (System.nanoTime() - began).toDouble / 1e9), config)
    } catch { case scala.util.control.NonFatal(e) => values.unpersist(false); throw e }
  }
}
