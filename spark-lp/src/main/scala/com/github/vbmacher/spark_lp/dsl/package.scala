package com.github.vbmacher.spark_lp

package object dsl {

/**
    * Linear-system strategy used by the interior-point solver.
    *
    * This alias exposes [[com.github.vbmacher.spark_lp.newton.NewtonSolver]] from the DSL package so
    * callers can configure [[SolveConfig.newtonSolver]] without another import.
    */
  type NewtonSolver = newton.NewtonSolver
  val NewtonSolver: newton.NewtonSolver.type = newton.NewtonSolver

  /**
    * Builds a linear expression from local coefficients and values.
    *
    * Both collections are read on the driver and must be finite, equal in length, and contain only
    * finite coefficients. A value may be an [[LpVariable]] or [[LpExpr]].
    */
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

  /** Converts one decision variable to a linear expression with coefficient one. */
  def lpSum(variable: LpVariable): LpExpr = variable.toExpr(1.0)

  /** Returns the sum of every member of a distributed variable family. */
  def lpSum[K](variables: LpVariableSet[K]): LpExpr = variables.handle.toExpr(1.0)

  /** Returns an already-built linear expression unchanged. */
  def lpSum(expression: LpExpr): LpExpr = expression

  /** Adds a finite driver-local collection of linear expressions. */
  def lpSum(expressions: Iterable[LpExpr]): LpExpr =
    expressions.foldLeft(LpExpr.zero)(_ plus _)

  /**
    * Builds one linear expression per group from distributed coefficient rows.
    *
    * `by` names the grouping columns stored in `terms`. Repeated rows for the same group and
    * variable are added. Import [[implicits]] to compare the result with a scalar or grouped RHS.
    */
  def lpSumBy(terms: LpTerms, by: Seq[String]): GroupedLpExpr = new GroupedLpExpr(terms, by)
}
