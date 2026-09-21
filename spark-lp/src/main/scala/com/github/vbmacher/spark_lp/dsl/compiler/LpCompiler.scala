package com.github.vbmacher.spark_lp.dsl.compiler

import com.github.vbmacher.spark_lp.{CachedRDDs, CandidateInfo, LP, Numerics, SolveControl, StopReason}
import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.vectors.{DMatrix, DVector}
import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.DoubleType
import org.apache.spark.sql.{AnalysisException, SparkSession}

import scala.collection.mutable
import scala.util.hashing.MurmurHash3

/**
  * Compiles a declarative [[LpProblem]] into the solver's equality form (`minimize c^T x` subject
  * to `Ax = b`, `x >= 0`) and reconstructs user-facing values from the solver iterate.
  *
  * Compilation is deterministic for fixed source data: variable sets are ordered by
  * `(set creation order, encoded key)`, generated rows by `(constraint creation order, encoded
  * group key)`. The coefficient matrix exists only as the solver's `DMatrix` of sparse rows; no
  * dense `n x m` structure or DataFrame pivot is ever materialised.
  */
private[dsl] final class LpCompiler(problem: LpProblem, config: SolveConfig,
  clock: LpSolveClock = LpSolveClock.system) extends AutoCloseable {
  import LpCompiler.{BoundBlock, notFinite, relativeBoundViolation, relativeRowViolation}

  /** Tolerance for presolved zero-term rows (`0 <sense> rhs`). */
  private val PresolveTolerance = 1e-11

  /** Relative tolerance when comparing normalised right-hand sides of duplicate rows. */
  private val RhsMatchTolerance = 1e-9

  private implicit val spark: SparkSession = problem.spark
  private val sc = spark.sparkContext
  private val caches = new CachedRDDs
  private val timing = new LpSolveTiming(clock)
  private var propagationPasses = 0
  private var boundReductions = Vector.empty[LpBoundReduction]
  private var substitutions = Map.empty[Int, LpSubstitution]
  private var separableInteger = false
  private var inferredBounds = Map.empty[Int, Map[String, LpBounds]]
  private var preparedStart: Option[PreparedStart] = None

  private def unusedStart(detail: String): Unit = preparedStart.filter(_.usable).foreach { start =>
    start.summary = start.summary.copy(disposition = LpStartDisposition.Unsupported, detail = detail)
  }
  private[dsl] def startApplied(): Unit = preparedStart.foreach(_.applied())
  private[dsl] def startIncumbent: Option[(RDD[((Int, String), Double)], Double, Double)] =
    preparedStart.filter(s => s.usable && s.summary.complete && s.summary.feasible).flatMap { s =>
      s.objective.map { objective => s.applied(incumbent = true); (s.values, objective, s.maxViolation) }
    }

  /** Map original-coordinate hints into the exact partition/column order, including row slacks. */
  private[dsl] def startPrimal(compiled: Compiled): Option[DVector] = preparedStart.filter(_.usable).flatMap { start =>
    import com.github.vbmacher.spark_lp.vectors.dmatrix.implicits._
    val hints = compiled.sortedCols.filter(_._2.setIndex >= 0)
      .map { case (g, c) => (c.setIndex -> c.enc) -> (g, c.kind, c.shift) }.join(start.values)
      .map { case (_, ((g, kind, shift), value)) =>
        val transformed = kind match {
          case 0 => value - shift
          case 1 => math.max(0.0, value)
          case 2 => math.max(0.0, -value)
          case 4 => shift - value
          case _ => 0.0
        }
        g -> transformed
      }
    val raw = caches.cache(compiled.sortedCols.leftOuterJoin(hints, compiled.sortedCols.partitioner.get)
      .mapPartitions(it => Iterator.single(new DenseVector(it.toArray.sortBy(_._1).map {
        case (_, (column, hint)) => if (column.kind == 3) 0.0 else hint.getOrElse(1.0)
      }))))
    if (raw.filter(v => v.values.exists(x => !java.lang.Double.isFinite(x))).take(1).nonEmpty) {
      start.summary = start.summary.copy(disposition = LpStartDisposition.Rejected, detail = "Start overflows solver coordinates", used = false)
      None
    } else {
      val activity = compiled.AT.adjointProduct(raw).values
      val rhs = compiled.b.values
      val floor = start.config.interiorFloor
      val result = compiled.sortedCols.zipPartitions(raw) { (columns, blocks) =>
        val values = blocks.next().values
        Iterator.single(new DenseVector(columns.zipWithIndex.map { case ((_, column), i) =>
          val value = if (column.kind == 3) {
            val coefficients = column.vector.toSparse
            val row = coefficients.indices.head
            (rhs(row) - activity(row)) / coefficients.values.head
          } else values(i)
          math.max(floor, value)
        }.toArray))
      }
      Some(caches.cache(result))
    }
  }

  private[dsl] def newtonSolver(rows: Int): NewtonSolver =
    if (problem.sosGroups.nonEmpty && config.newtonSolver == NewtonSolver.Auto) NewtonSolver.ConjugateGradient
    else config.resolvedNewtonSolver(rows)

  private def inferIntegerBounds(): Option[String] = {
    val needsInference = problem.handles.exists { h =>
      h.category == Integer && (h.metadata.bounds +: h.metadata.members.values.toVector)
        .exists(b => b.lower.isNegInfinity || b.upper.isEmpty)
    }
    val full = config.presolve.enabled && config.presolve.effort == PresolveEffort.Full
    if ((!needsInference && !full) || !config.presolve.enabled || problem.constraints.isEmpty || config.relaxIntegrality) None
    else {
      problem.handles.foreach { h =>
        LpVariableEditing.validate(h.metadata.bounds, h.category)
        h.metadata.members.values.foreach(LpVariableEditing.validate(_, h.category))
      }
      val propagation = LpBoundPropagation.run(problem.inspect, config.boundInference)
      try {
        propagationPasses = propagation.passes
        if (propagation.contradiction.isEmpty) {
          separableInteger = needsInference && problem.sosGroups.isEmpty && problem.quadratic.isEmpty &&
            problem.inspect.coefficients.map(c => c.row -> 1L)
            .reduceByKey(_ + _).filter(_._2 > 1L).take(1).isEmpty
          val selected = if (separableInteger || full) propagation.variables else propagation.variables.filter(_.category == Integer)
          val inferred = selected.take(config.boundInference.maxLocalChanges + 1)
          if (inferred.length > config.boundInference.maxLocalChanges)
            fail(s"Integer bound inference exceeds maxLocalChanges=${config.boundInference.maxLocalChanges}")
          boundReductions = inferred.toVector.flatMap { v =>
            val original = problem.handles(v.id.family).metadata.at(v.id.key)
            val implied = LpBounds(v.lower, v.upper)
            if (original == implied) None else Some(LpBoundReduction(v.id, original, implied))
          }
          inferredBounds = inferred.groupBy(_.id.family).map { case (family, variables) =>
            family -> variables.map(v => v.id.key -> LpBounds(v.lower, v.upper)).toMap
          }
        }
        propagation.contradiction
      } finally propagation.close()
    }
  }

  private def analyticalMip(status: LpStatus, objective: Double): Option[MipSummary] =
    if (config.relaxIntegrality || !problem.handles.exists(_.category != Continuous) && problem.sosGroups.isEmpty) None
    else if (status == LpStatus.Optimal) Some(MipSummary(Some(objective), Some(objective), Some(0.0), Some(0.0), 0, 0, "SearchExhausted"))
    else Some(MipSummary(None, None, None, None, 0, 0, "AnalyticalUnbounded"))

  private def infeasibleFromBounds(detail: String): LpSolution = {
    timing.startReconstruction()
    unusedStart("Presolve establishes infeasibility before iterative initialization")
    val rows = problem.inspect.constraints.map(r => ConstraintDiagnostics.row(r.name,
      if (r.group.isEmpty) null else r.group.mkString(","), Double.NaN, r.sense, r.rhs, null,
      detail, "No optimal continuous LP dual available"))
    val statistics = problem.inspect.statistics()
    val constraints = spark.createDataFrame(rows, ConstraintDiagnostics.schema)
    val mip = if (problem.handles.exists(_.category != Continuous))
      Some(MipSummary(None, None, None, None, 0, 0, "PresolveInfeasible")) else None
    val presolve = Some(LpPresolveSummary(config.presolve.enabled, config.presolve.effort, propagationPasses,
      statistics.variables, statistics.constraints.toInt, 0, 0, 0, 0, 0, boundReductions, Vector.empty))
    val start = preparedStart.map(_.summary)
    val timings = timing.finish()
    new LpSolution(LpStatus.Infeasible, Double.NaN, 0, LpResiduals(Double.NaN, Double.NaN, Double.NaN), timings,
      constraints, problem, sc.emptyRDD, CandidateInfo.Unavailable, isRelaxation = config.relaxIntegrality,
      mip = mip, presolve = presolve, start = start)
  }

  override def close(): Unit = try caches.close() finally preparedStart.foreach(_.close())

  /** Raises an [[LpModelException]] carrying `message`; never returns. */
  private def fail(message: String): Nothing = throw new LpModelException(message)

  /**
    * Compiles the problem and dispatches to the matching solver path: an all-fixed model with no
    * columns is checked for feasibility directly, a purely continuous model is handed to
    * [[LP.solveSummary]], and a model with integral columns is solved by [[BranchAndBound]].
    * `stopAfterIteration` and a non-default [[SolveControl]] are rejected for discrete models. The
    * compiler and its cached RDDs are always released when the call returns.
    */
  def solve(): LpSolution = try {
    timing.start()
    preparedStart = config.start.map(LpStartProcessing.prepare(problem, _, CandidateValidationConfig(
      tolerance = config.tolerance, integralityTolerance = config.mip.integralityTolerance,
      relaxIntegrality = config.relaxIntegrality, sosZeroTolerance = config.mip.sosZeroTolerance)))
    inferIntegerBounds().foreach(reason => return infeasibleFromBounds(reason))
    val compiled = compile()
    if (compiled.direct.nonEmpty || compiled.numCols == 0) unusedStart("Analytical solve does not use an iterative initialization")
    if (compiled.plans.exists(_.integral) &&
      (config.stopAfterIteration.nonEmpty || config.control != SolveControl()))
      fail("stopAfterIteration and SolveControl are supported only for continuous models")
    if (compiled.direct.nonEmpty) {
      timing.startReconstruction()
      val direct = compiled.direct.get
      val restored = restoreSubstitutions(compiled, direct.values)
      val violation = originalValuesViolation(compiled, restored)
      buildSolution(compiled, sc.emptyRDD,
        if (direct.unbounded) LpStatus.Unbounded else LpStatus.Optimal,
        direct.objectiveValue, 0,
        if (direct.unbounded) LpResiduals(0.0, Double.NaN, Double.NaN) else LpResiduals(0.0, 0.0, 0.0),
        Map.empty, Some(CandidateInfo(true, violation <= config.control.feasibilityTolerance, Some(0))), originalValues = Some(restored),
        mip = analyticalMip(if (direct.unbounded) LpStatus.Unbounded else LpStatus.Optimal, direct.objectiveValue))
    } else if (compiled.numCols == 0) {
      timing.startReconstruction()
      val feasible = compiled.rowSpecs.forall { row =>
        row.sense.violation(-row.b0) <= config.control.feasibilityTolerance * (1.0 + math.abs(row.rhsUser))
      }
      buildSolution(compiled, sc.emptyRDD, LpStatus.Optimal, compiled.objConstant, 0,
        LpResiduals(0.0, 0.0, 0.0), Map.empty, Some(CandidateInfo(true, feasible, Some(0))), mip = analyticalMip(LpStatus.Optimal, compiled.objConstant))
    } else if (compiled.intCols.isEmpty) {
      timing.startNumericalSolve()
      var convergedDual: Option[DenseVector] = None
      val summary = LP.solveSummary(
        c = compiled.c,
        AT = compiled.AT,
        b = compiled.b,
        tolerance = config.tolerance,
        maxIter = config.maxIterations,
        etaIter = config.etaIteration,
        valueCap = config.valueCap,
        eps = config.epsilon,
        infeasibilityTolerance = config.infeasibilityTolerance,
        solver = if (problem.quadratic.exists(_.factors.nonEmpty) && compiled.plans.exists(_.kind == SplitKind))
          NewtonSolver.ConjugateGradient else newtonSolver(compiled.numRows),
        cgTolerance = config.cgTolerance,
        cgConfig = config.cgConfig,
        cgMaxIterations = config.cgMaxIterations,
        stopAfterIteration = config.stopAfterIteration,
        control = config.control.copy(onProgress = {
          event =>
            config.control.onProgress(event.copy(iterate = event.iterate.map(metrics =>
              metrics.copy(objectiveValue = compiled.senseMult * metrics.objectiveValue + compiled.objConstant))))
        }),
        candidateViolation = Some(x => originalViolation(compiled, x)),
        inspectConverged = Some((_, multipliers, _) =>
          convergedDual = Some(new DenseVector(multipliers.values.clone()))),
        quadratic = compiled.quadratic,
        initialPrimal = startPrimal(compiled), onStartApplied = () => startApplied())
      timing.startReconstruction()
      try continuousSolution(compiled, summary, convergedDual)
      finally summary.x.unpersist(blocking = false)
    } else {
      if (config.stopAfterIteration.nonEmpty)
        fail("stopAfterIteration is supported only for continuous models")
      timing.startNumericalSolve()
      new BranchAndBound(this, compiled, config).solve()
    }
  } finally close()

  private final class LoweredProblem(
    val objective: LpExpr,
    val factors: Seq[(LpExpr, Double)],
    val auxiliaryHandles: Seq[VarSetHandle],
    val diagonal: LpExpr,
    val constraints: Seq[Either[LpConstraint, LpConstraintSet]])

  private final class PlannedVariables(val plans: IndexedSeq[SetPlan], val columns: Long)

  private final class ExpandedRows(
    val rowSpecs: mutable.ArrayBuffer[RowSpec],
    val termPieces: mutable.ArrayBuffer[RDD[((Int, String, Int), Double)]])

  private def lowerProblem(): LoweredProblem = {
    val objective = problem.objective.getOrElse(LpExpr.zero)
    if (notFinite(objective.constant))
      fail(s"Problem '${problem.name}': non-finite objective constant ${objective.constant}")

    // Exact sparse lifting: for each w*(a^T*x+t)^2 introduce u,v >= 0,
    // a^T*x+t = u-v, and w*(u^2+v^2). Minimizing over u,v recovers
    // w*(a^T*x+t)^2 because the optimum has min(u,v)=0. No Hessian is formed.
    val factors = problem.quadratic.toSeq.flatMap(_.factors).filter(_._2 != 0.0)
    val objectiveSign = if (problem.sense == Maximize) -1.0 else 1.0
    factors.foreach { case (_, weight) =>
      if (notFinite(weight) || weight * objectiveSign < 0.0)
        fail("Quadratic factor weights must be finite and convex in minimization form")
    }
    val factorHandles = factors.indices.flatMap { i =>
      Seq("positive", "negative").zipWithIndex.map { case (sign, j) =>
        new VarSetHandle(problem, problem.handles.size + 2 * i + j,
          s"__qp_factor_${i}_$sign", 0.0, None, Continuous, new ScalarDomain(spark))
      }
    }
    if (problem.sosGroups.nonEmpty && problem.quadratic.nonEmpty)
      fail("SOS groups support linear objectives only")
    val (sosHandles, sosConstraints) = LpSos.lower(problem, problem.handles.size + factorHandles.size)
    val auxiliaryHandles = factorHandles ++ sosHandles
    val diagonal = problem.quadratic.map(_.diagonal).getOrElse(LpExpr.zero).plus(
      new LpExpr(factors.zipWithIndex.flatMap { case ((_, weight), i) =>
        Seq(ConstCoeffTerm(auxiliaryHandles(2 * i), 2 * weight),
          ConstCoeffTerm(auxiliaryHandles(2 * i + 1), 2 * weight))
      }.toVector, 0.0))
    val factorConstraints: Seq[Either[LpConstraint, LpConstraintSet]] =
      factors.zipWithIndex.map { case ((expression, _), i) =>
        val equation = expression.plus(auxiliaryHandles(2 * i).toExpr(-1.0))
          .plus(auxiliaryHandles(2 * i + 1).toExpr(1.0))
        Left(equation.compare(LpSense.Eq, 0.0).withName(s"__qp_factor_$i"))
      }
    new LoweredProblem(objective, factors, auxiliaryHandles, diagonal,
      problem.constraints.toVector ++ factorConstraints ++ sosConstraints)
  }

  private def planVariables(lowered: LoweredProblem): PlannedVariables = {
    if (!config.relaxIntegrality && problem.quadratic.nonEmpty && problem.handles.exists(_.category != Continuous))
      fail("Quadratic objectives support continuous variables only; integer and binary categories are unsupported")
    val plans = (problem.handles.toVector ++ lowered.auxiliaryHandles).map(buildPlan).toIndexedSeq
    if (problem.handles.exists(_.metadata.names.nonEmpty)) {
      val names = sc.union(plans.take(problem.handles.size).map { p =>
        val metadata = p.handle.metadata
        p.keys.map { case (key, display) => metadata.display(key, display) -> 1 }
      })
      if (names.reduceByKey(_ + _).filter(_._2 > 1).take(1).nonEmpty)
        fail("Duplicate expanded variable names after renaming")
    }
    if (lowered.factors.nonEmpty && plans.exists(_.kind == SplitKind) &&
      config.newtonSolver == NewtonSolver.Cholesky)
      fail("Coupled QP with free variables requires regularized ConjugateGradient; use Auto or ConjugateGradient")
    var colCursor = 0L
    plans.foreach { plan =>
      plan.offset = colCursor
      colCursor += plan.columns
    }
    new PlannedVariables(plans, colCursor)
  }

  private def expandRows(lowered: LoweredProblem, plans: IndexedSeq[SetPlan]): ExpandedRows = {
    val rowSpecs = mutable.ArrayBuffer.empty[RowSpec]
    val termPieces = mutable.ArrayBuffer.empty[RDD[((Int, String, Int), Double)]]
    val firstInternalConstraint = problem.constraints.size + lowered.factors.size

    lowered.constraints.zipWithIndex.foreach {
      case (Left(constraint), idx) =>
        val cname = constraint.explicitName.getOrElse(s"_c$idx")
        val context = s"constraint '$cname'"
        validateRhs(constraint.rhs, context)
        checkRowBudget(rowSpecs.size + 1L, context)
        val rowId = rowSpecs.size
        rowSpecs += new RowSpec(rowId, cname, None, constraint.sense, constraint.rhs,
          internalBound = idx >= firstInternalConstraint)
        constraint.terms.foreach { term =>
          termPieces += expandTerm(plans, term, rowId, context)
        }
      case (Right(constraintSet), idx) =>
        val base = constraintSet.explicitName.getOrElse(s"_c$idx")
        expandBulk(constraintSet, base, plans, rowSpecs, termPieces)
    }

    // Per-member bounds use a common family transformation plus explicit original-coordinate rows.
    // Only declarations whose bounds differ from that envelope need extra rows.
    val memberBoundTerms = mutable.ArrayBuffer.empty[((Int, String, Int), Double)]
    plans.filter(_.metadata.members.nonEmpty).foreach { plan =>
      val metadata = plan.metadata
      val envelope = metadata.envelopeFor(Some(plan.count))
      plan.keys.toLocalIterator.foreach { case (key, _) =>
        val bounds = metadata.at(key)
        Vector((bounds.lower > envelope.lower, LpSense.Ge, bounds.lower),
          (bounds.upper.exists(u => envelope.upper.forall(_ > u)), LpSense.Le, bounds.upper.getOrElse(0.0)))
          .filter(_._1).foreach { case (_, sense, rhs) =>
            checkRowBudget(rowSpecs.size + 1L, "member variable bounds")
            val id = rowSpecs.size
            rowSpecs += new RowSpec(id, s"__member_bound_$id", None, sense, rhs, internalBound = true)
            memberBoundTerms += (((plan.handle.setIndex, key, id), 1.0))
          }
      }
    }
    if (memberBoundTerms.nonEmpty) termPieces += sc.parallelize(memberBoundTerms.toVector)

    val dupNames = rowSpecs.groupBy(_.name).filter(_._2.size > 1).keys.take(5).toSeq
    if (dupNames.nonEmpty)
      fail(s"Duplicate constraint names after expansion: ${dupNames.mkString(", ")}")
    new ExpandedRows(rowSpecs, termPieces)
  }

  // -------------------------------------------------------------------------------------------
  // Compilation
  // -------------------------------------------------------------------------------------------

  /**
    * Lowers the declarative problem into equality form (`minimize c^T x` s.t. `Ax = b`, `x >= 0`).
    * Builds per-set column plans, expands constraint and objective terms into distributed
    * `(setIndex, key, row) -> coefficient` RDDs, folds fixed variables and lower-bound shifts into the
    * RHS and objective constant, presolves away trivially satisfied rows, merges consistent duplicate
    * equality rows, appends upper-bound and slack columns, and assembles the sparse solver columns
    * (`AT`), cost vector (`c`) and RHS (`b`). Every source RDD is read once per solve and the
    * coefficient matrix is never densified.
    */
  private[dsl] def compile(): Compiled = {
    val lowered = lowerProblem()
    val objective = lowered.objective
    val diagonal = lowered.diagonal
    val planned = planVariables(lowered)
    val plans = planned.plans
    var colCursor = planned.columns
    var numUserCols = colCursor
    val expanded = expandRows(lowered, plans)
    val rowSpecs = expanded.rowSpecs
    val termPieces = expanded.termPieces

    // --- aggregated user-space terms (repeated terms in one expression sum, per linear algebra)
    val emptyTerms: RDD[((Int, String, Int), Double)] = sc.emptyRDD
    val userTermsAgg = caches.cache(
      (if (termPieces.isEmpty) emptyTerms else sc.union(termPieces))
        .reduceByKey(_ + _)
        .filter(_._2 != 0.0)
    )

    val emptyObj: RDD[((Int, String), Double)] = sc.emptyRDD
    val objPieces = objective.terms.map { term =>
      expandTerm(plans, term, -1, "objective").map { case ((si, enc, _), c) => ((si, enc), c) }
    }
    val linearTerms = caches.cache(
      (if (objPieces.isEmpty) emptyObj else sc.union(objPieces))
        .reduceByKey(_ + _)
        .filter(_._2 != 0.0)
    )

    val senseMultQ = if (problem.sense == Maximize) -1.0 else 1.0
    val curvaturePieces = diagonal.terms.map { term =>
      expandTerm(plans, term, -1, "quadratic curvature").map { case ((si, key, _), q) => ((si, key), q) }
    }
    val curvature = caches.cache((if (curvaturePieces.isEmpty) emptyObj else sc.union(curvaturePieces))
      .reduceByKey(_ + _).filter(_._2 != 0.0))
    curvature.filter { case (_, q) => notFinite(q) || senseMultQ * q < 0.0 }.take(1).foreach {
      case ((si, key), q) => fail(s"Invalid curvature $q for variable '${plans(si).handle.name}' (key $key): objective must be convex in minimization form")
    }
    val freeSets = plans.filter(_.kind == SplitKind).map(_.handle.setIndex).toSet
    if (curvature.filter(x => freeSets(x._1._1)).take(1).nonEmpty)
      fail("Curved free variables are unsupported by separable QP; supply a finite lower bound")
    if (config.presolve.enabled && config.presolve.effort == PresolveEffort.Full &&
      problem.quadratic.isEmpty && config.presolve.maxSubstitutions > 0) {
      val free = plans.filter(p => p.handle.category == Continuous && !p.kind.isInstanceOf[FixedKind])
        .map(p => p.handle.setIndex -> p.handle.metadata).toMap
      val equalities = rowSpecs.filter(r => r.sense == LpSense.Eq && !r.internalBound).map(_.rowId).toSet
      val singleton = userTermsAgg.map { case ((si, key, row), value) => ((si, key), (1L, row, value)) }
        .reduceByKey { case ((n, row, value), (m, _, _)) => (n + m, row, value) }
        .filter { case ((si, key), (count, row, _)) =>
          free.get(si).exists(m => m.at(key).lower.isNegInfinity && m.at(key).upper.isEmpty) && count == 1 && equalities(row)
        }
        .leftOuterJoin(linearTerms).filter(_._2._2.forall(_ == 0.0))
        .map { case ((si, key), ((_, row, coefficient), _)) => row -> (si, key, coefficient) }
        .reduceByKey { (a, b) => if (a._1 < b._1 || a._1 == b._1 && a._2 < b._2) a else b }
        .sortByKey().take(config.presolve.maxSubstitutions)
      substitutions = singleton.map { case (row, (si, key, a)) =>
        row -> LpSubstitution(LpVariableId(si, key), rowSpecs(row).name, a, rowSpecs(row).rhsUser)
      }.toMap
      substitutions.foreach { case (row, substitution) =>
        plans(substitution.variable.family).excluded += substitution.variable.key
        rowSpecs(row).emitted = false
        rowSpecs(row).note = Some("presolved: free zero-cost singleton column substitution")
      }
      colCursor = 0L
      plans.foreach { p => p.offset = colCursor; colCursor += p.columns }
      numUserCols = colCursor
    }
    val shifts = plans.map { p => p.kind match {
      case FixedKind(v) => p.handle.setIndex -> v
      case ShiftedKind(v, _) => p.handle.setIndex -> v
      case ReflectedKind(v) => p.handle.setIndex -> v
      case _ => p.handle.setIndex -> 0.0
    }}.toMap
    // c' = c + Q*l, k' = k + c*l + 0.5*l^T Q*l.
    val objTerms = caches.cache(linearTerms.union(curvature.map { case (key, q) =>
      (key, q * shifts(key._1))
    }).reduceByKey(_ + _))
    val quadraticConstantCorrection = curvature.map { case ((si, _), q) =>
      -0.5 * q * shifts(si) * shifts(si)
    }.sum()
    val hasCurvature = curvature.take(1).nonEmpty

    val rowNames = sc.broadcast(rowSpecs.map(_.name).toArray)
    val handleNames = sc.broadcast(plans.map(_.handle.name).toArray)
    userTermsAgg.filter { case (_, v) => notFinite(v) }.take(1).foreach {
      case ((si, enc, r), v) =>
        fail(s"Non-finite coefficient $v in constraint '${rowNames.value(r)}' " +
          s"for variable '${handleNames.value(si)}' (key $enc)")
    }
    objTerms.filter { case (_, v) => notFinite(v) }.take(1).foreach {
      case ((si, enc), v) =>
        fail(s"Non-finite coefficient $v in the objective for variable '${handleNames.value(si)}' (key $enc)")
    }

    // --- fixed-variable presolve and lower-bound shifts fold into the RHS and objective constant
    val fixedVals = plans.flatMap { p =>
      p.kind match {
        case FixedKind(v) => Some(p.handle.setIndex -> v)
        case _ => None
      }
    }.toMap
    val shiftVals = plans.flatMap { p =>
      p.kind match {
        case ShiftedKind(shift, _) if shift != 0.0 => Some(p.handle.setIndex -> shift)
        case ReflectedKind(upper) => Some(p.handle.setIndex -> upper)
        case _ => None
      }
    }.toMap

    val adjust = sc.broadcast((fixedVals, shiftVals))
    val rowAdjust = userTermsAgg.flatMap { case ((si, _, r), c) =>
      val (fixed, shifted) = adjust.value
      fixed.get(si).orElse(shifted.get(si)).map(l => (r, c * l))
    }.reduceByKey(_ + _).collectAsMap()
    rowSpecs.foreach { rs =>
      rs.b0 = rs.rhsUser - rowAdjust.getOrElse(rs.rowId, 0.0)
      validateRhs(rs.b0, s"constraint '${rs.name}' after bound shifts")
    }

    val objAdjust = objTerms.map { case ((si, _), c) =>
      val (fixed, shifted) = adjust.value
      fixed.get(si).orElse(shifted.get(si)).map(l => c * l).getOrElse(0.0)
    }.sum()
    val objConstant = objective.constant + objAdjust + quadraticConstantCorrection
    if (notFinite(objConstant)) fail("Non-finite objective after bound shifts")

    val fixedSetIdx = sc.broadcast(fixedVals.keySet)
    val substitutedRows = substitutions.keySet
    val solverTerms = caches.cache(userTermsAgg.filter { case ((si, _, row), _) =>
      !fixedSetIdx.value.contains(si) && !substitutedRows(row)
    })

    // --- zero-term rows: trivially satisfied rows are presolved away, infeasible ones rejected
    val liveRows = solverTerms.map(_._1._3).distinct().collect().toSet
    rowSpecs.foreach { rs =>
      if (rs.emitted && !liveRows.contains(rs.rowId)) {
        val feasible = rs.sense.violation(-rs.b0) <= PresolveTolerance
        if (!feasible) {
          fail(s"Constraint '${rs.name}' has no remaining terms but requires 0 ${rs.sense.symbol} ${rs.b0} " +
            "(after presolve) — trivially infeasible")
        }
        rs.emitted = false
        rs.note = Some("presolved: no remaining terms; trivially satisfied")
      }
    }

    // --- duplicate equality rows: consistent duplicates merge, conflicting ones fail
    if (config.presolve.enabled) detectDuplicateRows(rowSpecs, solverTerms)

    // With no active rows, the linear objective separates over validated domains.
    if (!hasCurvature && (rowSpecs.forall(!_.emitted) || separableInteger)) {
      val sign = if (problem.sense == Maximize) -1.0 else 1.0
      val pieces = plans.map { p =>
        val si = p.handle.setIndex
        val (lower, upper) = p.kind match {
          case FixedKind(v) => (Some(v), Some(v))
          case ShiftedKind(v, u) => (Some(v), u)
          case SplitKind => (None, None)
          case ReflectedKind(upper) => (None, Some(upper))
        }
        val metadata = p.metadata
        val useInferred = separableInteger
        p.activeKeys.map { case (key, _) =>
          if (useInferred) {
            val bounds = metadata.at(key)
            ((si, key), (if (bounds.lower.isNegInfinity) None else Some(bounds.lower), bounds.upper))
          } else ((si, key), (lower, upper))
        }
      }
      val domains: RDD[((Int, String), (Option[Double], Option[Double]))] =
        if (pieces.isEmpty) sc.emptyRDD else sc.union(pieces)
      val choices = caches.cache(domains.leftOuterJoin(linearTerms).mapValues {
        case ((lower, upper), cost) =>
          val c = cost.getOrElse(0.0)
          val target = if (sign * c > 0.0) lower else if (sign * c < 0.0) upper
            else Some(math.max(lower.getOrElse(0.0), math.min(upper.getOrElse(0.0), 0.0)))
          val value = target.getOrElse(lower.orElse(upper).getOrElse(0.0))
          (value, c * value, target.isEmpty)
      })
      val unbounded = choices.filter(_._2._3).take(1).nonEmpty
      val directObjective = if (unbounded) -sign * Double.PositiveInfinity
        else objective.constant + choices.map(_._2._2).sum()
      if (!unbounded && (directObjective.isNaN || directObjective.isInfinity))
        fail("Non-finite objective in rowless model")
      return new Compiled(sc.emptyRDD, sc.emptyRDD, new DenseVector(Array.emptyDoubleArray),
        0, 0L, sc.emptyRDD, rowSpecs.toIndexedSeq, plans, objConstant, sign, userTermsAgg,
        IndexedSeq.empty, originalCosts = Some(linearTerms), originalCurvature = Some(curvature),
        direct = Some(DirectResult(choices.mapValues(_._1), directObjective, unbounded)), substitutions = substitutions)
    }

    var rowCursor = 0
    rowSpecs.foreach { rs =>
      if (rs.emitted) {
        rs.finalIdx = rowCursor
        rowCursor += 1
      }
    }

    // --- one extra row per finitely upper-bounded variable: y + s = u - l
    val boundBlocks = mutable.ArrayBuffer.empty[BoundBlock]
    plans.foreach { p =>
      p.kind match {
        case ShiftedKind(shift, Some(upper)) =>
          validateRhs(upper - shift, s"upper bound of variable '${p.handle.name}' after bound shift")
          checkRowBudget(rowCursor + p.activeCount,
            s"upper bound rows of variable set '${p.handle.name}' (+${p.activeCount} rows)")
          boundBlocks += BoundBlock(p, rowCursor, upper - shift)
          rowCursor += p.activeCount.toInt
        case _ => ()
      }
    }
    val numRows = if (hasCurvature) math.max(1, rowCursor) else rowCursor
    if (numUserCols == 0 && plans.exists(_.count > 0)) {
      return new Compiled(sc.emptyRDD, sc.emptyRDD, new DenseVector(Array.emptyDoubleArray),
        0, 0L, sc.emptyRDD, rowSpecs.toIndexedSeq, plans, objConstant,
        if (problem.sense == Maximize) -1.0 else 1.0, userTermsAgg, IndexedSeq.empty, substitutions = substitutions)
    }
    if (numRows == 0) {
      fail(s"Problem '${problem.name}' compiled to an empty model: all constraint rows were presolved away")
    }

    val bArray = new Array[Double](numRows)
    if (rowCursor == 0 && hasCurvature) bArray(0) = 1.0
    rowSpecs.foreach { rs => if (rs.emitted) bArray(rs.finalIdx) = rs.b0 }
    boundBlocks.foreach { bb =>
      var i = 0
      while (i < bb.plan.activeCount) {
        bArray(bb.rowBase + i) = bb.rhs
        i += 1
      }
    }

    // --- internal slack variables, appended after all user columns
    var slackCursor = numUserCols
    val slacks = mutable.ArrayBuffer.empty[(Long, (Int, Double))]
    rowSpecs.filter(_.emitted).sortBy(_.finalIdx).foreach { rs =>
      rs.sense match {
        case LpSense.Le => slacks += ((slackCursor, (rs.finalIdx, 1.0))); slackCursor += 1
        case LpSense.Ge => slacks += ((slackCursor, (rs.finalIdx, -1.0))); slackCursor += 1
        case LpSense.Eq => ()
      }
    }
    boundBlocks.foreach { bb =>
      var i = 0
      while (i < bb.plan.activeCount) {
        slacks += ((slackCursor, (bb.rowBase + i, 1.0)))
        slackCursor += 1
        i += 1
      }
    }
    if (rowCursor == 0 && hasCurvature) {
      slacks += ((slackCursor, (0, 1.0)))
      slackCursor += 1
    }
    val numCols = slackCursor
    if (numCols == 0) {
      fail(s"Problem '${problem.name}' has no decision variables")
    }

    // --- assemble sparse columns of A (= rows of the transposed DMatrix)
    val rowRemap = sc.broadcast(rowSpecs.filter(_.emitted).map(rs => rs.rowId -> rs.finalIdx).toMap)
    val senseMult = if (problem.sense == Maximize) -1.0 else 1.0

    val basePieces = mutable.ArrayBuffer.empty[RDD[(Long, (Int, String, Byte, Double))]]
    val entryPieces = mutable.ArrayBuffer.empty[RDD[(Long, (Int, Double))]]
    val costPieces = mutable.ArrayBuffer.empty[RDD[(Long, Double)]]

    plans.foreach { p =>
      val si = p.handle.setIndex
      val setTerms = solverTerms
        .filter { case ((s, _, _), _) => s == si }
        .map { case ((_, enc, r), c) => (enc, (r, c)) }
      val setCost = objTerms
        .filter { case ((s, _), _) => s == si }
        .map { case ((_, enc), c) => (enc, c) }

      p.kind match {
        case FixedKind(_) => ()

        case ShiftedKind(shift, _) =>
          val off = p.offset
          val idx = caches.cache(p.sortedKeys).map { case (enc, (i, _)) => (enc, off + i) }
          basePieces += idx.map { case (enc, g) => (g, (si, enc, 0: Byte, shift)) }
          entryPieces += setTerms.join(idx).flatMap { case (_, ((r, c), g)) =>
            rowRemap.value.get(r).map(fr => (g, (fr, c)))
          }
          costPieces += setCost.join(idx).map { case (_, (c, g)) => (g, senseMult * c) }

        case ReflectedKind(upper) =>
          val off = p.offset
          val idx = caches.cache(p.sortedKeys).map { case (enc, (i, _)) => (enc, off + i) }
          basePieces += idx.map { case (enc, g) => (g, (si, enc, 4: Byte, upper)) }
          entryPieces += setTerms.join(idx).flatMap { case (_, ((r, c), g)) =>
            rowRemap.value.get(r).map(fr => (g, (fr, -c)))
          }
          costPieces += setCost.join(idx).map { case (_, (c, g)) => (g, -senseMult * c) }

        case SplitKind =>
          // free variable: x = x_plus - x_minus, both non-negative
          val off = p.offset
          val idx = caches.cache(p.sortedKeys).map { case (enc, (i, _)) => (enc, off + 2 * i) }
          basePieces += idx.flatMap { case (enc, g) =>
            Seq((g, (si, enc, 1: Byte, 0.0)), (g + 1, (si, enc, 2: Byte, 0.0)))
          }
          entryPieces += setTerms.join(idx).flatMap { case (_, ((r, c), g)) =>
            rowRemap.value.get(r).toSeq.flatMap(fr => Seq((g, (fr, c)), (g + 1, (fr, -c))))
          }
          costPieces += setCost.join(idx).flatMap { case (_, (c, g)) =>
            Seq((g, senseMult * c), (g + 1, -senseMult * c))
          }
      }
    }

    boundBlocks.foreach { bb =>
      val off = bb.plan.offset
      val base = bb.rowBase
      entryPieces += bb.plan.sortedKeys.map { case (_, (i, _)) => (off + i, (base + i.toInt, 1.0)) }
    }

    if (slacks.nonEmpty) {
      val slackParts = math.max(1, math.min(sc.defaultParallelism, slacks.size))
      entryPieces += sc.parallelize(slacks, slackParts)
      basePieces += sc.parallelize(slacks.map { case (g, _) => (g, (-1, "", 3: Byte, 0.0)) }, slackParts)
    }

    val emptyEntries: RDD[(Long, (Int, Double))] = sc.emptyRDD
    val emptyCosts: RDD[(Long, Double)] = sc.emptyRDD
    val allBase = sc.union(basePieces)
    val allEntries = if (entryPieces.isEmpty) emptyEntries else sc.union(entryPieces)
    val allCosts = if (costPieces.isEmpty) emptyCosts else sc.union(costPieces)

    val parts = math.max(1, math.min(sc.defaultParallelism.toLong, numCols).toInt)
    val partitioner = new RangeIndexPartitioner(numCols, parts)
    val mLocal = numRows

    val sortedCols: RDD[(Long, ColData)] = caches.cache(allBase.cogroup(allEntries, allCosts, partitioner)
      .mapPartitions({ it =>
        val buffer = it.toArray.sortBy(_._1)
        buffer.iterator.map { case (g, (metas, entries, costs)) =>
          require(metas.nonEmpty, s"column $g has no metadata")
          val (si, enc, kind, shift) = metas.head
          val cost = costs.sum
          val sorted = entries.toArray.sortBy(_._1)
          val indices = mutable.ArrayBuilder.make[Int]
          val values = mutable.ArrayBuilder.make[Double]
          var i = 0
          while (i < sorted.length) {
            var v = sorted(i)._2
            val row = sorted(i)._1
            while (i + 1 < sorted.length && sorted(i + 1)._1 == row) {
              v += sorted(i + 1)._2
              i += 1
            }
            indices += row
            values += v
            i += 1
          }
          (g, ColData(si, enc, kind, shift, cost, Vectors.sparse(mLocal, indices.result(), values.result())))
        }
      }, preservesPartitioning = true)
    )

    val cVec: DVector = caches.cache(sortedCols
      .mapPartitions(it => Iterator.single(new DenseVector(it.map(_._2.cost).toArray)), preservesPartitioning = true)
    )
    val AT: DMatrix = caches.cache(sortedCols.map(_._2.vector))

    val qVector = if (!hasCurvature) None else {
      val keyedCurvature = sortedCols.map { case (g, d) => ((d.setIndex, d.enc), g) }
        .leftOuterJoin(curvature).map { case (_, (g, q)) => (g, senseMultQ * q.getOrElse(0.0)) }
        .repartitionAndSortWithinPartitions(partitioner)
      Some(caches.cache(keyedCurvature.mapPartitions(it =>
        Iterator.single(new DenseVector(it.map(_._2).toArray)), preservesPartitioning = true)))
    }

    // one consistent read of every source per solve, before the solver starts
    val materialisedCols = sortedCols.count()
    require(materialisedCols == numCols, s"expected $numCols columns, materialised $materialisedCols")
    cVec.count()
    AT.count()

    // --- driver-local layout of integral columns for the discrete solver
    val intCols: IndexedSeq[IntColumn] = {
      val intPlans = plans.filter(p => p.integral && p.columns > 0)
      if (intPlans.isEmpty) IndexedSeq.empty
      else {
        val boundRowBase = boundBlocks.map(bb => bb.plan.handle.setIndex -> bb.rowBase).toMap
        val partial = intPlans.flatMap { p =>
          val si = p.handle.setIndex
          val (shift, upper) = p.kind match {
            case ShiftedKind(s, Some(u)) => (s, u)
            case other =>
              throw new IllegalStateException(s"integral plan of '${p.handle.name}' has unexpected kind $other")
          }
          val off = p.offset
          val rowBase = boundRowBase(si)
          // constraint-row coefficients per key; merged/presolved rows are absent from rowRemap
          val coeffRows: Map[String, Map[Int, Double]] = solverTerms
            .filter { case ((s, _, _), _) => s == si }
            .flatMap { case ((_, enc, r), coeff) => rowRemap.value.get(r).map(fr => (enc, (fr, coeff))) }
            .collect()
            .groupBy(_._1)
            .map { case (enc, entries) => enc -> entries.map(_._2).toMap }
          p.sortedKeys.collect().map { case (enc, (i, _)) =>
            IntColumn(off + i, si, enc, shift, upper, 0.0, rowBase + i.toInt,
              coeffRows.getOrElse(enc, Map.empty))
          }
        }
        val gSet = sc.broadcast(partial.map(_.g).toSet)
        val costByG = sortedCols
          .filter { case (g, _) => gSet.value.contains(g) }
          .map { case (g, colData) => (g, colData.cost) }
          .collect().toMap
        partial.map(ic => ic.copy(cost = costByG(ic.g))).sortBy(_.g).toIndexedSeq
      }
    }

    new Compiled(
      c = cVec,
      AT = AT,
      b = new DenseVector(bArray),
      numRows = numRows,
      numCols = numCols,
      sortedCols = sortedCols,
      rowSpecs = rowSpecs.toIndexedSeq,
      plans = plans,
      objConstant = objConstant,
      senseMult = senseMult,
      userTermsAgg = userTermsAgg,
      intCols = intCols,
      substitutions = substitutions,
      quadratic = qVector,
      originalCosts = Some(linearTerms),
      originalCurvature = Some(curvature))
  }

  // -------------------------------------------------------------------------------------------
  // Set plans
  // -------------------------------------------------------------------------------------------

  /**
    * Validates one variable set's declared bounds and domain (finite, non-conflicting bounds and no
    * null or duplicate keys) and derives its [[PlanKind]]: fixed when `lower == upper`, split when the
    * lower bound is `-inf`, otherwise a shifted column carrying the optional finite upper bound.
    */
  private def buildPlan(handle: VarSetHandle): SetPlan = {
    val where = s"variable '${handle.name}'"
    val metadata = handle.metadata.copy(members = handle.metadata.members ++ inferredBounds.getOrElse(handle.setIndex, Map.empty))
    val keys = caches.cache(handle.domain.keyPairs())
    val count = keys.count()
    LpVariableEditing.validate(metadata.bounds, handle.category)
    metadata.members.values.foreach(LpVariableEditing.validate(_, handle.category))
    val envelope = metadata.envelopeFor(Some(count))
    val declaredLb = envelope.lower
    if (declaredLb.isNaN || declaredLb == Double.PositiveInfinity) {
      fail(s"$where: lower bound must not be NaN or +inf (got $declaredLb); Double.NegativeInfinity means a free variable")
    }
    envelope.upper.foreach { ub =>
      if (notFinite(ub)) {
        fail(s"$where: upper bound must be finite when defined (got $ub); unbounded is expressed as None")
      }
    }

    val (lb, upperOpt, integral) = handle.category match {
      case Continuous => (declaredLb, envelope.upper, false)
      case category => integralBounds(new VarSetHandle(problem, handle.setIndex, handle.name, declaredLb, envelope.upper, category, handle.domain), category, where)
    }

    upperOpt.foreach { ub =>
      if (lb > ub) {
        fail(s"$where: lowerBound ($lb) > upperBound ($ub)")
      }
    }

    if (keys.filter(_._1 == null).count() > 0) {
      fail(s"$where: the domain contains null keys")
    }
    val duplicated = keys.map(k => (k._1, 1L)).reduceByKey(_ + _).filter(_._2 > 1).keys.take(5)
    if (duplicated.nonEmpty) {
      val dupSet = duplicated.toSet
      val setName = handle.name
      val displays = keys
        .filter(k => dupSet.contains(k._1))
        .map(k => KeyCodec.displayName(setName, k._2))
        .distinct()
        .take(5)
      fail(s"$where: duplicate variable keys in the domain: ${displays.mkString(", ")}")
    }

    val editedKeys = metadata.members.keySet ++ metadata.names.keySet
    if (editedKeys.nonEmpty && sc.parallelize(editedKeys.toSeq).subtract(keys.keys).take(1).nonEmpty)
      fail(s"$where: an edited member key is absent from the domain")
    val kind =
      if (upperOpt.contains(lb)) FixedKind(lb)
      else if (lb == Double.NegativeInfinity) upperOpt.map(ReflectedKind).getOrElse(SplitKind)
      else ShiftedKind(lb, upperOpt)
    new SetPlan(handle, keys, count, kind, integral, metadata)
  }

  /**
    * Effective integral bounds of an [[Integer]] or [[Binary]] variable: the declared bounds
    * (intersected with `{0, 1}` for Binary) tightened to the enclosed integral range. The result
    * always has finite bounds, so every integral column owns an upper-bound row that the solver can
    * retarget while exploring the discrete model.
    */
  private def integralBounds(
    handle: VarSetHandle,
    category: VariableCategory,
    where: String): (Double, Option[Double], Boolean) = {

    if (category != Binary && problem.constraints.nonEmpty && !separableInteger && !config.relaxIntegrality &&
      (handle.lowerBound.isNegInfinity || handle.upperBound.isEmpty))
      fail(s"$where: could not infer a finite integer interval; the built-in search leaves this model unsupported (no finite cap or relaxation is substituted)")
    val (lo, hiOption) = VariableCategory.domainBounds(category, handle.lowerBound, handle.upperBound)
    val (lower, upperOption) = LpIntegrality.integralBounds(category, lo, hiOption)
    val hi = hiOption.getOrElse(Double.PositiveInfinity)
    val upper = upperOption.getOrElse(Double.PositiveInfinity)
    if (java.lang.Double.isFinite(lower) && math.abs(lower) > 9007199254740991.0 || java.lang.Double.isFinite(upper) && math.abs(upper) > 9007199254740991.0) {
      fail(s"$where: integral bounds must be within +/- (2^53 - 1) for exact Double integers")
    }
    if (lower > upper) {
      val detail = if (category == Binary) " after intersecting the declared bounds with the Binary domain {0, 1}" else ""
      fail(s"$where: no integral values within bounds [$lo, $hi]$detail")
    }
    (lower, if (upper.isPosInfinity) None else Some(upper), !config.relaxIntegrality)
  }

  // -------------------------------------------------------------------------------------------
  // Term expansion
  // -------------------------------------------------------------------------------------------

  /**
    * Expands one symbolic [[LpTerm]] into a distributed `(setIndex, encodedKey, rowId) -> coefficient`
    * RDD, one entry per key in the term's variable set. A constant coefficient broadcasts to every key;
    * column and weighted coefficients read their per-key values from the term's source and are scaled.
    * `rowId` is `-1` for objective terms. Rejects non-finite coefficients and cross-problem variables.
    */
  private def expandTerm(
    plans: IndexedSeq[SetPlan],
    term: LpTerm,
    rowId: Int,
    context: String): RDD[((Int, String, Int), Double)] = {

    if (!(term.handle.problem eq problem)) {
      fail(s"$context references variable '${term.handle.name}' from a different problem")
    }
    val si = term.handle.setIndex
    val rid = rowId
    term match {
      case ConstCoeffTerm(handle, coeff) =>
        if (notFinite(coeff)) {
          fail(s"$context: non-finite coefficient $coeff for variable '${handle.name}'")
        }
        val c = coeff
        plans(si).keys.map { case (enc, _) => ((si, enc, rid), c) }

      case FilteredCoeffTerm(inner, excluded) =>
        expandTerm(plans, inner, rowId, context).filter { case ((_, key, _), _) => !excluded(key) }

      case KeyCoeffTerm(handle, key, coeff) =>
        if (coeff.isNaN || coeff.isInfinite) fail(s"$context: non-finite coefficient for '${handle.name}'")
        val selected = plans(si).keys.filter(_._1 == key)
        if (selected.take(1).isEmpty)
          fail(s"$context: missing or incompatible key '$key' in variable family '${handle.name}'")
        selected.map { case (enc, _) => ((si, enc, rid), coeff) }

      case ColumnCoeffTerm(handle, column, scale) =>
        val pairs = handle.domain.columnPairs(column, s"$context, variable '${handle.name}'")
        val sc0 = scale
        pairs.map { case (enc, v) => ((si, enc, rid), v * sc0) }

      case WeightedCoeffTerm(handle, weights, scale, description) =>
        val w =
          try {
            caches.cache(weights())
          } catch {
            case e: AnalysisException => fail(s"$context: cannot evaluate $description: ${e.getMessage}")
          }
        validateWeights(plans(si), w, description, context)
        val sc0 = scale
        w.map { case (enc, v) => ((si, enc, rid), v * sc0) }
    }
  }

  /**
    * Rejects a weight RDD that contains null keys, duplicate keys (which would be silently summed) or
    * keys absent from the variable set's domain, reporting up to five offending keys.
    */
  private def validateWeights(
    plan: SetPlan,
    weights: RDD[(String, Double)],
    description: String,
    context: String): Unit = {

    if (weights.filter(_._1 == null).count() > 0) {
      fail(s"$context: $description contains null keys")
    }
    val duplicated = weights.mapValues(_ => 1L).reduceByKey(_ + _).filter(_._2 > 1).keys.take(5)
    if (duplicated.nonEmpty) {
      fail(s"$context: duplicate keys in $description: ${duplicated.mkString(", ")}; " +
        "summing them silently would hide data errors — pre-aggregate explicitly if aggregation is intended")
    }
    val foreign = weights.keys.distinct().subtract(plan.keys.map(_._1)).take(5)
    if (foreign.nonEmpty) {
      fail(s"$context: keys in $description that are absent from the domain of '${plan.handle.name}': " +
        foreign.mkString(", "))
    }
  }

  // -------------------------------------------------------------------------------------------
  // Bulk (grouped) constraints
  // -------------------------------------------------------------------------------------------

  /**
    * Expands one grouped (`lpSumBy`) [[LpConstraintSet]] into one constraint row per group key. Reads
    * the term and RHS frames, sums repeated `(group, variable)` coefficients, validates keys against the
    * variable domain and matches each term group to an RHS row, then appends the generated [[RowSpec]]s
    * (ordered by encoded group key for determinism) and their term pieces to the accumulators.
    */
  private def expandBulk(
    constraintSet: LpConstraintSet,
    base: String,
    plans: IndexedSeq[SetPlan],
    rowSpecs: mutable.ArrayBuffer[RowSpec],
    termPieces: mutable.ArrayBuffer[RDD[((Int, String, Int), Double)]]): Unit = {

    val grouped = constraintSet.grouped
    val terms = grouped.terms
    val handle = terms.handle
    val context = s"bulk constraint '$base'"
    if (!(handle.problem eq problem)) {
      fail(s"$context references variable '${handle.name}' from a different problem")
    }
    val byNames = grouped.by
    if (byNames.isEmpty) {
      fail(s"$context: lpSumBy requires at least one grouping column")
    }

    val termsDf =
      try {
        terms.source.select(
          (terms.by :+ terms.key.as("__lp_key") :+ terms.coefficient.cast(DoubleType).as("__lp_coeff")): _*)
      } catch {
        case e: AnalysisException => fail(s"$context: cannot resolve term columns: ${e.getMessage}")
      }
    val groupedDf =
      try {
        termsDf.select((byNames.map(col) :+ col("__lp_key") :+ col("__lp_coeff")): _*)
      } catch {
        case e: AnalysisException =>
          fail(s"$context: cannot resolve grouping columns ${byNames.mkString(", ")}: ${e.getMessage}")
      }

    val nby = byNames.size
    val rawTerms = caches.cache(groupedDf.rdd.map { row =>
      val groupValues = (0 until nby).map(row.get)
      val gEnc = KeyCodec.encodeParts(groupValues)
      val gDisp = groupValues.flatMap(v => KeyCodec.displayParts(v))
      val kEnc = KeyCodec.encodeValue(row.get(nby))
      val coeff = if (row.isNullAt(nby + 1)) Double.NaN else row.getDouble(nby + 1)
      ((gEnc, kEnc), (gDisp, coeff))
    })

    if (rawTerms.filter(_._1._1 == null).count() > 0) {
      fail(s"$context: term rows contain null grouping key parts")
    }
    if (rawTerms.filter(_._1._2 == null).count() > 0) {
      fail(s"$context: term rows contain null variable keys")
    }

    // duplicate (group, variable) pairs sum — the linear-algebra meaning of repeated terms
    val aggTerms = caches.cache(rawTerms.reduceByKey((a, b) => (a._1, a._2 + b._2)))

    val foreign = aggTerms.map(_._1._2).distinct().subtract(plans(handle.setIndex).keys.map(_._1)).take(5)
    if (foreign.nonEmpty) {
      fail(s"$context: term rows reference keys absent from the domain of '${handle.name}': ${foreign.mkString(", ")}")
    }

    val groups = aggTerms
      .map { case ((gEnc, _), (gDisp, _)) => (gEnc, gDisp) }
      .reduceByKey((a, _) => a)
    checkRowBudget(rowSpecs.size.toLong + groups.count(), context)
    val termGroups = groups.collect()

    val groupRows: Seq[(String, Seq[String], Double)] = constraintSet.rhs match {
      case Left(value) =>
        validateRhs(value, context)
        termGroups.map { case (gEnc, gDisp) => (gEnc, gDisp, value) }.toSeq

      case Right(rhsDf) =>
        val rdf =
          try {
            rhsDf.select((byNames.map(col) :+ col("rhs").cast(DoubleType).as("__lp_rhs")): _*)
          } catch {
            case e: AnalysisException =>
              fail(s"$context: cannot resolve the RHS frame (expected columns ${byNames.mkString(", ")} and rhs): ${e.getMessage}")
          }
        checkRowBudget(rowSpecs.size + rdf.count(), context)
        val rhsPairs = rdf.rdd.map { row =>
          val groupValues = (0 until nby).map(row.get)
          val gEnc = KeyCodec.encodeParts(groupValues)
          val gDisp = groupValues.flatMap(v => KeyCodec.displayParts(v))
          val rhs = if (row.isNullAt(nby)) Double.NaN else row.getDouble(nby)
          (gEnc, (gDisp, rhs))
        }.collect()

        if (rhsPairs.exists(_._1 == null)) {
          fail(s"$context: the RHS frame contains null grouping key parts")
        }
        val dupRhs = rhsPairs.groupBy(_._1).filter(_._2.length > 1)
        if (dupRhs.nonEmpty) {
          val names = dupRhs.values.take(5).map(g => KeyCodec.displayName(base, g.head._2._1)).mkString(", ")
          fail(s"$context: duplicate RHS rows for group keys: $names")
        }
        val rhsKeys = rhsPairs.map(_._1).toSet
        val missing = termGroups.filterNot { case (gEnc, _) => rhsKeys.contains(gEnc) }
        if (missing.nonEmpty) {
          val names = missing.take(5).map { case (_, d) => KeyCodec.displayName(base, d) }.mkString(", ")
          fail(s"$context: groups with terms but no RHS row: $names")
        }
        rhsPairs.map { case (gEnc, (gDisp, rhs)) => (gEnc, gDisp, rhs) }.toSeq
    }

    checkRowBudget(rowSpecs.size + groupRows.size.toLong, context)

    // deterministic expansion: rows ordered by encoded group key
    val rowIdByGroup = mutable.HashMap.empty[String, Int]
    groupRows.sortBy(_._1).foreach { case (gEnc, gDisp, rhs) =>
      val rname = KeyCodec.displayName(base, gDisp)
      validateRhs(rhs, s"$context, row '$rname'")
      val rowId = rowSpecs.size
      rowSpecs += new RowSpec(rowId, rname, Some(gDisp.map(KeyCodec.escapePart).mkString(",")), constraintSet.sense, rhs)
      rowIdByGroup(gEnc) = rowId
    }

    val rowMap = sc.broadcast(rowIdByGroup.toMap)
    val si = handle.setIndex
    termPieces += aggTerms.map { case ((gEnc, kEnc), (_, coeff)) => ((si, kEnc, rowMap.value(gEnc)), coeff) }
  }

  // -------------------------------------------------------------------------------------------
  // Duplicate row detection
  // -------------------------------------------------------------------------------------------

  /**
    * Hashes each normalised equality row (coefficients scaled to a canonical leading value).
    * Rows identical in coefficients and RHS are merged into one with a note in diagnostics; rows
    * identical in coefficients but differing in RHS are inconsistent and fail. Inequality rows
    * always receive their own slack column and can never be exact duplicates in equality form.
    */
  private def detectDuplicateRows(
    rowSpecs: mutable.ArrayBuffer[RowSpec],
    solverTerms: RDD[((Int, String, Int), Double)]): Unit = {

    val eqRows = rowSpecs.filter(rs => rs.emitted && rs.sense == LpSense.Eq).map(_.rowId).toSet
    if (eqRows.isEmpty) return

    val eqRowsB = sc.broadcast(eqRows)
    val eqTerms = solverTerms.filter { case ((_, _, r), _) => eqRowsB.value.contains(r) }

    val keyOrdering = implicitly[Ordering[(Int, String)]]
    val leading = eqTerms
      .map { case ((si, enc, r), c) => (r, ((si, enc), c)) }
      .reduceByKey((a, b) => if (keyOrdering.lteq(a._1, b._1)) a else b)
      .mapValues(_._2)
      .collectAsMap()
    val leadingB = sc.broadcast(leading.toMap)

    // Hash each (variable, coefficient) pair together: summing independent hashes would treat
    // permutations of coefficients as identical rows. A hash is only a candidate filter.
    val signatures = eqTerms.map { case ((si, enc, r), c) =>
      val bits = java.lang.Double.doubleToLongBits(c / leadingB.value(r))
      val hash = MurmurHash3.productHash((si, enc, bits)).toLong
      (r, (1L, hash))
    }.reduceByKey((a, b) => (a._1 + b._1, a._2 + b._2)).collectAsMap()

    val grouped = eqRows.toSeq.sorted.map(r => (r, signatures(r))).groupBy(_._2)

    def sameCoefficients(a: Int, b: Int): Boolean = {
      def row(id: Int) = eqTerms.filter(_._1._3 == id).map { case ((si, enc, _), c) =>
        ((si, enc), c / leadingB.value(id))
      }

      row(a).fullOuterJoin(row(b)).filter { case (_, (x, y)) =>
        x.isEmpty || y.isEmpty || x != y || x.exists(notFinite)
      }.take(1).isEmpty
    }

    grouped.values.filter(_.size > 1).foreach { group =>
      val keepers = mutable.ArrayBuffer.empty[RowSpec]
      group.sortBy(_._1).foreach { case (rowId, _) =>
        val rs = rowSpecs(rowId)
        keepers.find(k => sameCoefficients(k.rowId, rowId)) match {
          case None => keepers += rs
          case Some(keeper) =>
            val keeperRhsN = keeper.b0 / leading(keeper.rowId)
            val rhsN = rs.b0 / leading(rowId)
            if (math.abs(rhsN - keeperRhsN) <= RhsMatchTolerance * math.max(1.0, math.abs(keeperRhsN))) {
              rs.emitted = false
              rs.dualNonUnique = true
              keeper.dualNonUnique = true
              rs.note = Some(s"merged: duplicate of constraint '${keeper.name}'")
            } else {
              fail(s"Constraints '${keeper.name}' and '${rs.name}' have identical normalised coefficients " +
                s"but conflicting right-hand sides (${keeper.b0} vs ${rs.b0} after bound shifts) — " +
                "inconsistent duplicate rows")
            }
        }
      }
    }
  }

  // -------------------------------------------------------------------------------------------
  // Validation helpers
  // -------------------------------------------------------------------------------------------

  /** Rejects a NaN or infinite right-hand side. */
  private def validateRhs(value: Double, context: String): Unit = {
    if (notFinite(value)) {
      fail(s"$context: non-finite right-hand side $value")
    }
  }

  /**
    * Guards the equality-form row count: fails if it overflows `Int` (Spark vector indices are `Int`),
    * or if it exceeds `maxLocalConstraints` while the driver-local Cholesky solver is selected, which
    * would hold a roughly `16*m*m`-byte Gramian on the driver.
    */
  private def checkRowBudget(rows: Long, what: String): Unit = {
    if (rows > Int.MaxValue) fail(s"Too many constraint rows ($rows): $what; Spark vector indices are Int")
    // Only the driver-local Cholesky solver is bounded by maxLocalConstraints; NewtonSolver.Auto
    // switches to the matrix-free conjugate-gradient solver beyond it, and an explicit
    // ConjugateGradient never touches the budget.
    if (config.newtonSolver == NewtonSolver.Cholesky && rows > config.maxLocalConstraints) {
      val mb = 16.0 * rows * rows / 1e6
      fail(f"Model needs at least $rows equality-form constraint rows ($what), exceeding " +
        f"maxLocalConstraints = ${config.maxLocalConstraints} with newtonSolver = Cholesky. With the " +
        f"Cholesky solver every constraint row is driver-local: the solver holds roughly 16*m*m bytes " +
        f"of Gramian-related driver allocations per solve (~$mb%.0f MB at m = $rows) plus an O(m^3) " +
        "factorization per iteration. Either raise maxLocalConstraints (only if the driver heap is " +
        "provisioned for it) or use NewtonSolver.Auto/ConjugateGradient, which avoids the " +
        "driver-local m x m Gramian but may use bounded O(m * rank) preconditioner storage.")
    }
  }

  // -------------------------------------------------------------------------------------------
  // Solution reconstruction
  // -------------------------------------------------------------------------------------------

  /**
    * Maps a continuous solver summary to the public status/objective pair and reconstructs the
    * solution — the unchanged single-solve path used whenever the model has no integral columns.
    */
  private def continuousSolution(compiled: Compiled, summary: LP.SolveSummary, multipliers: Option[DenseVector]): LpSolution = {
    val status: LpStatus = summary.termination match {
      case LP.Termination.Converged => LpStatus.Optimal
      case LP.Termination.IterationLimit => LpStatus.IterationLimit
      case LP.Termination.Stopped => LpStatus.Stopped
      case LP.Termination.PrimalInfeasible => LpStatus.Infeasible
      case LP.Termination.DualInfeasible =>
        // a dual-infeasibility ray proves unboundedness only together with a primal-feasible point
        if (summary.primalResidual < config.tolerance) LpStatus.Unbounded
        else LpStatus.InfeasibleOrUnbounded
    }
    val objectiveValue = status match {
      case LpStatus.Infeasible | LpStatus.InfeasibleOrUnbounded => Double.NaN
        // the solver minimizes, so an unbounded objective diverges to -inf in solver form
      case LpStatus.Unbounded => compiled.senseMult * Double.NegativeInfinity
      case _ => compiled.senseMult * summary.objectiveValue + compiled.objConstant
    }
    val prices = if (status == LpStatus.Optimal && problem.quadratic.isEmpty && boundReductions.isEmpty)
      multipliers.map(lambda => compiled.rowSpecs.filter(r => r.emitted && !r.dualNonUnique)
        .map(r => r.rowId -> (compiled.senseMult * lambda(r.finalIdx))).toMap)
      else None
    buildSolution(compiled, summary.x, status, objectiveValue, summary.iterations,
      LpResiduals(summary.primalResidual, summary.dualResidual, summary.dualityGap), Map.empty,
      Some(summary.candidate), summary.stopReason, buildEvidence(compiled, summary, status), rowPrices = prices)
  }

  private def buildEvidence(compiled: Compiled, summary: LP.SolveSummary,
    status: LpStatus): Option[LpEvidence] = {
    if (compiled.rowSpecs.exists(_.internalBound) || compiled.substitutions.nonEmpty || boundReductions.nonEmpty) return None
    if (summary.primalCertificate.isEmpty && summary.dualCertificate.isEmpty) return None
    def snapshot[A: scala.reflect.ClassTag](rdd: RDD[A]): RDD[A] = {
      val saved = caches.checkpoint(rdd)
      saved.count()
      caches.keep(saved)
    }
    val costs = compiled.originalCosts.getOrElse(sc.emptyRDD[((Int, String), Double)])
    val coefficients = compiled.userTermsAgg.map { case ((si, key, row), a) => ((si, key), (row, a)) }
      .groupByKey().mapValues(_.toMap)
    val metadata = sc.union(compiled.plans.map { p =>
      val si = p.handle.setIndex
      val name = p.handle.name
      val lower = p.handle.lowerBound
      val upper = p.handle.upperBound
      p.keys.map { case (key, display) =>
        ((si, key), (KeyCodec.displayName(name, display), lower, upper))
      }
    })
    val curvature = compiled.originalCurvature.getOrElse(sc.emptyRDD[((Int, String), Double)])
    val variables = snapshot(metadata.leftOuterJoin(coefficients).leftOuterJoin(costs)
      .leftOuterJoin(curvature).mapValues {
        case ((((name, lower, upper), coeffs), cost), q) =>
          EvidenceVariable(name, lower, upper, cost.getOrElse(0.0), coeffs.getOrElse(Map.empty), q.getOrElse(0.0))
      })
    val model = EvidenceModel(compiled.rowSpecs.map(r => EvidenceRow(r.name, r.group, r.sense.symbol, r.rhsUser)),
      variables, problem.sense)
    summary.primalCertificate.map { certificate =>
      val y = compiled.rowSpecs.map(r => if (r.emitted) certificate(r.finalIdx) else 0.0)
      var boundRow = compiled.rowSpecs.count(_.emitted)
      val upperPieces = compiled.plans.flatMap { p => p.kind match {
        case ShiftedKind(_, Some(_)) =>
          val base = boundRow
          boundRow += p.count.toInt
          val si = p.handle.setIndex
          val multipliers = certificate.values
          Some(p.sortedKeys.map { case (key, (i, _)) => ((si, key), multipliers(base + i.toInt)) })
        case _ => None
      }}
      val upper: RDD[((Int, String), Double)] = if (upperPieces.isEmpty) sc.emptyRDD else sc.union(upperPieces)
      val bounds = snapshot(variables.leftOuterJoin(upper).mapValues { case (v, u) =>
        val ay = v.coefficients.iterator.map { case (r, a) => a * y(r) }.sum
        if (v.upper.contains(v.lower)) (math.max(0.0, -ay), math.min(0.0, -ay))
        else if (v.lower.isNegInfinity && v.upper.nonEmpty) (0.0, -ay)
        else (if (v.lower.isNegInfinity) 0.0 else -ay - u.getOrElse(0.0), u.getOrElse(0.0))
      })
      InfeasibilityCertificate(model, y, bounds): LpEvidence
    }.orElse(summary.dualCertificate.map { ray =>
      val direction = snapshot(reconstructValues(compiled, ray, Map.empty, direction = true))
      val point = if (status == LpStatus.Unbounded)
        Some(snapshot(reconstructValues(compiled, summary.x, Map.empty))) else None
      UnboundedDirection(model, direction, point): LpEvidence
    })
  }

  /**
    * Rebuilds user-facing per-variable values from a solver iterate: undoes bound shifts (`shift + y`),
    * recombines free-variable splits (`x_plus - x_minus`), drops internal slack columns, and re-adds
    * fixed variables that were presolved out. `integerOverrides` supplies exact user-unit values for
    * integral columns keyed by global column index, taking precedence over the reconstructed value.
    */
  private[dsl] def sosFeasible(compiled: Compiled, x: DVector, overrides: Map[Long, Double], tolerance: Double): Boolean = {
    val groups = problem.sosGroups.map(_.data).toVector
    if (groups.isEmpty) true
    else {
      val members = groups.flatMap(_.members.map(_.variable)).toSet
      val values = reconstructValues(compiled, x, overrides)
        .filter { case ((si, key), _) => members(LpVariableId(si, key)) }.collect().toMap
      groups.forall { group =>
        val active = group.members.zipWithIndex.collect { case (m, i) if
          values.get((m.variable.family, m.variable.key)).exists(v => math.abs(v) > tolerance) => i }
        group.members.forall(m => values.get((m.variable.family, m.variable.key)).exists(java.lang.Double.isFinite)) && LpSos.valid(group, active)
      }
    }
  }

  private def reconstructValues(compiled: Compiled, x: DVector,
    integerOverrides: Map[Long, Double], direction: Boolean = false): RDD[((Int, String), Double)] = {
    // per-column primal values, aligned with the compiled column order
    val overrides = integerOverrides
    val xValues: RDD[(Long, ColData, Double)] = compiled.sortedCols.zipPartitions(x) { (colsIt, xIt) =>
      if (!xIt.hasNext) Iterator.empty
      else {
        val values = xIt.next().values
        colsIt.zipWithIndex.map { case ((g, colData), i) => (g, colData, values(i)) }
      }
    }

    // undo bound shifts and free-variable splits; drop slacks
    val varValues = xValues.flatMap { case (g, colData, v) =>
      overrides.get(g) match {
        case Some(exact) => Some(((colData.setIndex, colData.enc), exact))
        case None =>
          colData.kind match {
            case 0 => Some(((colData.setIndex, colData.enc), (if (direction) 0.0 else colData.shift) + v))
            case 4 => Some(((colData.setIndex, colData.enc), (if (direction) 0.0 else colData.shift) - v))
            case 1 => Some(((colData.setIndex, colData.enc), v))
            case 2 => Some(((colData.setIndex, colData.enc), -v))
            case _ => None
          }
      }
    }.reduceByKey(_ + _)

    val fixedRdds = compiled.plans.flatMap { p =>
      p.kind match {
        case FixedKind(value) =>
          val si = p.handle.setIndex
          val v = if (direction) 0.0 else value
          Some(p.keys.map { case (enc, _) => ((si, enc), v) })
        case _ => None
      }
    }
    restoreSubstitutions(compiled, if (fixedRdds.isEmpty) varValues else sc.union(varValues +: fixedRdds), direction)
  }

  private def restoreSubstitutions(compiled: Compiled, values: RDD[((Int, String), Double)],
    direction: Boolean = false): RDD[((Int, String), Double)] = {
    if (compiled.substitutions.isEmpty) values
    else {
      val substitutions = compiled.substitutions
      val activities = compiled.userTermsAgg.filter { case ((si, key, row), _) =>
        substitutions.get(row).exists(s => s.variable != LpVariableId(si, key))
      }.map { case ((si, key, row), a) => ((si, key), (row, a)) }.join(values)
        .map { case (_, ((row, a), value)) => row -> (a * value) }.reduceByKey(_ + _)
      val restored = sc.parallelize(substitutions.toVector).leftOuterJoin(activities).map {
        case (_, (substitution, activity)) =>
          val value = ((if (direction) 0.0 else substitution.rhs) - activity.getOrElse(0.0)) / substitution.coefficient
          ((substitution.variable.family, substitution.variable.key), value)
      }
      values.union(restored)
    }
  }

  private def presolveSummary(compiled: Compiled): LpPresolveSummary = {
    val plans = compiled.plans.take(problem.handles.size)
    val fixed = plans.filter(_.kind.isInstanceOf[FixedKind]).map(_.count).sum
    val variables = plans.map(_.count).sum
    val factorRows = problem.quadratic.toSeq.flatMap(_.factors).count(_._2 != 0.0)
    val rows = compiled.rowSpecs.filterNot(_.internalBound).dropRight(factorRows)
    val solved = compiled.direct.nonEmpty || compiled.numCols == 0
    LpPresolveSummary(config.presolve.enabled, config.presolve.effort, propagationPasses,
      variables, rows.size, if (solved) 0L else variables - fixed - compiled.substitutions.size,
      if (solved) 0 else rows.count(_.emitted), compiled.numCols, compiled.numRows, fixed,
      boundReductions, compiled.substitutions.toVector.sortBy(_._1).map(_._2))
  }

  /** Check original units, including presolved/merged rows, bound shifts and free splits.
    * These actions validate retention, not reporting; solution vectors stay distributed.
    */
  private def originalViolation(compiled: Compiled, x: DVector): Double = {
    if (compiled.rowSpecs.forall(_.emitted) && compiled.plans.forall(_.kind.isInstanceOf[ShiftedKind]))
      alignedOriginalViolation(compiled, x)
    else reconstructedOriginalViolation(compiled, x)
  }

  /** Plain bounded variables have one solver column per user value. Reuse that alignment
    * instead of joining keys on every iteration. Accumulate original-unit activities (excluding
    * internal slacks); only the bounded constraint vector and a scalar bound violation reduce
    * to the driver. Other transformations use the complete reconstruction below.
    */
  private def alignedOriginalViolation(compiled: Compiled, x: DVector): Double = {
    val rowCount = compiled.rowSpecs.size
    val bounds = compiled.plans.map { p =>
      p.handle.setIndex -> (p.kind match {
        case ShiftedKind(lower, upper) => (lower, upper)
        case _ => throw new IllegalStateException("Expected one-column bounded variables")
      })
    }.toMap
    val (activity, boundViolation) = compiled.sortedCols.zipPartitions(x) { (columns, blocks) =>
      val values = blocks.next().values
      val activity = new Array[Double](rowCount)
      var violation = 0.0
      var i = 0
      columns.foreach { case (_, data) =>
        if (data.kind == 0) {
          val value = data.shift + values(i)
          val (lower, upper) = bounds(data.setIndex)
          violation = math.max(violation, relativeBoundViolation(value, Some(lower), upper))
          data.vector.foreachActive { (row, coefficient) =>
            if (row < rowCount) activity(row) += coefficient * value
          }
        }
        i += 1
      }
      Iterator.single((activity, violation))
    }.reduce { case ((left, lv), (right, rv)) =>
      var i = 0
      while (i < left.length) {
        left(i) += right(i);
        i += 1
      }
      (left, math.max(lv, rv))
    }
    compiled.rowSpecs.foldLeft(boundViolation) { (worst, row) =>
      val residual = activity(row.finalIdx) - row.rhsUser
      math.max(worst, relativeRowViolation(row.sense, residual, row.rhsUser))
    }
  }

  /**
    * General original-unit feasibility check, used when the model contains presolved/merged rows, fixed
    * variables or free splits. Reconstructs user values, then computes the worst relative bound and
    * constraint-row violation over the original (pre-transformation) model. Stays fully distributed,
    * reducing only a scalar to the driver.
    */
  private def reconstructedOriginalViolation(compiled: Compiled, x: DVector): Double = {
    originalValuesViolation(compiled, reconstructValues(compiled, x, Map.empty))
  }

  private def originalValuesViolation(compiled: Compiled, values: RDD[((Int, String), Double)]): Double = {
    val bounds = compiled.plans.map { p =>
      val limits = p.kind match {
        case FixedKind(v) => (Some(v), Some(v))
        case ShiftedKind(lower, upper) => (Some(lower), upper)
        case SplitKind => (None, None)
        case ReflectedKind(upper) => (None, Some(upper))
      }
      p.handle.setIndex -> limits
    }.toMap
    val boundViolations = values.map { case ((si, _), value) =>
      val (lower, upper) = bounds(si)
      relativeBoundViolation(value, lower, upper)
    }
    val rows = sc.parallelize(compiled.rowSpecs.map(r => (r.rowId, (r.rhsUser, r.sense))))
    val activity = compiled.userTermsAgg.map { case ((si, enc, row), c) => ((si, enc), (row, c)) }
      .join(values).map { case (_, ((row, c), value)) => (row, c * value) }.reduceByKey(_ + _)
    val violations = activity.rightOuterJoin(rows).map { case (_, (act, (rhs, sense))) =>
      relativeRowViolation(sense, act.getOrElse(0.0) - rhs, rhs)
    }
    boundViolations.union(violations).fold(0.0)(math.max)
  }

  /**
    * Reconstructs user-facing values and diagnostics from a solver iterate. `integerOverrides`
    * carries exact user-unit values (already rounded to integers) for integral solver columns,
    * keyed by the global column index; an empty map reproduces the plain continuous
    * reconstruction.
    */
  private[dsl] def buildSolution(
    compiled: Compiled,
    x: DVector,
    status: LpStatus,
    objectiveValue: Double,
    iterations: Int,
    residuals: LpResiduals,
    integerOverrides: Map[Long, Double],
    candidate: Option[CandidateInfo] = None,
    stopReason: Option[StopReason] = None,
    evidence: Option[LpEvidence] = None,
    originalValues: Option[RDD[((Int, String), Double)]] = None,
    mip: Option[MipSummary] = None,
    rowPrices: Option[Map[Int, Double]] = None): LpSolution = {

    val available = candidate.forall(_.available)
    val userValues = caches.checkpoint(
      if (available) originalValues.getOrElse(reconstructValues(compiled, x, integerOverrides))
      else sc.emptyRDD[((Int, String), Double)])
    // Detach the result before releasing the solver's checkpointed iterate and compiled inputs.
    userValues.count()

    // activity in the caller's original variable units, for every user constraint row
    val activity = compiled.userTermsAgg
      .map { case ((si, enc, r), c) => ((si, enc), (r, c)) }
      .join(userValues)
      .map { case (_, ((r, c), v)) => (r, c * v) }
      .reduceByKey(_ + _)
      .collectAsMap()

    val factorRows = problem.quadratic.toSeq.flatMap(_.factors).count(_._2 != 0.0)
    val rows = compiled.rowSpecs.filterNot(_.internalBound).dropRight(factorRows).map { rs =>
      val act = if (available) activity.getOrElse(rs.rowId, 0.0) else Double.NaN
      val price = rowPrices.flatMap(_.get(rs.rowId))
      val unavailable = if (price.nonEmpty) null
        else if (rs.dualNonUnique) "Non-unique dual for merged equivalent rows"
        else if (!rs.emitted) "Presolved row has no uniquely reconstructed dual"
        else "No optimal continuous LP dual available"
      ConstraintDiagnostics.row(rs.name, rs.group.orNull, act, rs.sense.symbol, rs.rhsUser,
        price.map(Double.box).orNull, rs.note.orNull, unavailable)
    }
    val constraintsDf = spark.createDataFrame(sc.parallelize(rows, 1), ConstraintDiagnostics.schema)

    val reducedCosts = rowPrices.filter(_ => !compiled.rowSpecs.exists(_.internalBound)).map { prices =>
      val originalRows = compiled.rowSpecs.map(_.rowId).toSet
      val unavailable = originalRows -- prices.keySet
      val effects = compiled.userTermsAgg.map { case ((si, key, row), coefficient) =>
        ((si, key), (coefficient * prices.getOrElse(row, 0.0), unavailable(row) && coefficient != 0.0))
      }.reduceByKey { case ((a, missingA), (b, missingB)) => (a + b, missingA || missingB) }
      val costs = compiled.originalCosts.getOrElse(sc.emptyRDD[((Int, String), Double)])
      val keys = sc.union(compiled.plans.map { p =>
        val si = p.handle.setIndex
        val fixed = p.kind.isInstanceOf[FixedKind]
        p.keys.map { case (key, _) => ((si, key), fixed) }
      })
      val result = caches.checkpoint(keys.leftOuterJoin(costs).leftOuterJoin(effects).flatMap {
        case (key, ((fixed, cost), effect)) =>
          if (fixed || effect.exists(_._2)) None
          else Some(key -> (cost.getOrElse(0.0) - effect.map(_._1).getOrElse(0.0)))
      })
      result.count()
      caches.keep(result)
    }

    val publicValues = if (compiled.plans.size == problem.handles.size) userValues else {
      val limit = problem.handles.size
      val visible = caches.checkpoint(userValues.filter(_._1._1 < limit))
      visible.count(); visible
    }
    val retainedValues = caches.keep(publicValues)
    val candidateInfo = candidate.getOrElse(CandidateInfo(true, status == LpStatus.Optimal, Some(iterations)))
    val presolve = Some(presolveSummary(compiled))
    val start = preparedStart.map(_.summary)
    val timings = timing.finish()
    new LpSolution(
      status = status,
      objectiveValue = objectiveValue,
      iterations = iterations,
      residuals = residuals,
      timings = timings,
      constraints = constraintsDf,
      problem = problem,
      userValues = retainedValues,
      candidate = candidateInfo,
      stopReason = stopReason,
      evidence = evidence,
      isRelaxation = config.relaxIntegrality,
      mip = mip,
      reducedCostData = reducedCosts,
      presolve = presolve, start = start)
  }

  private[dsl] def startReconstruction(): Unit = timing.startReconstruction()
}

private[dsl] object LpCompiler {
  /**
    * Contiguous upper-bound rows emitted for one shifted variable family.
    *
    * @param plan compiled family whose active members receive bound rows.
    * @param rowBase first equality-row index assigned to the family.
    * @param rhs common bound width `upper - lower`.
    */
  private[compiler] final case class BoundBlock(plan: SetPlan, rowBase: Int, rhs: Double)

  /** True for a NaN or infinite value; the compiler rejects non-finite user data. */
  private[compiler] def notFinite(value: Double): Boolean = !Numerics.isFinite(value)

  /**
    * Worst relative bound violation of `value` against optional lower/upper bounds, scaled by
    * `1 + |bound|`; `0.0` when feasible and `+Inf` for a non-finite value. Safe to call inside Spark
    * closures because it lives on the singleton object.
    */
  private[compiler] def relativeBoundViolation(value: Double, lower: Option[Double], upper: Option[Double]): Double =
    if (notFinite(value)) Double.PositiveInfinity
    else math.max(
      lower.map(l => (l - value) / (1.0 + math.abs(l))).getOrElse(0.0),
      upper.map(u => (value - u) / (1.0 + math.abs(u))).getOrElse(0.0))

  /**
    * Relative constraint-row violation for a signed residual `activity - rhs`, scaled by `1 + |rhs|`;
    * `+Inf` for a non-finite residual. Safe inside Spark closures (singleton object).
    */
  private[compiler] def relativeRowViolation(sense: LpSense, residual: Double, rhs: Double): Double =
    if (notFinite(residual)) Double.PositiveInfinity
    else sense.violation(residual) / (1.0 + math.abs(rhs))
}
