package com.github.vbmacher.spark_lp.dsl

import org.apache.spark.rdd.RDD

sealed trait LpStartDisposition

object LpStartDisposition {
  case object Accepted extends LpStartDisposition

  case object Repaired extends LpStartDisposition

  case object Rejected extends LpStartDisposition

  case object Unsupported extends LpStartDisposition
}

/**
  * Validation and repair policy for a supplied starting assignment.
  *
  * @param allowPartial fill missing variables from model bounds when true.
  * @param repairBounds clamp supplied values into declared bounds when true.
  * @param requireFeasible reject a completed assignment that violates the original constraints.
  * @param interiorFloor minimum positive solver-coordinate value used to place a start in the
  *                      interior of nonnegative bounds.
  */
final case class LpStartConfig(
  allowPartial: Boolean = true,
  repairBounds: Boolean = true,
  requireFeasible: Boolean = false,
  interiorFloor: Double = 1e-3
) {
  require(java.lang.Double.isFinite(interiorFloor) && interiorFloor > 0.0, "Start interior floor must be finite and positive")
}

/**
  * Outcome of preparing and applying starting values.
  *
  * @param disposition whether the start was accepted, repaired, rejected, or unsupported.
  * @param detail human-readable reason for the disposition.
  * @param suppliedValues number of candidate records supplied by the caller.
  * @param complete true when every model variable received a value after optional repair.
  * @param feasible true when the prepared values satisfy the original model.
  * @param used true when the numerical solver actually consumed the prepared start.
  * @param seededIncumbent true when the start supplied the initial mixed-integer incumbent.
  * @param preparationSeconds wall-clock time spent validating and transforming the start.
  */
final case class LpStartSummary(
  disposition: LpStartDisposition,
  detail: String,
  suppliedValues: Long,
  complete: Boolean,
  feasible: Boolean,
  used: Boolean = false,
  seededIncumbent: Boolean = false,
  preparationSeconds: Double = 0.0
)

/**
  * Materialized starting assignment for a later solve of the same model.
  *
  * The snapshot owns its persisted Spark data independently of any source [[LpSolution]]. It may be
  * reused across solves and must be closed when no longer needed.
  */
final class LpStart private[dsl](private[dsl] val problem: LpProblem,
  private val data: RDD[LpCandidateValue], val config: LpStartConfig) extends AutoCloseable {
  private var closed = false

  def values: RDD[LpCandidateValue] = {
    if (closed) throw new LpModelException("Start is closed")
    data
  }

  override def close(): Unit = if (!closed) {
    closed = true; data.unpersist(false)
  }
}

private[dsl] object LpStart {
  def create(problem: LpProblem, values: RDD[LpCandidateValue], config: LpStartConfig): LpStart = {
    val copy = values.map(identity).localCheckpoint()
    try {
      copy.count(); new LpStart(problem, copy, config)
    }
    catch {
      case scala.util.control.NonFatal(e) => copy.unpersist(false); throw e
    }
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
        LpIntegrality.isIntegral(clipped, validation.integralityTolerance)) math.rint(clipped) else clipped
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
    } catch {
      case scala.util.control.NonFatal(e) => values.unpersist(false); throw e
    }
  }
}
