package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.CandidateInfo
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row
import org.apache.spark.sql.types._
import scala.concurrent.duration.FiniteDuration

final case class LpSolverCapabilities(lp: Boolean = true, mip: Boolean = false,
  quadratic: Boolean = false, infiniteBounds: Boolean = true, starts: Boolean = false,
  callbacks: Boolean = false, duals: Boolean = false, reducedCosts: Boolean = false,
  nativeSession: Boolean = false)

final case class LpAdapterOptions(validation: CandidateValidationConfig = CandidateValidationConfig(),
  timeLimit: Option[FiniteDuration] = None, shouldStop: () => Boolean = () => false,
  onProgress: Option[String => Unit] = None, requireDuals: Boolean = false,
  maxVariables: Long = 1000000L, maxConstraints: Long = 100000L, maxNonzeros: Long = 10000000L) {
  require(timeLimit.forall(_.toNanos > 0), "Adapter time limit must be positive")
  require(Seq(maxVariables, maxConstraints, maxNonzeros).forall(_ >= 0), "Adapter transfer limits must be nonnegative")
}

/** Backend claims in original model units. The library independently validates every supplied point.
  * The result owns no caller resources; its adapter session must keep the RDDs usable until close.
  */
final case class LpAdapterResult(status: LpStatus, values: Option[RDD[LpCandidateValue]] = None,
  objective: Option[Double] = None, bestBound: Option[Double] = None, iterations: Int = 0,
  rowDuals: Option[RDD[(LpConstraintId, Double)]] = None,
  reducedCosts: Option[RDD[(LpVariableId, Double)]] = None, diagnostics: Map[String, String] = Map.empty)

final case class LpBackendSummary(name: String, diagnostics: Map[String, String],
  bestBound: Option[Double], independentlyValidated: Boolean)

/** Driver-only extension point. Implementations use public identity-bearing model RDDs. */
trait LpSolverAdapter {
  def name: String
  def capabilities: LpSolverCapabilities
  def prepare(model: LpModelView, options: LpAdapterOptions): LpAdapterSession
}

/** Close must be idempotent and release only session-owned resources, even after solve failure. */
trait LpAdapterSession extends AutoCloseable {
  def solve(): LpAdapterResult
}

private[dsl] object LpAdapterSolve {
  def run(problem: LpProblem, adapter: LpSolverAdapter, options: LpAdapterOptions): LpSolution = {
    val view = problem.inspect
    check(view, adapter.capabilities, options)
    val session = adapter.prepare(view, options)
    var solution: Option[LpSolution] = None
    var failure: Option[Throwable] = None
    try {
      solution = Some(normalize(problem, adapter, options, session.solve()))
      solution.get
    } catch { case scala.util.control.NonFatal(e) => failure = Some(e); throw e }
    finally try session.close() catch {
      case scala.util.control.NonFatal(e) => failure match {
        case Some(original) => original.addSuppressed(e)
        case None => solution.foreach(_.close()); throw e
      }
    }
  }

  def check(view: LpModelView, capabilities: LpSolverCapabilities, options: LpAdapterOptions): Unit = {
    def need(condition: Boolean, detail: String): Unit = if (!condition) throw new LpModelException(s"Adapter does not support $detail")
    val discrete = view.variableDeclarations.exists(_.category != Continuous) && !options.validation.relaxIntegrality
    need(if (discrete) capabilities.mip else capabilities.lp, if (discrete) "MIP" else "LP")
    need(!view.hasQuadraticObjective || capabilities.quadratic, "quadratic objectives")
    need(!options.validation.relaxIntegrality, "implicit relaxation; export an explicit relaxed model instead")
    need(options.onProgress.isEmpty || capabilities.callbacks, "progress callbacks")
    need(!options.requireDuals || capabilities.duals && !discrete, "continuous LP duals")
    if (!capabilities.infiniteBounds)
      need(view.variables.filter(v => !java.lang.Double.isFinite(v.lower) || v.upper.isEmpty).take(1).isEmpty, "infinite bounds")
    val size = view.statistics()
    need(size.variables <= options.maxVariables && size.constraints <= options.maxConstraints &&
      size.constraintNonzeros <= options.maxNonzeros, "the requested transfer size (increase explicit adapter limits)")
  }

  def normalize(problem: LpProblem, adapter: LpSolverAdapter, options: LpAdapterOptions, raw: LpAdapterResult): LpSolution = {
    def fail(message: String): Nothing = throw new LpModelException(s"Malformed result from ${adapter.name}: $message")
    if (raw.status == null || raw.iterations < 0) fail("invalid status or iteration count")
    if ((raw.rowDuals.nonEmpty && !adapter.capabilities.duals) ||
      (raw.reducedCosts.nonEmpty && !adapter.capabilities.reducedCosts)) fail("unadvertised sensitivity data")
    if (options.requireDuals && raw.status == LpStatus.Optimal && raw.rowDuals.isEmpty) fail("requested duals are missing")
    val view = problem.inspect
    val sc = problem.spark.sparkContext
    val values = raw.values.map(_.map(v => (v.variable.family -> v.variable.key) -> v.value).persist())
      .getOrElse(sc.emptyRDD[((Int, String), Double)].persist())
    var costs: Option[RDD[((Int, String), Double)]] = None
    var diagnosticsFrame: Option[org.apache.spark.sql.DataFrame] = None
    try {
      values.count()
      val report = raw.values.map(_ => problem.validateCandidate(values.map { case ((family, key), value) =>
        LpCandidateValue(LpVariableId(family, key), value)
      }, options.validation))
      val (feasible, objective, violation) = try report.map { r =>
        if (r.violations.filter(v => Set("foreign", "missing", "duplicate", "non-finite")(v.kind)).take(1).nonEmpty)
          fail("candidate identities or values are invalid")
        (r.feasible, r.objectiveValue, r.maxViolation)
      }.getOrElse((false, None, Double.NaN)) finally report.foreach(_.close())
      if (raw.status == LpStatus.Optimal && (!feasible || objective.isEmpty)) fail("optimal status requires a validated feasible point")
      if (raw.status == LpStatus.Unbounded && !feasible) fail("unbounded status requires a validated feasible point")
      if (raw.status == LpStatus.Infeasible && feasible) fail("infeasible status contradicts the supplied feasible point")
      raw.objective.foreach { claimed =>
        if (!java.lang.Double.isFinite(claimed) || objective.isEmpty ||
          math.abs(claimed - objective.get) > options.validation.tolerance * (1.0 + math.abs(objective.get)))
          fail("objective does not match original coefficients, sense and constant")
      }
      raw.bestBound.foreach { bound =>
        if (!java.lang.Double.isFinite(bound)) fail("bound must be finite when supplied")
        if (feasible && objective.exists(o => (if (problem.sense == Minimize) bound - o else o - bound) >
          options.validation.tolerance * (1.0 + math.abs(o)))) fail("bound contradicts the incumbent")
      }
      def validateKeys[K: scala.reflect.ClassTag](data: RDD[(K, Double)], expected: RDD[K]): Unit = {
        if (data.filter(v => !java.lang.Double.isFinite(v._2)).take(1).nonEmpty ||
          data.mapValues(_ => 1).reduceByKey(_ + _).filter(_._2 != 1).take(1).nonEmpty ||
          data.keys.subtract(expected).take(1).nonEmpty) fail("invalid sensitivity identities or values")
      }
      raw.rowDuals.foreach(validateKeys(_, view.constraints.map(_.id)))
      raw.reducedCosts.foreach(validateKeys(_, view.variables.map(_.id)))
      val sensitivity = raw.status == LpStatus.Optimal && !view.hasQuadraticObjective &&
        view.variableDeclarations.forall(_.category == Continuous)
      if (!sensitivity && (raw.rowDuals.nonEmpty || raw.reducedCosts.nonEmpty)) fail("sensitivity requires an optimal continuous LP")
      costs = raw.reducedCosts.map(_.map { case (id, value) => (id.family -> id.key) -> value }.persist())
      costs.foreach(_.count())
      val activities = view.coefficients.map(c => (c.variable.family -> c.variable.key) -> (c.row, c.value))
        .join(values).map { case (_, ((row, c), x)) => row -> (c * x) }.reduceByKey(_ + _)
      val duals = raw.rowDuals.getOrElse(sc.emptyRDD[(LpConstraintId, Double)])
      val available = raw.values.nonEmpty
      // Diagnostics remain usable after session cleanup because they depend on retained original values.
      val rows = view.constraints.map(r => r.id -> r).leftOuterJoin(activities).leftOuterJoin(duals)
        .map { case (_, ((r, activity), dual)) =>
          val a = if (!available) Double.NaN else activity.getOrElse(0.0)
          val slack = if (r.sense == ">=") a - r.rhs else r.rhs - a
          Row(r.name, if (r.group.isEmpty) null else r.group.mkString(","), a, r.sense, r.rhs, slack,
            dual.map(Double.box).orNull, "External adapter", if (dual.isDefined) null else "Backend did not provide an LP dual")
        }
      val schema = StructType(Seq(StructField("name", StringType, false), StructField("group", StringType),
        StructField("activity", DoubleType), StructField("sense", StringType), StructField("rhs", DoubleType),
        StructField("slack", DoubleType), StructField("dual", DoubleType), StructField("note", StringType), StructField("dual_note", StringType)))
      val value = raw.status match {
        case LpStatus.Infeasible | LpStatus.InfeasibleOrUnbounded => Double.NaN
        case LpStatus.Unbounded => if (problem.sense == Minimize) Double.NegativeInfinity else Double.PositiveInfinity
        case _ => objective.getOrElse(Double.NaN)
      }
      val frame = problem.spark.createDataFrame(rows, schema).persist()
      diagnosticsFrame = Some(frame)
      frame.count()
      new LpSolution(raw.status, value, raw.iterations, LpResiduals(violation, Double.NaN, Double.NaN),
        frame, problem, values,
        CandidateInfo(raw.values.nonEmpty, feasible, if (raw.values.nonEmpty) Some(raw.iterations) else None),
        reducedCostData = costs, backend = Some(LpBackendSummary(adapter.name, raw.diagnostics, raw.bestBound, raw.values.nonEmpty)))
    } catch { case scala.util.control.NonFatal(e) =>
      values.unpersist(false); costs.foreach(_.unpersist(false)); diagnosticsFrame.foreach(_.unpersist(false)); throw e }
  }
}
