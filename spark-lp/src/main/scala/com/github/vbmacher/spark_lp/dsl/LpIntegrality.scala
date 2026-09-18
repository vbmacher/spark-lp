package com.github.vbmacher.spark_lp.dsl

/**
  * Shared integrality helpers: distance to the nearest integer and the integral bound snap. Keeps
  * the `math.rint`/`ceil`/`floor` conventions in one place across rounding, branching, presolve and
  * candidate validation.
  */
private[dsl] object LpIntegrality {

  /** Absolute distance from `value` to its nearest integer. */
  def fractionality(value: Double): Double = math.abs(value - math.rint(value))

  /** True when `value` lies within `tolerance` of an integer. */
  def isIntegral(value: Double, tolerance: Double): Boolean = fractionality(value) <= tolerance

  /**
    * Snap a variable's domain bounds to the integral hull (`ceil` the lower, `floor` the upper) for
    * non-continuous categories; continuous categories keep their exact bounds.
    */
  def integralBounds(category: VariableCategory, lower: Double, upper: Option[Double]): (Double, Option[Double]) =
    if (category == Continuous) (lower, upper)
    else {
      val (clampedLower, clampedUpper) = VariableCategory.domainBounds(category, lower, upper)
      (math.ceil(clampedLower), clampedUpper.map(math.floor))
    }
}
