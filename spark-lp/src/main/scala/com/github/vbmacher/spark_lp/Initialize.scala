package com.github.vbmacher.spark_lp

import com.github.vbmacher.spark_lp.vectors.breeze_ops.{symPosDefInverse, triuToFull}
import com.github.vbmacher.spark_lp.vectors.dmatrix.implicits._
import com.github.vbmacher.spark_lp.vectors.dvector.implicits._
import com.github.vbmacher.spark_lp.vectors.{DMatrix, DVector}
import com.typesafe.scalalogging.LazyLogging
import org.apache.spark.mllib.linalg.DenseVector

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
    require(!A.isEmpty(), "Matrix A (constraint matrix) must not be empty")

    c.cacheIfNoStorageLevel()
    A.cache()

    val rows = A.count()
    val columns = A.first().size

    logger.info(s"Number of unknowns: $rows")
    logger.info(s"Number of equations: $columns")

    val BTB = A.gramianMatrix(columns) // Returns symmetric positive-definite matrix, if A columns are linearly independent
    val BTBtoArrayToInv = BTB.toArray

    symPosDefInverse(BTBtoArrayToInv, columns) // less space with managed side effect
    val BTBInv = triuToFull(BTBtoArrayToInv, columns)

    // xTilda = B^T * BTBInv * b
    // NOTE: BTBInv and BTBInv * b are local matrix and vector
    val xTilda = A.product(BTBInv.multiply(b))

    // deltax = max(1.5 * xTilda.max(), 0)
    val deltax: Double = math.max(1.5 * xTilda.maxValue, 0)

    // xHat = xTilda + deltax * e
    val xHat: DVector = xTilda.mapElements(a => a + deltax)

    // lambdaTilda = BTBInv * B^T * c
    val lambdaTilda: DenseVector = BTBInv.multiply(A.adjointProduct(c))

    // sTilda = c - B * lambdaTilda
    val sTilda: DVector = c.diff(A.product(lambdaTilda))

    // val deltas: Double = math.max(-1.5 * row.min(sTilda), 0) // TODO: check this
    // deltas = max(1.5 * sTilda.max(), 0)
    val deltas: Double = math.max(1.5 * sTilda.maxValue, 0)

    // sHat = sTilda + deltas * e
    val sHat: DVector = sTilda.mapElements(a => a + deltas)

    // deltaxHat = 0.5 * (xHat, sHat) / (e, sHat)
    val deltaxHat: Double = 0.5 * (xHat.dot(sHat) / sHat.sum())

    // deltasHat = 0.5 * (xHat, sHat) / (e, xHat)
    val deltasHat: Double = 0.5 * (xHat.dot(sHat) / xHat.sum())

    // x = xHat + deltaxHat * e
    val x = xHat.mapElements(a => a + deltaxHat)

    // lambda = lambdaTilda
    // s = sHat + deltasHat * e
    val s = sHat.mapElements(a => a + deltasHat)

    Initialization(x = x, lambda = lambdaTilda, s = s, rows = rows, cols = columns)
  }
}
