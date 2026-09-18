package com.github.vbmacher.spark_lp.dsl

import org.apache.spark.sql.DataFrame

/** Absolute snapping tolerances. Continuous variables are only snapped to bounds, never integers. */
final case class LpRounding(integerTolerance: Double = 1e-6, boundTolerance: Double = 1e-8)
    extends Serializable {
  require(Seq(integerTolerance, boundTolerance).forall(t => !t.isNaN && !t.isInfinite && t >= 0.0),
    "Rounding tolerances must be finite and nonnegative")
  require(integerTolerance < 0.5, "Integer rounding tolerance must be less than 0.5")

  private[dsl] def apply(value: Double, category: VariableCategory,
                         lower: Double, upper: Option[Double]): Double = {
    if (value.isNaN || value.isInfinite) value
    else {
      val (clampedLower, clampedUpper) = VariableCategory.domainBounds(category, lower, upper)
      val lo = if (category == Continuous) lower else math.ceil(clampedLower)
      val hi = if (category == Continuous) upper else clampedUpper.map(math.floor)
      val integer = if (category != Continuous && math.abs(value - math.rint(value)) <= integerTolerance)
        math.rint(value) else value
      val nearby = (if (lo.isNegInfinity) Vector.empty else Vector(lo)) ++ hi.toVector
      nearby.filter(b => math.abs(integer - b) <= boundTolerance)
        .sortBy(b => (math.abs(integer - b), b)).headOption.getOrElse(integer)
    }
  }
}

/** A lazy reporting view owned by the raw solution. It carries no objective, status or feasibility claim. */
final class LpRoundedValues private[dsl](raw: LpSolution, val rounding: LpRounding) {
  def value(variable: LpVariable): Double = {
    val h = variable.handle
    val bounds = raw.snapshot(h).at(variable.selectedKey.getOrElse(""))
    rounding(raw.value(variable), h.category, bounds.lower, bounds.upper)
  }

  /** Distributed transformation followed by the ordinary domain join; no values are collected. */
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
