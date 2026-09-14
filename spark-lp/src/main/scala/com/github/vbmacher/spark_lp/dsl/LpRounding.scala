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
      val lo = if (category == Continuous) lower
        else math.ceil(if (category == Binary) math.max(0.0, lower) else lower)
      val hi = if (category == Continuous) upper
        else (if (category == Binary) Some(math.min(1.0, upper.getOrElse(1.0))) else upper).map(math.floor)
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
    rounding(raw.value(variable), h.category, h.lowerBound, h.upperBound)
  }

  /** Distributed transformation followed by the ordinary domain join; no values are collected. */
  def values[K](variables: LpVariableSet[K]): DataFrame = {
    raw.requireCandidate()
    val h = variables.handle
    raw.requireOwner(h)
    val si = h.setIndex
    val category = h.category
    val lower = h.lowerBound
    val upper = h.upperBound
    val rule = rounding
    h.domain.attachValues(raw.userValues.filter(_._1._1 == si).map { case ((_, key), v) =>
      key -> rule(v, category, lower, upper)
    }, h.name)
  }
}
