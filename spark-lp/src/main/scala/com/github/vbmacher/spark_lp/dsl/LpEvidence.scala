package com.github.vbmacher.spark_lp.dsl

import org.apache.spark.rdd.RDD

/** Original row, before shifts, slack introduction or duplicate-row elimination. */
final case class EvidenceRow(name: String, group: Option[String], sense: String, rhs: Double)

/** Keyed original variable; coefficients use indices into EvidenceModel.rows. */
final case class EvidenceVariable(name: String, lower: Double, upper: Option[Double],
  cost: Double, coefficients: Map[Int, Double], curvature: Double = 0.0)

/** A materialised original-model snapshot, independent of the solver's transformed matrix. */
final case class EvidenceModel(rows: IndexedSeq[EvidenceRow],
  variables: RDD[((Int, String), EvidenceVariable)], sense: ObjectiveSense)

final case class EvidenceVerification(valid: Boolean, residual: Double, margin: Double)

sealed trait LpEvidence extends AutoCloseable {
  def model: EvidenceModel
  def verify(tolerance: Double = 1e-8): EvidenceVerification
}

/** Farkas convention: A^T y + lowerMultiplier + upperMultiplier = 0,
  * b^T y + l^T lowerMultiplier + u^T upperMultiplier = 1.
  * <= row and upper-bound multipliers are nonpositive; >= and lower are nonnegative.
  * Equality multipliers are unrestricted. All arrays are in original units.
  */
final case class InfeasibilityCertificate(model: EvidenceModel, rows: IndexedSeq[Double],
  bounds: RDD[((Int, String), (Double, Double))]) extends LpEvidence {
  override def verify(tolerance: Double): EvidenceVerification =
    LpEvidenceVerifier.infeasibility(this, tolerance)
  override def close(): Unit = {
    model.variables.unpersist(false)
    bounds.unpersist(false)
  }
}

/** Direction in original units, normalized to an improvement of one. A feasible point is
  * present only for proven unboundedness; a direction alone does not prove primal feasibility.
  */
final case class UnboundedDirection(model: EvidenceModel,
  direction: RDD[((Int, String), Double)],
  point: Option[RDD[((Int, String), Double)]]) extends LpEvidence {
  override def verify(tolerance: Double): EvidenceVerification =
    LpEvidenceVerifier.unbounded(this, tolerance)
  override def close(): Unit = {
    model.variables.unpersist(false)
    direction.unpersist(false)
    point.foreach(_.unpersist(false))
  }
}

/** Checks original coefficients, bounds and objective, without trusting solver residuals/status.
  * `valid` is a numerical witness at the requested absolute tolerance, not an exact-arithmetic proof.
  */
object LpEvidenceVerifier {
  private def checked(v: Double): Double = if (LpExpressionData.finite(v)) math.max(0.0, v) else Double.PositiveInfinity
  private def checkTolerance(t: Double): Unit = require(LpExpressionData.finite(t) && t > 0, "tolerance must be finite and positive")
  private def keysMatch[A: scala.reflect.ClassTag, B: scala.reflect.ClassTag](a: RDD[((Int, String), A)], b: RDD[((Int, String), B)]): Boolean =
    a.mapValues(_ => 1).cogroup(b.mapValues(_ => 1)).filter { case (_, (x, y)) => x.size != 1 || y.size != 1 }.take(1).isEmpty

  def infeasibility(proof: InfeasibilityCertificate, tolerance: Double = 1e-8): EvidenceVerification = {
    checkTolerance(tolerance)
    val model = proof.model
    val y = proof.rows
    if (y.size != model.rows.size || !y.forall(LpExpressionData.finite) || !keysMatch(model.variables, proof.bounds))
      return EvidenceVerification(false, Double.PositiveInfinity, Double.NaN)
    val rowError = model.rows.zip(y).map { case (r, v) =>
      r.sense match { case "<=" => checked(v); case ">=" => checked(-v); case _ => 0.0 }
    }.foldLeft(0.0)(math.max)
    val terms = model.variables.join(proof.bounds).map { case (_, (v, (lower, upper))) =>
      val ay = v.coefficients.iterator.map { case (r, a) => a * y(r) }.sum
      val sign = math.max(checked(-lower), checked(upper))
      val absent = math.max(if (v.lower.isNegInfinity) math.abs(lower) else 0.0,
        if (v.upper.isEmpty) math.abs(upper) else 0.0)
      val err = Seq(sign, absent, math.abs(ay + lower + upper)).map(checked).max
      val margin = (if (v.lower.isNegInfinity) 0.0 else lower * v.lower) +
        v.upper.map(_ * upper).getOrElse(0.0)
      (err, margin)
    }.fold((0.0, 0.0)) { case ((e, m), (f, n)) => (math.max(e, f), m + n) }
    val margin = terms._2 + model.rows.zip(y).map { case (r, v) => r.rhs * v }.sum
    val residual = math.max(math.max(rowError, terms._1), checked(math.abs(margin - 1.0)))
    EvidenceVerification(residual <= tolerance && margin > tolerance, residual, margin)
  }

  def unbounded(proof: UnboundedDirection, tolerance: Double = 1e-8): EvidenceVerification = {
    checkTolerance(tolerance)
    val model = proof.model
    def violations(values: RDD[((Int, String), Double)], ray: Boolean): (Double, Double) = {
      if (!keysMatch(model.variables, values)) return (Double.PositiveInfinity, Double.NaN)
      val joined = model.variables.join(values)
      val scalar = joined.map { case (_, (v, x)) =>
        val lo = if (v.lower.isNegInfinity) 0.0 else (if (ray) -x else v.lower - x)
        val hi = v.upper.map(u => if (ray) x else x - u).getOrElse(0.0)
        val curvatureError = if (ray) checked(math.abs(v.curvature * x)) else 0.0
        (if (LpExpressionData.finite(x)) math.max(curvatureError, math.max(checked(lo), checked(hi)))
          else Double.PositiveInfinity, v.cost * x)
      }.fold((0.0, 0.0)) { case ((a, b), (c, d)) => (math.max(a, c), b + d) }
      val activities = joined.flatMap { case (_, (v, x)) =>
        v.coefficients.map { case (r, a) => (r, a * x) }
      }.reduceByKey(_ + _).collectAsMap()
      val rowError = model.rows.zipWithIndex.map { case (r, i) =>
        val d = activities.getOrElse(i, 0.0) - (if (ray) 0.0 else r.rhs)
        checked(r.sense match { case "<=" => d; case ">=" => -d; case _ => math.abs(d) })
      }.foldLeft(0.0)(math.max)
      (math.max(scalar._1, rowError), scalar._2)
    }
    val ray = violations(proof.direction, ray = true)
    val margin = (if (model.sense == Minimize) -1.0 else 1.0) * ray._2
    val pointError = proof.point.map(violations(_, ray = false)._1).getOrElse(Double.PositiveInfinity)
    val residual = math.max(math.max(ray._1, pointError), checked(math.abs(margin - 1.0)))
    EvidenceVerification(residual <= tolerance && margin > tolerance, residual, margin)
  }
}
