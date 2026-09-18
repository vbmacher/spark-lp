package com.github.vbmacher.spark_lp

package object dsl {

  /**
    * Strategy for solving the per-iteration normal-equations systems, re-exported from the core
    * package for DSL users; see [[com.github.vbmacher.spark_lp.newton.NewtonSolver]] and
    * `SolveConfig.newtonSolver`.
    */
  type NewtonSolver = newton.NewtonSolver
  val NewtonSolver: newton.NewtonSolver.type = newton.NewtonSolver

  /** Local dot product; both finite collections must have equal lengths. */
  def lpDot[A](coefficients: Iterable[Double], values: Iterable[A])(
    implicit toExpression: A => LpExpr): LpExpr = {
    val cs = coefficients.iterator
    val vs = values.iterator
    var result = LpExpr.zero
    while (cs.hasNext && vs.hasNext) {
      val coefficient = cs.next()
      Numerics.requireFinite(coefficient, "Dot-product coefficient")
      result = result.plus(toExpression(vs.next()).scaledBy(coefficient))
    }
    if (cs.hasNext || vs.hasNext)
      throw new LpModelException("Dot-product collections must have equal lengths")
    result
  }

  /** Sums a scalar variable into an expression, PuLP-style. */
  def lpSum(variable: LpVariable): LpExpr = variable.toExpr(1.0)

  /** Sums every variable of a set with coefficient 1. */
  def lpSum[K](variables: LpVariableSet[K]): LpExpr = variables.handle.toExpr(1.0)

  /** Identity; accepts a prebuilt expression such as `variables * $"cost"`. */
  def lpSum(expression: LpExpr): LpExpr = expression

  /** Sums a collection of expressions. */
  def lpSum(expressions: Iterable[LpExpr]): LpExpr =
    expressions.foldLeft(LpExpr.zero)(_ plus _)

  /**
    * One symbolic expression per group key. Duplicate `(group, variable)` pairs are aggregated by
    * summing their coefficients — the standard linear-algebra meaning of repeated terms in one
    * expression. Comparison operators (taking the RHS as a DataFrame or a scalar) are supplied by
    * [[implicits.GroupedExprOps]].
    */
  def lpSumBy(terms: LpTerms, by: Seq[String]): GroupedLpExpr = new GroupedLpExpr(terms, by)
}
