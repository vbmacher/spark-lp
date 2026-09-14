package com.github.vbmacher.spark_lp.dsl

/** Separable objective 0.5 * sum(q_i*x_i*x_i) + linear.
  * Coefficients of `diagonal` denote curvature, not linear costs. Repeated terms sum.
  * Curvature must be nonnegative for minimization, nonpositive for maximization.
  */
final class QpObjective private[dsl](val diagonal: LpExpr, val linear: LpExpr) {
  def +(other: QpObjective): QpObjective =
    new QpObjective(diagonal.plus(other.diagonal), linear.plus(other.linear))
  def +(other: LpExpr): QpObjective = new QpObjective(diagonal, linear.plus(other))
  def *(scale: Double): QpObjective = new QpObjective(diagonal.scaledBy(scale), linear.scaledBy(scale))
}

object QpObjective {
  /** Scalar or keyed curvature via the existing constant, Column and weightedBy term builders. */
  def separable(diagonal: LpExpr, linear: LpExpr): QpObjective = {
    require(diagonal.constant == 0.0, "A curvature expression cannot have a constant")
    new QpObjective(diagonal, linear)
  }

  /** weight * (variable - target)^2; negate for a concave maximization objective. */
  def squaredDeviation(variable: LpVariable, target: Double, weight: Double = 1.0): QpObjective = {
    require(!target.isNaN && !target.isInfinite, "target must be finite")
    require(weight >= 0.0 && !weight.isInfinite, "weight must be finite and nonnegative")
    new QpObjective(variable.handle.toExpr(2.0 * weight),
      variable.handle.toExpr(-2.0 * weight * target).plusConstant(weight * target * target))
  }
}
