package com.github.vbmacher.spark_lp.dsl.compiler

import com.github.vbmacher.spark_lp.dsl.{LpSense, VarSetHandle}
import com.github.vbmacher.spark_lp.vectors.{DMatrix, DVector}
import org.apache.spark.Partitioner
import org.apache.spark.mllib.linalg.{DenseVector, Vector => MLVector}
import org.apache.spark.rdd.RDD

/**
  * Driver-local model types shared by [[LpCompiler]] and [[BranchAndBound]]. Every type here is a
  * pure data holder or a small structural helper; the compilation logic lives in [[LpCompiler]].
  */

/**
  * How a variable set is realised in the solver model after presolve: a set is either eliminated
  * ([[FixedKind]]), represented by a single shifted non-negative column ([[ShiftedKind]]), or split
  * into a positive/negative column pair for a free variable ([[SplitKind]]).
  */
private[dsl] sealed trait PlanKind

/** Fixed variable (`lower == upper`): folded into the RHS/objective constant, given no solver column. */
private[dsl] final case class FixedKind(value: Double) extends PlanKind

/**
  * Lower-bounded variable `x = shift + y` with `y >= 0` and an optional finite `upper`. A defined
  * `upper` adds one bound row `y + s = upper - shift` per key.
  */
private[dsl] final case class ShiftedKind(shift: Double, upper: Option[Double]) extends PlanKind

/** Free variable, represented as the difference of two non-negative columns `x = x_plus - x_minus`. */
private[dsl] case object SplitKind extends PlanKind

/** Upper-only variable: x = upper - y, y >= 0. */
private[dsl] final case class ReflectedKind(upper: Double) extends PlanKind

/**
  * One solver column. `kind`: 0 = plain shifted variable (`x = shift + y`), 1 = positive part of
  * a free split, 2 = negative part, 3 = internal slack, 4 = upper reflection (`x = shift - y`).
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
    case ReflectedKind(_) => count
  }

  /** Keys sorted by encoded form; ordering never depends on partition order. */
  lazy val sortedKeys: RDD[(String, (Long, Seq[String]))] =
    keys.sortBy(_._1).zipWithIndex().map { case ((enc, disp), i) => (enc, (i, disp)) }
}

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
  val intCols: IndexedSeq[IntColumn],
  val quadratic: Option[DVector] = None,
  val originalCosts: Option[RDD[((Int, String), Double)]] = None,
  val originalCurvature: Option[RDD[((Int, String), Double)]] = None,
  val direct: Option[DirectResult] = None)

/** Analytic result for a separable linear objective with no active user rows. */
private[dsl] final case class DirectResult(
  values: RDD[((Int, String), Double)], objectiveValue: Double, unbounded: Boolean)

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
