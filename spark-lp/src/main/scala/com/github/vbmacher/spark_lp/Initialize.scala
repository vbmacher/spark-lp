package com.github.vbmacher.spark_lp

import breeze.linalg.{DenseVector => BDV}
import com.github.vbmacher.spark_lp.VectorSpace._
import com.github.vbmacher.spark_lp.fs.dmatrix.vector.LPRowMatrix
import com.github.vbmacher.spark_lp.fs.dvector.vector.LinopMatrixAdjoint
import com.github.vbmacher.spark_lp.fs.vector.dvector.LinopMatrix
import com.typesafe.scalalogging.LazyLogging
import org.apache.spark.mllib.linalg.{DenseVector, Matrix}
import DVectorFunctions._

/**
  * An abstract class for LP initialization.
  */
abstract class Initialize extends Serializable {
  def init(c: DVector, rows: DMatrix, b: DenseVector): (DVector, DenseVector, DVector, Long, Int)
}

object Initialize extends LazyLogging {

  /**
    * Compute the heuristic starting points.
    *
    * @param c    the objective coefficient DVector.
    * @param rows the constraint DMatrix.
    * @param b    the constraint values.
    * @param row  implicit for distributed computations.
    * @param col  implicit for local computations.
    * @return starting points (x, lambda, s) and the computed dimensions of rows DMatrix (n, m).
    */
  def init(c: DVector, rows: DMatrix, b: DenseVector)(
    implicit row: VectorSpace[DVector], col: VectorSpace[DenseVector]
  ): (DVector, DenseVector, DVector, Long, Int) = {

    row.cache(c)
    rows.cache()
    val dmat = new LinopMatrix(rows)
    val dmatT = new LinopMatrixAdjoint(rows)
    val n: Long = rows.count()
    println(s"number of unknows: $n")
    val m: Int = rows.first().size
    println(s"number of equations: $m")
    val B: LPRowMatrix = new LPRowMatrix(rows, n, m)
    val BTB: BDV[Double] = B.computeGramianMatrixColumn(m, depth = 2)
    val BTBtoArrayToInv = BTB.toArray
    Util.posSymDefInv(BTBtoArrayToInv, m) // less space with managed side effect
    val BTBInv: Matrix = Util.triuToFull(BTBtoArrayToInv, m)

    // xTilda = B * BTBInv * b
    // NOTE: BTBInv and BTBInv * b are local matrix and vector
    val xTilda: DVector = dmat(BTBInv.multiply(b)) // DMatrix * DenseVector

    // lambdaTilda = BTBInv * B^T * c
    val lambdaTilda: DenseVector = BTBInv.multiply(dmatT(c))

    // sTilda = c - B * lambdaTilda
    val sTilda: DVector = c.diff(dmat(lambdaTilda))

    // deltax = max(1.5 * xTilda.max(), 0)
    val deltax: Double = math.max(1.5 * row.max(xTilda), 0)

    // deltas = max(1.5 * sTilda.max(), 0)
    val deltas: Double = math.max(1.5 * row.max(sTilda), 0)

    // xHat = xTilda + deltax * e
    val xHat: DVector = xTilda.mapElements(a => a + deltax)

    // sHat = sTilda + deltas * e
    val sHat: DVector = sTilda.mapElements(a => a + deltas)

    // deltaxHat = 0.5 * (xHat, sHat) / (e, sHat)
    val deltaxHat: Double = 0.5 * (xHat.dot(sHat) / row.sum(sHat))

    // deltasHat = 0.5 * (xHat, sHat) / (e, xHat)
    val deltasHat: Double = 0.5 * (xHat.dot(sHat) / row.sum(xHat))

    // x = xHat + deltaxHat * e
    val x = xHat.mapElements(a => a + deltaxHat)

    // lambda = lambdaTilda
    // s = sHat + deltasHat * e
    val s = sHat.mapElements(a => a + deltasHat)

    (x, lambdaTilda, s, n, m)
  }
}