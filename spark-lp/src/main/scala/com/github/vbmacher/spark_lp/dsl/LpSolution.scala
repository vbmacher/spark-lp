package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.Numerics
import com.github.vbmacher.spark_lp.{CandidateInfo, StopReason}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame

/**
  * Result returned after an [[LpProblem]] is solved.
  *
  * Read `candidate` before reading variable values: a stopped or limited solve may have no completed
  * iterate, or its retained iterate may violate the original model. Values remain raw
  * floating-point results; use [[rounded]] only when preparing a report.
  *
  * This object owns persisted Spark data. Complete actions on [[values]], [[constraints]] and
  * [[evidence]] before calling [[close]].
  *
  * @param status why the solve terminated
  * @param objectiveValue objective in the model's original minimize/maximize sense, including
  *                       constants; `NaN` for infeasibility statuses and signed infinity for
  *                       [[LpStatus.Unbounded]]
  * @param iterations completed numerical iterations
  * @param residuals errors measured on the returned continuous iterate
  * @param timings wall-clock phases and optional driver CPU time
  * @param constraints one row per original constraint, with `activity`, `sense`, `rhs`,
  *                    directional `slack`, and nullable sensitivity columns
  * @param candidate whether variable values exist and satisfy the original model
  * @param stopReason cooperative-stop reason, when the solve was intentionally stopped
  * @param evidence independently verifiable continuous infeasibility or unboundedness evidence
  * @param isRelaxation true when status, residuals and candidate describe a continuous relaxation
  * @param mip mixed-integer search bounds and gaps, when integer search ran
  * @param presolve original and simplified model sizes and transformations
  * @param backend external-adapter provenance, when an adapter produced the result
  * @param start summary of the supplied starting values, when present
  */
final class LpSolution private[dsl](
  val status: LpStatus,
  val objectiveValue: Double,
  val iterations: Int,
  val residuals: LpResiduals,
  val timings: LpSolveTimings,
  val constraints: DataFrame,
  private val problem: LpProblem,
  private[dsl] val userValues: RDD[((Int, String), Double)],
  val candidate: CandidateInfo,
  val stopReason: Option[StopReason] = None,
  val evidence: Option[LpEvidence] = None,
  val isRelaxation: Boolean = false,
  val mip: Option[MipSummary] = None,
  private[dsl] val reducedCostData: Option[RDD[((Int, String), Double)]] = None,
  val presolve: Option[LpPresolveSummary] = None,
  val backend: Option[LpBackendSummary] = None,
  val start: Option[LpStartSummary] = None) extends AutoCloseable {

  private val metadata = problem.handles.map(h => h.setIndex -> h.metadata).toMap
  private[dsl] def snapshot(handle: VarSetHandle): VariableMetadata = {
    requireOwner(handle)
    metadata.getOrElse(handle.setIndex, throw new LpModelException("Variable was declared after this solution"))
  }
  private var closed = false

  def asStart(config: LpStartConfig = LpStartConfig()): LpStart = {
    requireCandidate()
    LpStart.create(problem, userValues.map { case ((family, key), value) =>
      LpCandidateValue(LpVariableId(family, key), value)
    }, config)
  }

  private[dsl] def requireOwner(handle: VarSetHandle): Unit = {
    if (!(handle.problem eq problem)) throw new LpModelException("Variable belongs to a different problem")
  }

  /**
    * Creates a reporting view that snaps near-integer and near-bound values.
    *
    * The returned view does not change this solution, its objective, status or feasibility.
    */
  def rounded(rounding: LpRounding = LpRounding()): LpRoundedValues = {
    requireCandidate()
    new LpRoundedValues(this, rounding)
  }

  private[dsl] def requireOpen(): Unit =
    if (closed) throw new LpModelException("Solution is closed")

  private[dsl] def requireCandidate(): Unit = {
    requireOpen()
    if (!candidate.available) throw new LpModelException("No completed iterate is available for this solve")
  }

  /**
    * Evaluates `expression` with this solution's variable values.
    *
    * Coefficient DataFrames and Datasets are evaluated when this method is called, not when the
    * model was solved.
    */
  def evaluate(expression: LpExpr): Double = {
    requireCandidate()
    implicit val spark: org.apache.spark.sql.SparkSession = problem.spark
    val coefficients = LpExpressionData.expand(expression, Some(problem))
    val joined = coefficients.leftOuterJoin(userValues)
    if (joined.filter { case (_, (_, value)) => value.isEmpty || value.exists(v => !Numerics.isFinite(v)) }
      .take(1).nonEmpty)
      throw new LpModelException("Expression references values absent or non-finite in this solution")
    val value = joined.values.map { case (coefficient, x) => coefficient * x.get }.fold(0.0)(_ + _) + expression.constant
    LpExpressionData.check(value)
    value
  }

  /** Returns the variable's reduced cost in original model units, or `None` when unavailable. */
  def reducedCost(variable: LpVariable): Option[Double] = {
    requireOpen()
    requireOwner(variable.handle)
    val id = (variable.handle.setIndex, variable.selectedKey.getOrElse(""))
    reducedCostData.flatMap(_.filter(_._1 == id).values.take(1).headOption)
  }

  /**
    * Joins reduced costs to the variable set's original domain.
    *
    * The returned DataFrame adds nullable `lp_reduced_cost`; `null` means that sensitivity is
    * unavailable and is never replaced with zero.
    */
  def reducedCosts[K](variables: LpVariableSet[K]): DataFrame = {
    requireOpen()
    requireOwner(variables.handle)
    val h = variables.handle
    val si = h.setIndex
    val sc = h.problem.spark.sparkContext
    val costs = reducedCostData.getOrElse(sc.emptyRDD[((Int, String), Double)])
      .filter(_._1._1 == si).map { case ((_, key), value) => key -> value }
    val values = h.domain.keyPairs().mapValues(_ => ()).leftOuterJoin(costs)
      .mapValues { case (_, value) => value.getOrElse(Double.NaN) }
    import org.apache.spark.sql.functions.{col, isnan, lit, when}
    h.domain.attachValues(values, snapshot(h).name, snapshot(h).names).withColumnRenamed("lp_value", "lp_reduced_cost")
      .withColumn("lp_reduced_cost", when(isnan(col("lp_reduced_cost")), lit(null).cast("double"))
        .otherwise(col("lp_reduced_cost")))
  }

  /** Releases Spark data owned by this result. Calling it more than once is safe. */
  override def close(): Unit = {
    closed = true
    userValues.unpersist(blocking = false)
    evidence.foreach(_.close())
    reducedCostData.foreach(_.unpersist(false))
    if (backend.nonEmpty) constraints.unpersist(false)
  }

  /**
    * Joins solved values to the variable set's original domain.
    *
    * The returned DataFrame adds `lp_variable` (display name) and `lp_value` columns.
    */
  def values[K](variables: LpVariableSet[K]): DataFrame = {
    requireCandidate()
    val handle = variables.handle
    if (!(handle.problem eq problem)) {
      throw new LpModelException(s"Variable set '${handle.name}' belongs to a different problem")
    }
    val setIndex = handle.setIndex
    val setValues = userValues.filter(_._1._1 == setIndex).map { case ((_, enc), value) => (enc, value) }
    handle.domain.attachValues(setValues, snapshot(handle).name, snapshot(handle).names)
  }

  /** Returns one variable's primal value in original model units. */
  def value(variable: LpVariable): Double = {
    requireCandidate()
    val handle = variable.handle
    if (!(handle.problem eq problem)) {
      throw new LpModelException(s"Variable '${handle.name}' belongs to a different problem")
    }
    val setIndex = handle.setIndex
    val key = variable.selectedKey.getOrElse("")
    val collected = userValues.filter { case ((si, enc), _) => si == setIndex && enc == key }
      .map(_._2).take(1)
    if (collected.isEmpty) {
      throw new LpModelException(s"No value available for variable '${handle.name}'")
    }
    collected.head
  }
}
