package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.{CachedRDDs, LP}
import com.github.vbmacher.spark_lp.vectors.{DMatrix, DVector}
import org.apache.spark.Partitioner
import org.apache.spark.mllib.linalg.{DenseVector, Vectors, Vector => MLVector}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.{DoubleType, StringType, StructField, StructType}
import org.apache.spark.sql.{AnalysisException, Row, SparkSession}

import scala.collection.mutable
import scala.util.hashing.MurmurHash3

private[dsl] object LpCompiler {

  /** Tolerance for presolved zero-term rows (`0 <sense> rhs`). */
  private val PresolveTolerance = 1e-11

  /** Relative tolerance when comparing normalised right-hand sides of duplicate rows. */
  private val RhsMatchTolerance = 1e-9

  /**
    * Deterministic, contiguous range partitioner over column indices `0 until total`. With
    * `parts <= total` every partition is non-empty, which the solver's partition-aligned
    * `DVector`/`DMatrix` operations require.
    */
  private[dsl] final class RangeIndexPartitioner(total: Long, parts: Int) extends Partitioner {
    require(parts >= 1 && parts <= total, s"parts=$parts must be in [1, $total]")
    override def numPartitions: Int = parts
    override def getPartition(key: Any): Int = {
      val idx = key.asInstanceOf[Long]
      math.min(parts - 1, (idx * parts / total).toInt)
    }
  }

  private[dsl] sealed trait PlanKind
  private[dsl] final case class FixedKind(value: Double) extends PlanKind
  private[dsl] final case class ShiftedKind(shift: Double, upper: Option[Double]) extends PlanKind
  private[dsl] case object SplitKind extends PlanKind

  /** Compiled per-set layout: validated keys, transformation kind and the column offset. */
  private[dsl] final class SetPlan(
    val handle: VarSetHandle,
    val keys: RDD[(String, Seq[String])],
    val count: Long,
    val kind: PlanKind,
    val integral: Boolean) {

    var offset: Long = 0L

    def columns: Long = kind match {
      case FixedKind(_) => 0L
      case SplitKind => 2 * count
      case ShiftedKind(_, _) => count
    }

    /** Keys sorted by encoded form; ordering never depends on partition order. */
    lazy val sortedKeys: RDD[(String, (Long, Seq[String]))] =
      keys.sortBy(_._1).zipWithIndex().map { case ((enc, disp), i) => (enc, (i, disp)) }
  }

  /** One expanded (user-facing) constraint row. */
  private[dsl] final class RowSpec(
    val rowId: Int,
    val name: String,
    val group: Option[String],
    val sense: LpSense,
    val rhsUser: Double) {

    /** RHS after fixed-variable and bound-shift folding. */
    var b0: Double = rhsUser
    var note: Option[String] = None
    var emitted: Boolean = true
    var finalIdx: Int = -1
  }

  /**
    * One solver column. `kind`: 0 = plain shifted variable (`x = shift + y`), 1 = positive part of
    * a free split, 2 = negative part, 3 = internal slack.
    */
  private[dsl] final case class ColData(
    setIndex: Int,
    enc: String,
    kind: Byte,
    shift: Double,
    cost: Double,
    vector: MLVector)

  /**
    * Driver-local description of one integral (Integer/Binary) solver column, everything the
    * discrete solver needs to retarget the equality-form RHS when the column's integral
    * bounds are tightened. `rowCoeffs` maps emitted constraint-row indices to the column's
    * coefficients; `boundRow` is the column's upper-bound row (`y + s = upper - lower`), which
    * every integral column has by construction.
    */
  private[dsl] final case class IntColumn(
    g: Long,
    setIndex: Int,
    enc: String,
    rootLower: Double,
    rootUpper: Double,
    cost: Double,
    boundRow: Int,
    rowCoeffs: Map[Int, Double])

  /** Everything the solver call and the solution reconstruction need. */
  private[dsl] final class Compiled(
    val c: DVector,
    val AT: DMatrix,
    val b: DenseVector,
    val numRows: Int,
    val numCols: Long,
    val sortedCols: RDD[(Long, ColData)],
    val rowSpecs: IndexedSeq[RowSpec],
    val plans: IndexedSeq[SetPlan],
    val objConstant: Double,
    val senseMult: Double,
    val userTermsAgg: RDD[((Int, String, Int), Double)],
    val intCols: IndexedSeq[IntColumn])
}

/**
  * Compiles a declarative [[LpProblem]] into the solver's equality form (`minimize c^T x` subject
  * to `Ax = b`, `x >= 0`) and reconstructs user-facing values from the solver iterate.
  *
  * Compilation is deterministic for fixed source data: variable sets are ordered by
  * `(set creation order, encoded key)`, generated rows by `(constraint creation order, encoded
  * group key)`. The coefficient matrix exists only as the solver's `DMatrix` of sparse rows; no
  * dense `n x m` structure or DataFrame pivot is ever materialised.
  */
private[dsl] final class LpCompiler(problem: LpProblem, config: SolveConfig) extends AutoCloseable {

  import LpCompiler._

  private implicit val spark: SparkSession = problem.spark
  private val sc = spark.sparkContext
  private val caches = new CachedRDDs

  override def close(): Unit = caches.close()

  private def fail(message: String): Nothing = throw new LpModelException(message)

  def solve(): LpSolution = try {
    val compiled = compile()
    if (compiled.numCols == 0) {
      buildSolution(compiled, sc.emptyRDD, LpStatus.Optimal, compiled.objConstant, 0,
        LpResiduals(0.0, 0.0, 0.0), Map.empty)
    } else if (compiled.intCols.isEmpty) {
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
        solver = config.resolvedNewtonSolver(compiled.numRows),
        cgTolerance = config.cgTolerance,
        matrixFree = config.matrixFree,
        cgMaxIterations = config.cgMaxIterations,
        stopAfterIteration = config.stopAfterIteration)
      try continuousSolution(compiled, summary)
      finally summary.x.unpersist(blocking = false)
    } else {
      if (config.stopAfterIteration.nonEmpty)
        fail("stopAfterIteration is supported only for continuous models")
      new BranchAndBound(this, compiled, config).solve()
    }
  } finally close()

  // -------------------------------------------------------------------------------------------
  // Compilation
  // -------------------------------------------------------------------------------------------

  private[dsl] def compile(): Compiled = {
    val objective = problem.objective.getOrElse(
      fail(s"Problem '${problem.name}' has no objective; add one with += or setObjective"))
    if (objective.constant.isNaN || objective.constant.isInfinite) {
      fail(s"Problem '${problem.name}': non-finite objective constant ${objective.constant}")
    }

    // --- variable set plans, ordered by set creation
    val plans: IndexedSeq[SetPlan] = problem.handles.map(buildPlan).toIndexedSeq
    var colCursor = 0L
    plans.foreach { plan =>
      plan.offset = colCursor
      colCursor += plan.columns
    }
    val numUserCols = colCursor

    // --- expanded constraint rows and their symbolic terms
    val rowSpecs = mutable.ArrayBuffer.empty[RowSpec]
    val termPieces = mutable.ArrayBuffer.empty[RDD[((Int, String, Int), Double)]]

    problem.constraints.zipWithIndex.foreach {
      case (Left(constraint), idx) =>
        val cname = constraint.explicitName.getOrElse(s"_c$idx")
        val context = s"constraint '$cname'"
        validateRhs(constraint.rhs, context)
        checkRowBudget(rowSpecs.size + 1L, context)
        val rowId = rowSpecs.size
        rowSpecs += new RowSpec(rowId, cname, None, constraint.sense, constraint.rhs)
        constraint.terms.foreach { term =>
          termPieces += expandTerm(plans, term, rowId, context)
        }
      case (Right(constraintSet), idx) =>
        val base = constraintSet.explicitName.getOrElse(s"_c$idx")
        expandBulk(constraintSet, base, plans, rowSpecs, termPieces)
    }

    val dupNames = rowSpecs.groupBy(_.name).filter(_._2.size > 1).keys.take(5).toSeq
    if (dupNames.nonEmpty) {
      fail(s"Duplicate constraint names after expansion: ${dupNames.mkString(", ")}")
    }

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
    val objTerms = caches.cache(
      (if (objPieces.isEmpty) emptyObj else sc.union(objPieces))
        .reduceByKey(_ + _)
        .filter(_._2 != 0.0)
    )

    val rowNames = sc.broadcast(rowSpecs.map(_.name).toArray)
    val handleNames = sc.broadcast(problem.handles.map(_.name).toArray)
    userTermsAgg.filter { case (_, v) => v.isNaN || v.isInfinite }.take(1).foreach {
      case ((si, enc, r), v) =>
        fail(s"Non-finite coefficient $v in constraint '${rowNames.value(r)}' " +
          s"for variable '${handleNames.value(si)}' (key $enc)")
    }
    objTerms.filter { case (_, v) => v.isNaN || v.isInfinite }.take(1).foreach {
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
    val objConstant = objective.constant + objAdjust
    if (objConstant.isNaN || objConstant.isInfinite) fail("Non-finite objective after bound shifts")

    val fixedSetIdx = sc.broadcast(fixedVals.keySet)
    val solverTerms = caches.cache(userTermsAgg.filter { case ((si, _, _), _) => !fixedSetIdx.value.contains(si) })

    // --- zero-term rows: trivially satisfied rows are presolved away, infeasible ones rejected
    val liveRows = solverTerms.map(_._1._3).distinct().collect().toSet
    rowSpecs.foreach { rs =>
      if (!liveRows.contains(rs.rowId)) {
        val feasible = rs.sense match {
          case LpSense.Eq => math.abs(rs.b0) <= PresolveTolerance
          case LpSense.Le => rs.b0 >= -PresolveTolerance
          case LpSense.Ge => rs.b0 <= PresolveTolerance
        }
        if (!feasible) {
          fail(s"Constraint '${rs.name}' has no remaining terms but requires 0 ${rs.sense.symbol} ${rs.b0} " +
            "(after presolve) — trivially infeasible")
        }
        rs.emitted = false
        rs.note = Some("presolved: no remaining terms; trivially satisfied")
      }
    }

    // --- duplicate equality rows: consistent duplicates merge, conflicting ones fail
    detectDuplicateRows(rowSpecs, solverTerms)

    var rowCursor = 0
    rowSpecs.foreach { rs =>
      if (rs.emitted) {
        rs.finalIdx = rowCursor
        rowCursor += 1
      }
    }

    // --- one extra row per finitely upper-bounded variable: y + s = u - l
    final case class BoundBlock(plan: SetPlan, rowBase: Int, rhs: Double)
    val boundBlocks = mutable.ArrayBuffer.empty[BoundBlock]
    plans.foreach { p =>
      p.kind match {
        case ShiftedKind(shift, Some(upper)) =>
          validateRhs(upper - shift, s"upper bound of variable '${p.handle.name}' after bound shift")
          checkRowBudget(rowCursor + p.count,
            s"upper bound rows of variable set '${p.handle.name}' (+${p.count} rows)")
          boundBlocks += BoundBlock(p, rowCursor, upper - shift)
          rowCursor += p.count.toInt
        case _ => ()
      }
    }
    val numRows = rowCursor
    if (numUserCols == 0 && plans.exists(_.count > 0)) {
      return new Compiled(sc.emptyRDD, sc.emptyRDD, new DenseVector(Array.emptyDoubleArray),
        0, 0L, sc.emptyRDD, rowSpecs.toIndexedSeq, plans, objConstant,
        if (problem.sense == Maximize) -1.0 else 1.0, userTermsAgg, IndexedSeq.empty)
    }
    if (numRows == 0) {
      fail(s"Problem '${problem.name}' compiled to an empty model: all constraint rows were presolved away")
    }

    val bArray = new Array[Double](numRows)
    rowSpecs.foreach { rs => if (rs.emitted) bArray(rs.finalIdx) = rs.b0 }
    boundBlocks.foreach { bb =>
      var i = 0
      while (i < bb.plan.count) {
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
      while (i < bb.plan.count) {
        slacks += ((slackCursor, (bb.rowBase + i, 1.0)))
        slackCursor += 1
        i += 1
      }
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
      intCols = intCols)
  }

  // -------------------------------------------------------------------------------------------
  // Set plans
  // -------------------------------------------------------------------------------------------

  private def buildPlan(handle: VarSetHandle): SetPlan = {
    val where = s"variable '${handle.name}'"
    val declaredLb = handle.lowerBound
    if (declaredLb.isNaN || declaredLb == Double.PositiveInfinity) {
      fail(s"$where: lower bound must not be NaN or +inf (got $declaredLb); Double.NegativeInfinity means a free variable")
    }
    handle.upperBound.foreach { ub =>
      if (ub.isNaN || ub.isInfinite) {
        fail(s"$where: upper bound must be finite when defined (got $ub); unbounded is expressed as None")
      }
    }

    val (lb, upperOpt, integral) = handle.category match {
      case Continuous => (declaredLb, handle.upperBound, false)
      case category => integralBounds(handle, category, where)
    }

    if (upperOpt.isDefined && lb == Double.NegativeInfinity) {
      fail(s"$where: a finite upper bound combined with a -inf lower bound is not supported in this release")
    }
    upperOpt.foreach { ub =>
      if (lb > ub) {
        fail(s"$where: lowerBound ($lb) > upperBound ($ub)")
      }
    }

    val keys = caches.cache(handle.domain.keyPairs())
    val count = keys.count()
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

    val kind =
      if (upperOpt.contains(lb)) FixedKind(lb)
      else if (lb == Double.NegativeInfinity) SplitKind
      else ShiftedKind(lb, upperOpt)
    new SetPlan(handle, keys, count, kind, integral)
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

    val (lo, hi) = category match {
      case Binary =>
        (math.max(handle.lowerBound, 0.0), math.min(handle.upperBound.getOrElse(1.0), 1.0))
      case _ =>
        if (handle.lowerBound == Double.NegativeInfinity) {
          fail(s"$where: an Integer variable requires a finite lower bound")
        }
        val ub = handle.upperBound.getOrElse(
          fail(s"$where: an Integer variable requires a finite upper bound; declare upperBound explicitly"))
        (handle.lowerBound, ub)
    }
    val lower = math.ceil(lo)
    val upper = math.floor(hi)
    if (math.abs(lower) > 9007199254740991.0 || math.abs(upper) > 9007199254740991.0) {
      fail(s"$where: integral bounds must be within +/- (2^53 - 1) for exact Double integers")
    }
    if (lower > upper) {
      val detail = if (category == Binary) " after intersecting the declared bounds with the Binary domain {0, 1}" else ""
      fail(s"$where: no integral values within bounds [$lo, $hi]$detail")
    }
    (lower, Some(upper), true)
  }

  // -------------------------------------------------------------------------------------------
  // Term expansion
  // -------------------------------------------------------------------------------------------

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
        if (coeff.isNaN || coeff.isInfinite) {
          fail(s"$context: non-finite coefficient $coeff for variable '${handle.name}'")
        }
        val c = coeff
        plans(si).keys.map { case (enc, _) => ((si, enc, rid), c) }

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
        x.isEmpty || y.isEmpty || x != y || x.exists(v => v.isNaN || v.isInfinite)
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

  private def validateRhs(value: Double, context: String): Unit = {
    if (value.isNaN || value.isInfinite) {
      fail(s"$context: non-finite right-hand side $value")
    }
  }

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
  private def continuousSolution(compiled: Compiled, summary: LP.SolveSummary): LpSolution = {
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
    buildSolution(compiled, summary.x, status, objectiveValue, summary.iterations,
      LpResiduals(summary.primalResidual, summary.dualResidual, summary.dualityGap), Map.empty)
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
    integerOverrides: Map[Long, Double]): LpSolution = {

    // per-column primal values, aligned with the compiled column order
    val overrides = sc.broadcast(integerOverrides)
    val xValues: RDD[(Long, ColData, Double)] = compiled.sortedCols.zipPartitions(x) { (colsIt, xIt) =>
      if (!xIt.hasNext) Iterator.empty
      else {
        val values = xIt.next().values
        colsIt.zipWithIndex.map { case ((g, colData), i) => (g, colData, values(i)) }
      }
    }

    // undo bound shifts and free-variable splits; drop slacks
    val varValues = xValues.flatMap { case (g, colData, v) =>
      overrides.value.get(g) match {
        case Some(exact) => Some(((colData.setIndex, colData.enc), exact))
        case None =>
          colData.kind match {
            case 0 => Some(((colData.setIndex, colData.enc), colData.shift + v))
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
          val v = value
          Some(p.keys.map { case (enc, _) => ((si, enc), v) })
        case _ => None
      }
    }
    val userValues = caches.checkpoint(
      if (fixedRdds.isEmpty) varValues else sc.union(varValues +: fixedRdds))
    // Detach the result before releasing the solver's checkpointed iterate and compiled inputs.
    userValues.count()

    // activity in the caller's original variable units, for every user constraint row
    val activity = compiled.userTermsAgg
      .map { case ((si, enc, r), c) => ((si, enc), (r, c)) }
      .join(userValues)
      .map { case (_, ((r, c), v)) => (r, c * v) }
      .reduceByKey(_ + _)
      .collectAsMap()

    val schema = StructType(Seq(
      StructField("name", StringType, nullable = false),
      StructField("group", StringType, nullable = true),
      StructField("activity", DoubleType, nullable = false),
      StructField("sense", StringType, nullable = false),
      StructField("rhs", DoubleType, nullable = false),
      StructField("slack", DoubleType, nullable = false),
      StructField("dual", DoubleType, nullable = true),
      StructField("note", StringType, nullable = true)))
    val rows = compiled.rowSpecs.map { rs =>
      val act = activity.getOrElse(rs.rowId, 0.0)
      val slack = rs.sense match {
        case LpSense.Ge => act - rs.rhsUser
        case _ => rs.rhsUser - act
      }
      Row(rs.name, rs.group.orNull, act, rs.sense.symbol, rs.rhsUser, slack, null, rs.note.orNull)
    }
    val constraintsDf = spark.createDataFrame(sc.parallelize(rows, 1), schema)

    new LpSolution(
      status = status,
      objectiveValue = objectiveValue,
      iterations = iterations,
      residuals = residuals,
      constraints = constraintsDf,
      problem = problem,
      userValues = caches.keep(userValues))
  }
}
