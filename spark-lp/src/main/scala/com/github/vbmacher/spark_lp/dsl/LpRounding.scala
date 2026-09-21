package com.github.vbmacher.spark_lp.dsl

import org.apache.spark.sql.DataFrame

/**
  * Reporting-only rules for snapping values near exact domain values.
  *
  * Integer and binary values within `integerTolerance` of a whole number are snapped first. Every
  * variable may then be snapped to a declared bound within `boundTolerance`. Continuous values are
  * never snapped to an integer merely because they are nearby.
  *
  * @param integerTolerance maximum distance for snapping an integer or binary value to a whole number.
  * @param boundTolerance maximum distance for snapping any value to an inclusive declared bound.
  */
final case class LpRounding(integerTolerance: Double = 1e-6, boundTolerance: Double = 1e-8)
    extends Serializable {
  require(Seq(integerTolerance, boundTolerance).forall(t => !t.isNaN && !t.isInfinite && t >= 0.0),
    "Rounding tolerances must be finite and nonnegative")
  require(integerTolerance < 0.5, "Integer rounding tolerance must be less than 0.5")

  private[dsl] def apply(value: Double, category: VariableCategory,
                         lower: Double, upper: Option[Double]): Double = {
    if (value.isNaN || value.isInfinite) value
    else {
      val (lo, hi) = LpIntegrality.integralBounds(category, lower, upper)
      val integer = if (category != Continuous && LpIntegrality.isIntegral(value, integerTolerance))
        math.rint(value) else value
      val nearby = (if (lo.isNegInfinity) Vector.empty else Vector(lo)) ++ hi.toVector
      nearby.filter(b => math.abs(integer - b) <= boundTolerance)
        .sortBy(b => (math.abs(integer - b), b)).headOption.getOrElse(integer)
    }
  }
}

/**
  * Lazy rounded-value view over an [[LpSolution]].
  *
  * Rounding changes only reported values. It does not recalculate the objective, validate
  * feasibility or change the raw solution.
  */
final class LpRoundedValues private[dsl](raw: LpSolution, val rounding: LpRounding) {
  /** Returns one scalar value after applying this view's reporting rules. */
  def value(variable: LpVariable): Double = {
    val h = variable.handle
    val bounds = raw.snapshot(h).at(variable.selectedKey.getOrElse(""))
    rounding(raw.value(variable), h.category, bounds.lower, bounds.upper)
  }

  /** Applies rounding in Spark and joins the values to the variable family's original domain. */
  def values[K](variables: LpVariableSet[K]): DataFrame = {
    raw.requireCandidate()
    val h = variables.handle
    raw.requireOwner(h)
    val si = h.setIndex
    val category = h.category
    val metadata = raw.snapshot(h)
    val rule = rounding
    h.domain.attachValues(raw.userValues.filter(_._1._1 == si).map { case ((_, key), v) =>
      val bounds = metadata.at(key)
      key -> rule(v, category, bounds.lower, bounds.upper)
    }, metadata.name, metadata.names)
  }
}
