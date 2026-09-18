package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.Numerics
import org.apache.spark.rdd.RDD

final case class LpCandidateValue(variable: LpVariableId, value: Double)
final case class CandidateValidationConfig(tolerance: Double = 1e-8,
  integralityTolerance: Double = 1e-6, relaxIntegrality: Boolean = false, sosZeroTolerance: Double = 1e-6) {
  require(Seq(tolerance, integralityTolerance).forall(t => Numerics.isFinite(t) && t > 0.0),
    "Candidate tolerances must be finite and positive")
  require(java.lang.Double.isFinite(sosZeroTolerance) && sosZeroTolerance >= 0.0, "SOS zero tolerance must be finite and nonnegative")
  require(integralityTolerance < 0.5, "Integrality tolerance must be below 0.5")
}
final case class LpCandidateViolation(variable: Option[LpVariableId], constraint: Option[LpConstraintId],
  kind: String, magnitude: Double, accepted: Boolean)

/** Validation never optimizes. This object owns only its materialized violation records. */
final class LpCandidateReport private[dsl](val feasible: Boolean, val objectiveValue: Option[Double],
  val sense: ObjectiveSense, val relaxationOnly: Boolean, val maxViolation: Double,
  val violations: RDD[LpCandidateViolation]) extends AutoCloseable {
  override def close(): Unit = violations.unpersist(false)
}

private[dsl] object LpCandidateValidation {
  def validate(model: LpProblem, values: RDD[LpCandidateValue], config: CandidateValidationConfig): LpCandidateReport = {
    val view = model.inspect
    view.statistics() // validates original identities and row sources, never solves/presolves
    val source = values.map(v => v.variable -> v.value).persist()
    try {
      val variables = view.variables.map(v => v.id -> v)
      val paired = variables.cogroup(source)
      val malformed = paired.flatMap { case (id, (metadata, candidates)) =>
        val vs = metadata.toVector
        val xs = candidates.toVector
        val kind = if (vs.isEmpty) Some("foreign") else if (xs.isEmpty) Some("missing")
          else if (xs.size != 1) Some("duplicate") else if (!Numerics.isFinite(xs.head)) Some("non-finite") else None
        kind.map(k => LpCandidateViolation(Some(id), None, k, Double.PositiveInfinity, false))
      }
      val bad = malformed.take(1).nonEmpty
      val joined = variables.join(source)
      val limits = joined.flatMap { case (id, (v, x)) =>
        val (lower, upper) = VariableCategory.domainBounds(v.category, v.lower, v.upper)
        val bound = math.max(0.0, math.max(if (lower.isNegInfinity) 0.0 else lower - x, upper.map(x - _).getOrElse(0.0)))
        val integral = if (config.relaxIntegrality || v.category == Continuous) 0.0 else LpIntegrality.fractionality(x)
        Seq(LpCandidateViolation(Some(id), None, "bound", bound, bound <= config.tolerance),
          LpCandidateViolation(Some(id), None, "integrality", integral, integral <= config.integralityTolerance))
      }
      val activities = view.coefficients.map(c => c.variable -> (c.row, c.value)).join(source)
        .map { case (_, ((row, coefficient), value)) => row -> (coefficient * value) }.reduceByKey(_ + _)
      val rows = view.constraints.map(c => c.id -> c).leftOuterJoin(activities).map { case (id, (row, activity)) =>
        val delta = activity.getOrElse(0.0) - row.rhs
        val violation = if (!Numerics.isFinite(delta)) Double.PositiveInfinity else LpSense.violation(row.sense, delta)
        LpCandidateViolation(None, Some(id), "constraint", violation, violation <= config.tolerance)
      }
      val groups = view.sosGroups
      val membership = values.sparkContext.parallelize(groups.zipWithIndex.flatMap { case (group, gi) =>
        group.members.zipWithIndex.map { case (member, i) => member.variable -> (gi, i) }
      })
      val active = membership.join(source).filter { case (_, (_, value)) => math.abs(value) > config.sosZeroTolerance }
        .map { case (_, ((group, index), _)) => group -> index }.groupByKey().mapValues(_.toVector.sorted)
      val sos = values.sparkContext.parallelize(groups.zipWithIndex.map { case (group, i) => i -> group })
        .leftOuterJoin(active).map { case (_, (group, indices)) =>
          val accepted = config.relaxIntegrality || LpSos.valid(group, indices.getOrElse(Vector.empty))
          LpCandidateViolation(None, None, s"sos:${group.name}", if (accepted) 0.0 else 1.0, accepted)
        }
      val records = (if (bad) malformed else limits.union(rows).union(sos)).persist()
      try {
        val maximum = records.map(_.magnitude).fold(0.0)(math.max)
        val feasible = !bad && records.filter(!_.accepted).take(1).isEmpty
        val objective = if (bad) None else {
          def sum(coefficients: RDD[LpCoefficient], squared: Boolean = false): Double =
            coefficients.map(c => c.variable -> c.value).join(source).values
              .map { case (c, x) => c * x * (if (squared) x else 1.0) }.fold(0.0)(_ + _)
          val linear = sum(view.objectiveCoefficients) + view.objectiveConstant
          val diagonal = 0.5 * sum(view.diagonalCoefficients, squared = true)
          val factors = view.quadraticFactors.map { f =>
            val value = sum(f.coefficients) + f.constant
            f.weight * value * value
          }.sum
          val total = linear + diagonal + factors
          if (Numerics.isFinite(total)) Some(total) else None
        }
        new LpCandidateReport(feasible, objective, model.sense, config.relaxIntegrality, maximum, records)
      } catch {
        case scala.util.control.NonFatal(e) => records.unpersist(false); throw e
      }
    } finally source.unpersist(false)
  }
}
