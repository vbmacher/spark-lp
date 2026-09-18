package com.github.vbmacher.spark_lp.dsl

import java.math.{BigDecimal => Decimal, MathContext, RoundingMode}
import org.apache.spark.rdd.RDD

final case class BoundInferenceConfig(maxPasses: Int = 8, maxLocalChanges: Int = 100000, tolerance: Double = 1e-10) {
  require(java.lang.Double.isFinite(tolerance) && tolerance >= 0.0, "Bound inference tolerance must be finite and nonnegative")
  require(maxPasses > 0 && maxLocalChanges > 0 && maxLocalChanges < Int.MaxValue,
    "Bound inference requires positive bounded work limits")
}
private[dsl] final class BoundPropagationResult(val variables: RDD[LpExpandedVariable],
  val passes: Int, val contradiction: Option[String]) extends AutoCloseable {
  override def close(): Unit = variables.unpersist(false)
}

/** Exact binary-double products/sums, outward division, and category-aware interval propagation. */
private[dsl] object LpBoundPropagation extends Serializable {
  private case class Interval(lower: Option[Decimal], upper: Option[Decimal])
  private case class Totals(lower: Decimal, lowerInfinite: Long, upper: Decimal, upperInfinite: Long, nonzeros: Long) {
    def +(other: Totals): Totals = Totals(lower.add(other.lower), lowerInfinite + other.lowerInfinite,
      upper.add(other.upper), upperInfinite + other.upperInfinite, nonzeros + other.nonzeros)
    def without(own: Interval): Interval = Interval(
      if (lowerInfinite - (if (own.lower.isEmpty) 1 else 0) == 0) Some(lower.subtract(own.lower.getOrElse(Decimal.ZERO))) else None,
      if (upperInfinite - (if (own.upper.isEmpty) 1 else 0) == 0) Some(upper.subtract(own.upper.getOrElse(Decimal.ZERO))) else None)
  }
  private def decimal(value: Double): Decimal = new Decimal(value)
  private def product(a: Double, v: LpExpandedVariable): Interval = {
    val coefficient = decimal(a)
    val lower = if (v.lower.isNegInfinity) None else Some(decimal(v.lower).multiply(coefficient))
    val upper = v.upper.map(u => decimal(u).multiply(coefficient))
    if (a >= 0) Interval(lower, upper) else Interval(upper, lower)
  }
  private def rounded(value: Decimal, coefficient: Double, lower: Boolean, integral: Boolean): Double = {
    val mode = if (lower) RoundingMode.FLOOR else RoundingMode.CEILING
    val quotient = value.divide(decimal(coefficient), new MathContext(34, mode))
    if (integral) {
      val result = quotient.setScale(0, if (lower) RoundingMode.CEILING else RoundingMode.FLOOR).doubleValue()
      if (lower && result.isPosInfinity) Double.MaxValue
      else if (!lower && result.isNegInfinity) -Double.MaxValue
      else result
    }
    else {
      val nearest = quotient.doubleValue()
      if (lower && nearest.isPosInfinity) Double.MaxValue
      else if (!lower && nearest.isNegInfinity) -Double.MaxValue
      else if (!java.lang.Double.isFinite(nearest)) nearest
      else if (decimal(nearest).compareTo(quotient) == 0) nearest
      else if (lower) java.lang.Math.nextAfter(nearest, Double.NegativeInfinity)
      else java.lang.Math.nextAfter(nearest, Double.PositiveInfinity)
    }
  }
  private def domain(v: LpExpandedVariable): LpExpandedVariable = {
    if (v.category == Continuous) v
    else {
      val (lower, upper) = VariableCategory.domainBounds(v.category, v.lower, v.upper)
      v.copy(lower = math.ceil(lower), upper = upper.map(math.floor))
    }
  }

  def run(view: LpModelView, config: BoundInferenceConfig): BoundPropagationResult = {
    view.statistics()
    val matrix = view.coefficients.map(c => (c.variable, (c.row, c.value))).persist()
    val rows = view.constraints.map(r => r.id -> r).persist()
    var variables = view.variables.map(v => v.id -> domain(v)).persist()
    var passes = 0
    var changed = true
    var contradiction: Option[String] = None
    try {
      variables.count()
      while (changed && passes < config.maxPasses && contradiction.isEmpty) {
        passes += 1
        val entries = matrix.join(variables).map { case (id, ((row, a), v)) =>
          (row, (id, a, v.category, product(a, v)))
        }.persist()
        try {
          val totals = entries.mapValues { case (_, _, _, i) => Totals(i.lower.getOrElse(Decimal.ZERO),
            if (i.lower.isEmpty) 1 else 0, i.upper.getOrElse(Decimal.ZERO), if (i.upper.isEmpty) 1 else 0, 1L) }.reduceByKey(_ + _)
          val boundedRows = rows.leftOuterJoin(totals).mapValues { case (row, t) =>
            (row, t.getOrElse(Totals(Decimal.ZERO, 0, Decimal.ZERO, 0, 0L)))
          }.persist()
          try {
            val impossible = boundedRows.values.filter { case (row, total) =>
              val rhs = decimal(row.rhs)
              val allowance = decimal(config.tolerance).multiply(Decimal.ONE.add(rhs.abs()))
              (row.sense != ">=" && total.lowerInfinite == 0 && total.lower.compareTo(rhs.add(allowance)) > 0) ||
                (row.sense != "<=" && total.upperInfinite == 0 && total.upper.compareTo(rhs.subtract(allowance)) < 0)
            }.take(1)
            if (impossible.nonEmpty) contradiction = Some(s"Infeasible original row '${impossible.head._1.name}' under implied bounds")
            else {
              val implied = entries.join(boundedRows).map { case (_, ((id, a, category, own), (row, total))) =>
                val other = total.without(own)
                val rhs = decimal(row.rhs)
                val allowance = decimal(config.tolerance).multiply(Decimal.ONE.add(rhs.abs()))
                var lower = Double.NegativeInfinity
                var upper = Double.PositiveInfinity
                if (row.sense != ">=") other.lower.foreach { activity =>
                  if (a > 0) upper = rounded(rhs.add(allowance).subtract(activity), a, lower = false, category != Continuous)
                  else lower = rounded(rhs.add(allowance).subtract(activity), a, lower = true, category != Continuous)
                }
                if (row.sense != "<=") other.upper.foreach { activity =>
                  if (a > 0) lower = rounded(rhs.subtract(allowance).subtract(activity), a, lower = true, category != Continuous)
                  else upper = rounded(rhs.subtract(allowance).subtract(activity), a, lower = false, category != Continuous)
                }
                if (row.sense == "==" && total.nonzeros == 1 && category == Continuous) {
                  val exact = try Some(rhs.divide(decimal(a))) catch { case _: ArithmeticException => None }
                  exact.foreach { value =>
                    val asDouble = value.doubleValue()
                    if (java.lang.Double.isFinite(asDouble) && decimal(asDouble).compareTo(value) == 0) {
                      lower = asDouble; upper = asDouble
                    }
                  }
                }
                id -> LpBounds(lower, if (upper.isPosInfinity) None else Some(upper))
              }.reduceByKey((a, b) => LpBounds(math.max(a.lower, b.lower),
                (a.upper.toVector ++ b.upper.toVector).reduceOption((x: Double, y: Double) => math.min(x, y))))
              val next = variables.leftOuterJoin(implied).mapValues { case (v, bounds) =>
                bounds.fold(v)(b => v.copy(lower = math.max(v.lower, b.lower),
                  upper = (v.upper.toVector ++ b.upper.toVector).reduceOption((x: Double, y: Double) => math.min(x, y))))
              }.persist()
              next.count()
              val invalid = next.values.filter(v => v.lower.isPosInfinity || v.upper.exists(u => u.isNegInfinity || u < v.lower)).take(1)
              if (invalid.nonEmpty) contradiction = Some(s"No feasible value for '${invalid.head.name}' under implied bounds")
              changed = next.join(variables).values.filter { case (a, b) => a.lower != b.lower || a.upper != b.upper }.take(1).nonEmpty
              variables.unpersist(false); variables = next
            }
          } finally boundedRows.unpersist(false)
        } finally entries.unpersist(false)
      }
      val result = variables.values.persist()
      result.count()
      new BoundPropagationResult(result, passes, contradiction)
    } finally { variables.unpersist(false); matrix.unpersist(false); rows.unpersist(false) }
  }
}
