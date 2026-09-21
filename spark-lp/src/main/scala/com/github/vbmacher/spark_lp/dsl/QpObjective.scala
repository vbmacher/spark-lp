package com.github.vbmacher.spark_lp.dsl

/**
  * Quadratic objective for a continuous model.
  *
  * The objective has the form `0.5*x^T*Q*x + linear`. Construct it with [[QpObjective.separable]]
  * for diagonal `Q`, or with [[QpObjective.squared]] and [[QpObjective.sumSquares]] for squared
  * linear expressions containing cross-variable terms. Terms may be added and scaled before the
  * objective is assigned to an [[LpProblem]].
  */
final class QpObjective private[dsl](val diagonal: LpExpr, val linear: LpExpr,
  private[dsl] val factors: Vector[(LpExpr, Double)] = Vector.empty) {

  /** Adds another quadratic objective. Repeated terms add their coefficients. */
  def +(other: QpObjective): QpObjective =
    new QpObjective(diagonal.plus(other.diagonal), linear.plus(other.linear), factors ++ other.factors)

  /** Adds a linear expression and its constant to this objective. */
  def +(other: LpExpr): QpObjective = new QpObjective(diagonal, linear.plus(other), factors)

  /** Multiplies every quadratic, linear and constant term by `scale`. */
  def *(scale: Double): QpObjective = new QpObjective(diagonal.scaledBy(scale), linear.scaledBy(scale), factors.map { case (expr, w) => (expr, w * scale) })
}

object QpObjective {
  /**
    * Creates the convex term `weight * expression^2`.
    *
    * `weight` must be finite and nonnegative. The expression may contain multiple variables, so
    * its square may create cross-variable products.
    */
  def squared(expression: LpExpr, weight: Double = 1.0): QpObjective = {
    require(weight >= 0.0 && !weight.isInfinite, "weight must be finite and nonnegative")
    new QpObjective(LpExpr.zero, LpExpr.zero, Vector(expression -> weight))
  }

  /** Adds a sequence of `weight * expression^2` terms. */
  def sumSquares(factors: Seq[(LpExpr, Double)]): QpObjective =
    factors.foldLeft(new QpObjective(LpExpr.zero, LpExpr.zero)) { case (sum, (expr, weight)) =>
      sum + squared(expr, weight)
    }

  /**
    * Creates `0.5 * sum(diagonal_i * x_i^2) + linear` without cross-variable products.
    *
    * Coefficients in `diagonal` are quadratic curvature, not linear costs, and its constant must
    * be zero. Repeated variable terms add.
    */
  def separable(diagonal: LpExpr, linear: LpExpr): QpObjective = {
    require(diagonal.constant == 0.0, "A curvature expression cannot have a constant")
    new QpObjective(diagonal, linear)
  }

  /**
    * Creates `weight * (variable - target)^2`.
    *
    * Use a negative scale on the completed objective when building a concave maximization objective.
    */
  def squaredDeviation(variable: LpVariable, target: Double, weight: Double = 1.0): QpObjective = {
    require(!target.isNaN && !target.isInfinite, "target must be finite")
    require(weight >= 0.0 && !weight.isInfinite, "weight must be finite and nonnegative")
    new QpObjective(variable.toExpr(2.0 * weight),
      variable.toExpr(-2.0 * weight * target).plusConstant(weight * target * target))
  }
}
