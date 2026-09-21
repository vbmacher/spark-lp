package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.CandidateInfo
import org.apache.spark.rdd.RDD
import scala.concurrent.duration.FiniteDuration

/**
  * Model and result features supported by an external solver adapter.
  *
  * @param lp accepts continuous linear models.
  * @param mip accepts integer and binary variables.
  * @param quadratic accepts convex quadratic objectives.
  * @param infiniteBounds accepts variables without both finite bounds.
  * @param starts accepts caller-supplied starting values.
  * @param callbacks emits progress or native callback events.
  * @param duals returns constraint dual values for optimal continuous results.
  * @param reducedCosts returns variable reduced costs for optimal continuous results.
  * @param nativeSession supports reusable [[LpNativeSession]] handles.
  * @param sos accepts special ordered sets without compiler lowering.
  */
final case class LpSolverCapabilities(
  lp: Boolean = true,
  mip: Boolean = false,
  quadratic: Boolean = false,
  infiniteBounds: Boolean = true,
  starts: Boolean = false,
  callbacks: Boolean = false,
  duals: Boolean = false,
  reducedCosts: Boolean = false,
  nativeSession: Boolean = false,
  sos: Boolean = false
)

/**
  * Transfer, validation, and stopping policy for one adapter solve.
  *
  * @param validation independent original-model candidate checks.
  * @param timeLimit optional adapter solve deadline.
  * @param shouldStop polled by cooperative adapters; return true to stop.
  * @param onProgress optional handler for adapter-defined progress messages.
  * @param requireDuals reject a result that cannot provide constraint dual values.
  * @param maxVariables maximum expanded variables transferred to the adapter.
  * @param maxConstraints maximum expanded constraint rows transferred to the adapter.
  * @param maxNonzeros maximum expanded constraint-matrix entries transferred to the adapter.
  * @param start optional starting assignment for adapters that support starts.
  */
final case class LpAdapterOptions(
  validation: CandidateValidationConfig = CandidateValidationConfig(),
  timeLimit: Option[FiniteDuration] = None,
  shouldStop: () => Boolean = () => false,
  onProgress: Option[String => Unit] = None,
  requireDuals: Boolean = false,
  maxVariables: Long = 1000000L,
  maxConstraints: Long = 100000L,
  maxNonzeros: Long = 10000000L,
  start: Option[LpStart] = None
) {
  require(timeLimit.forall(_.toNanos > 0), "Adapter time limit must be positive")
  require(Seq(maxVariables, maxConstraints, maxNonzeros).forall(_ >= 0), "Adapter transfer limits must be nonnegative")
}

/** Backend claims in original model units. The library independently validates every supplied point.
  * The result owns no caller resources; its adapter session must keep the RDDs usable until close.
  *
  * @param status backend termination status.
  * @param values candidate assignment keyed by portable variable identity.
  * @param objective backend-reported objective in original objective direction.
  * @param bestBound backend-reported mixed-integer objective bound.
  * @param iterations backend iteration count.
  * @param rowDuals dual values keyed by original constraint identity.
  * @param reducedCosts reduced costs keyed by original variable identity.
  * @param diagnostics backend-specific result metadata.
  * @param start backend disposition of supplied starting values.
  */
final case class LpAdapterResult(
  status: LpStatus,
  values: Option[RDD[LpCandidateValue]] = None,
  objective: Option[Double] = None,
  bestBound: Option[Double] = None,
  iterations: Int = 0,
  rowDuals: Option[RDD[(LpConstraintId, Double)]] = None,
  reducedCosts: Option[RDD[(LpVariableId, Double)]] = None,
  diagnostics: Map[String, String] = Map.empty,
  start: Option[LpStartSummary] = None
)

/**
  * Adapter provenance attached to a normalized [[LpSolution]].
  *
  * @param name adapter name.
  * @param diagnostics backend-specific metadata returned with the result.
  * @param bestBound mixed-integer objective bound in original objective units.
  * @param independentlyValidated true when spark-lp checked the returned assignment against the
  *                               original model.
  */
final case class LpBackendSummary(
  name: String,
  diagnostics: Map[String, String],
  bestBound: Option[Double],
  independentlyValidated: Boolean
)

/** Driver-only extension point. Implementations use public identity-bearing model RDDs. */
trait LpSolverAdapter {
  def name: String

  def capabilities: LpSolverCapabilities

  def prepare(model: LpModelView, options: LpAdapterOptions): LpAdapterSession
}

/** Close must be idempotent and release only session-owned resources, even after solve failure. */
trait LpAdapterSession extends AutoCloseable {
  def solve(): LpAdapterResult

  def nativeAccess: Option[LpNativeAccess] = None
}

private[dsl] object LpAdapterSolve {
  def run(problem: LpProblem, adapter: LpSolverAdapter, options: LpAdapterOptions): LpSolution = {
    val timing = new LpSolveTiming(LpSolveClock.system)
    timing.start()
    options.start.foreach { start =>
      if (start.problem ne problem) throw new LpModelException("Start belongs to a different model")
      start.values
    }
    val view = problem.inspect
    check(view, adapter.capabilities, options)
    val session = adapter.prepare(view, options)
    timing.startNumericalSolve()
    var solution: Option[LpSolution] = None
    var failure: Option[Throwable] = None
    try {
      val raw = session.solve()
      timing.startReconstruction()
      solution = Some(normalize(problem, adapter, options, raw, timing))
      solution.get
    } catch {
      case scala.util.control.NonFatal(e) => failure = Some(e); throw e
    }
    finally try session.close() catch {
      case scala.util.control.NonFatal(e) => failure match {
        case Some(original) => original.addSuppressed(e)
        case None => solution.foreach(_.close()); throw e
      }
    }
  }

  def check(view: LpModelView, capabilities: LpSolverCapabilities, options: LpAdapterOptions): Unit = {
    def need(condition: Boolean, detail: String): Unit = if (!condition) throw new LpModelException(s"Adapter does not support $detail")

    val discrete = (view.variableDeclarations.exists(_.category != Continuous) || view.sosGroups.nonEmpty) && !options.validation.relaxIntegrality
    need(view.sosGroups.isEmpty || capabilities.sos, "SOS groups")
    need(options.start.isEmpty || capabilities.starts, "user starts")
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

  def normalize(problem: LpProblem, adapter: LpSolverAdapter, options: LpAdapterOptions,
    raw: LpAdapterResult, timing: LpSolveTiming): LpSolution = {
    def fail(message: String): Nothing = throw new LpModelException(s"Malformed result from ${adapter.name}: $message")

    if (raw.status == null || raw.iterations < 0) fail("invalid status or iteration count")
    if (options.start.nonEmpty && raw.start.isEmpty) fail("adapter did not report whether the start was used")
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

      def validateKeys[K: scala.reflect.ClassTag](data: RDD[(K, Double)], expected: RDD[K],
        requireComplete: Boolean = false): Unit = {
        if (data.filter(v => !java.lang.Double.isFinite(v._2)).take(1).nonEmpty ||
          data.mapValues(_ => 1).reduceByKey(_ + _).filter(_._2 != 1).take(1).nonEmpty ||
          data.keys.subtract(expected).take(1).nonEmpty ||
          requireComplete && expected.subtract(data.keys).take(1).nonEmpty)
          fail("invalid sensitivity identities or values")
      }

      raw.rowDuals.foreach(validateKeys(_, view.constraints.map(_.id), options.requireDuals))
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
          ConstraintDiagnostics.row(r.name, if (r.group.isEmpty) null else r.group.mkString(","),
            a, r.sense, r.rhs, dual.map(Double.box).orNull, "External adapter",
            if (dual.isDefined) null else "Backend did not provide an LP dual")
        }
      val value = raw.status match {
        case LpStatus.Infeasible | LpStatus.InfeasibleOrUnbounded => Double.NaN
        case LpStatus.Unbounded => if (problem.sense == Minimize) Double.NegativeInfinity else Double.PositiveInfinity
        case _ => objective.getOrElse(Double.NaN)
      }
      val frame = problem.spark.createDataFrame(rows, ConstraintDiagnostics.schema).persist()
      diagnosticsFrame = Some(frame)
      frame.count()
      val candidate = CandidateInfo(raw.values.nonEmpty, feasible, if (raw.values.nonEmpty) Some(raw.iterations) else None)
      val backend = Some(LpBackendSummary(adapter.name, raw.diagnostics, raw.bestBound, raw.values.nonEmpty))
      val timings = timing.finish()
      new LpSolution(raw.status, value, raw.iterations, LpResiduals(violation, Double.NaN, Double.NaN), timings,
        frame, problem, values, candidate, reducedCostData = costs, backend = backend, start = raw.start)
    } catch {
      case scala.util.control.NonFatal(e) =>
        values.unpersist(false); costs.foreach(_.unpersist(false)); diagnosticsFrame.foreach(_.unpersist(false)); throw e
    }
  }
}
