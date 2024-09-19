package com.github.vbmacher.spark_lp

import breeze.linalg.{DenseVector => BDV}
import com.github.vbmacher.spark_lp.linalg.breeze_ops.{posSymDefInv, triuToFull}
import com.github.vbmacher.spark_lp.linalg.dmatrix.implicits._
import com.github.vbmacher.spark_lp.linalg.dvector.implicits._
import com.github.vbmacher.spark_lp.linalg.vs.DVectorSpace
import com.github.vbmacher.spark_lp.linalg.{DMatrix, DVector}
import com.typesafe.scalalogging.LazyLogging
import org.apache.spark.mllib.linalg.{DenseVector, Matrix}

object Initialize extends LazyLogging {

  case class Initialization(
    x: DVector,
    lambda: DenseVector,
    s: DVector,
    rows: Long,
    cols: Int
  )

  /**
    * Compute the heuristic starting points.
    *
    * Ax + s = b
    *
    * @param c the objective coefficient DVector.
    * @param A the constraint DMatrix.
    * @param b the constraint values.
    * @return starting points (x, lambda, s) and the computed dimensions of rows DMatrix (n, m).
    */
  def init(c: DVector, A: DMatrix, b: DenseVector): Initialization = {
    val vs = DVectorSpace

    c.cacheIfNoStorageLevel()
    A.cache()

    val n: Long = A.count()
    val m: Int = A.first().size

    val B = A
    val BTB: BDV[Double] = B.gramianMatrix(m, depth = 2)
    val BTBtoArrayToInv = BTB.toArray
    posSymDefInv(BTBtoArrayToInv, m) // less space with managed side effect

    val BTBInv: Matrix = triuToFull(BTBtoArrayToInv, m)

    // xTilda = B^T * BTBInv * b
    // NOTE: BTBInv and BTBInv * b are local matrix and vector
    val xTilda: DVector = A.product(BTBInv.multiply(b)) // DMatrix * DenseVector

    // deltax = max(1.5 * xTilda.max(), 0)
    val deltax: Double = math.max(1.5 * vs.max(xTilda), 0)

    // xHat = xTilda + deltax * e
    val xHat: DVector = xTilda.mapElements(a => a + deltax)

    // lambdaTilda = BTBInv * B^T * c
    val lambdaTilda: DenseVector = BTBInv.multiply(A.adjointProduct(c))

    // sTilda = c - B * lambdaTilda
    val sTilda: DVector = c.diff(A.product(lambdaTilda))

    // deltas = max(1.5 * sTilda.max(), 0)
    // val deltas: Double = math.max(-1.5 * row.min(sTilda), 0)
    val deltas: Double = math.max(1.5 * vs.max(sTilda), 0)

    // sHat = sTilda + deltas * e
    val sHat: DVector = sTilda.mapElements(a => a + deltas)

    // deltaxHat = 0.5 * (xHat, sHat) / (e, sHat)
    val deltaxHat: Double = 0.5 * (xHat.dot(sHat) / vs.sum(sHat))

    // deltasHat = 0.5 * (xHat, sHat) / (e, xHat)
    val deltasHat: Double = 0.5 * (xHat.dot(sHat) / vs.sum(xHat))

    // x = xHat + deltaxHat * e
    val x = xHat.mapElements(a => a + deltaxHat)

    // lambda = lambdaTilda
    // s = sHat + deltasHat * e
    val s = sHat.mapElements(a => a + deltasHat)

    Initialization(x = x, lambda = lambdaTilda, s = s, rows = n, cols = m)
  }
}
