package com.github.vbmacher.spark_lp.dsl.compiler

import com.github.vbmacher.spark_lp.vectors.{DMatrix, DVector}
import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.rdd.RDD

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
