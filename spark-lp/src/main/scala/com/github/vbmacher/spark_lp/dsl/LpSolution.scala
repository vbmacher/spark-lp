package com.github.vbmacher.spark_lp.dsl

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame

/**
  * The result of one solve.
  *
  * An interior-point method returns values like `33.999999999`, not `34.0`; round at the point of
  * use — `values` does not round on the caller's behalf.
  *
  * At [[LpStatus.Infeasible]] and [[LpStatus.InfeasibleOrUnbounded]] the `objectiveValue` is `NaN`;
  * at [[LpStatus.Unbounded]] it is the signed infinity of the objective sense (`-Infinity` for
  * [[Minimize]], `+Infinity` for [[Maximize]]). `values(...)` and `constraints` keep exposing the
  * last iterate for these statuses, as with [[LpStatus.IterationLimit]] — for
  * [[LpStatus.Infeasible]] the `slack` column is exactly the tool to locate the conflicting
  * constraints.
  */
final class LpSolution private[dsl](
  val status: LpStatus,

  /** Objective value in the user's sense: solver optimum plus all constant terms (explicit
    * expression constants and the bound-shift contributions), with the sign restored for Maximize.
    * `NaN` at [[LpStatus.Infeasible]]/[[LpStatus.InfeasibleOrUnbounded]], signed infinity at
    * [[LpStatus.Unbounded]]. */
  val objectiveValue: Double,
  val iterations: Int,
  val residuals: LpResiduals,

  /**
    * Per-constraint diagnostics: `name`, `group` (when present), `activity`, `sense`, `rhs`,
    * `slack` (the distance to the bound in the constraint's own direction: `rhs - activity` for
    * `<=`, `activity - rhs` for `>=`), `dual` (reserved, always NULL) and `note` (presolve notes).
    * At [[LpStatus.IterationLimit]] and the infeasibility-related statuses the iterate need not be
    * primal-feasible, so slack may be materially negative and equality rows may be violated;
    * `residuals.primal` quantifies this, and at [[LpStatus.Infeasible]] the negative slacks locate
    * the conflicting constraints.
    */
  val constraints: DataFrame,
  private val problem: LpProblem,
  private[dsl] val userValues: RDD[((Int, String), Double)]) {

  /** The original variable domain plus `lp_variable` (display name) and `lp_value` columns. */
  def values[K](variables: LpVariableSet[K]): DataFrame = {
    val handle = variables.handle
    if (!(handle.problem eq problem)) {
      throw new LpModelException(s"Variable set '${handle.name}' belongs to a different problem")
    }
    val setIndex = handle.setIndex
    val setValues = userValues.filter(_._1._1 == setIndex).map { case ((_, enc), value) => (enc, value) }
    handle.domain.attachValues(setValues, handle.name)
  }

  /** Primal value of one scalar variable, in the caller's original units. */
  def value(variable: LpVariable): Double = {
    val handle = variable.handle
    if (!(handle.problem eq problem)) {
      throw new LpModelException(s"Variable '${handle.name}' belongs to a different problem")
    }
    val setIndex = handle.setIndex
    val collected = userValues.filter(_._1._1 == setIndex).map(_._2).collect()
    if (collected.isEmpty) {
      throw new LpModelException(s"No value available for variable '${handle.name}'")
    }
    collected.head
  }
}
