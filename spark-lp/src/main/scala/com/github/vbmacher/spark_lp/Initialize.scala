package com.github.vbmacher.spark_lp

import breeze.linalg.{DenseVector => BDV}
import com.github.vbmacher.spark_lp.dmatrix.DMatrix
import com.github.vbmacher.spark_lp.dmatrix.implicits._
import com.github.vbmacher.spark_lp.dvector.DVector
import com.github.vbmacher.spark_lp.dvector.implicits._
import com.github.vbmacher.spark_lp.vector_space.VectorSpace
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

  case class InitializationSlack(
    x: DVector,
    lambda: DenseVector,
    s: DVector,
    slack: DenseVector,
    rows: Long,
    columns: Int
  )

  /**
    * Compute the heuristic starting points.
    *
    * Ax = b
    *
    * @param c     the objective coefficient DVector.
    * @param rows  the constraint DMatrix.
    * @param b     the constraint values.
    * @param rowVS implicit for distributed computations (rows).
    * @param colVS implicit for local computations (columns).
    * @return starting points (x, lambda, s) and the computed dimensions of rows DMatrix (n, m).
    */
  def init(c: DVector, rows: DMatrix, b: DenseVector)(
    implicit rowVS: VectorSpace[DVector], colVS: VectorSpace[DenseVector]
  ): Initialization = {

    rowVS.cache(c)
    rows.cache()

    val n: Long = rows.count()
    println(s"number of unknowns: $n")

    val m: Int = rows.first().size
    println(s"number of equations: $m")

    val B = rows
    val BTB: BDV[Double] = B.gramianMatrix(m, depth = 2)
    val BTBtoArrayToInv = BTB.toArray
    Util.posSymDefInv(BTBtoArrayToInv, m) // less space with managed side effect

    val BTBInv: Matrix = Util.triuToFull(BTBtoArrayToInv, m)

    // xTilda = B^T * BTBInv * b
    // NOTE: BTBInv and BTBInv * b are local matrix and vector
    val xTilda: DVector = rows.product(BTBInv.multiply(b)) // DMatrix * DenseVector

    // deltax = max(1.5 * xTilda.max(), 0)
    val deltax: Double = math.max(1.5 * rowVS.max(xTilda), 0)

    // lambdaTilda = BTBInv * B^T * c
    val lambdaTilda: DenseVector = BTBInv.multiply(rows.adjointProduct(c))

    // sTilda = c - B * lambdaTilda
    val sTilda: DVector = c.diff(rows.product(lambdaTilda))

    // deltas = max(1.5 * sTilda.max(), 0)
    // val deltas: Double = math.max(-1.5 * row.min(sTilda), 0)
    val deltas: Double = math.max(1.5 * rowVS.max(sTilda), 0)

    // xHat = xTilda + deltax * e
    val xHat: DVector = xTilda.mapElements(a => a + deltax)

    // sHat = sTilda + deltas * e
    val sHat: DVector = sTilda.mapElements(a => a + deltas)

    // deltaxHat = 0.5 * (xHat, sHat) / (e, sHat)
    val deltaxHat: Double = 0.5 * (xHat.dot(sHat) / rowVS.sum(sHat))

    // deltasHat = 0.5 * (xHat, sHat) / (e, xHat)
    val deltasHat: Double = 0.5 * (xHat.dot(sHat) / rowVS.sum(xHat))

    // x = xHat + deltaxHat * e
    val x = xHat.mapElements(a => a + deltaxHat)

    // lambda = lambdaTilda
    // s = sHat + deltasHat * e
    val s = sHat.mapElements(a => a + deltasHat)

    Initialization(x = x, lambda = lambdaTilda, s = s, rows = n, cols = m)
  }


  /**
    * Compute the heuristic starting points.
    *
    * Ax <= b
    *
    * @param c    the objective coefficient DVector.
    * @param rows the constraint DMatrix.
    * @param b    the constraint values.
    * @param row  implicit for distributed computations.
    * @param col  implicit for local computations.
    * @return starting points (x, lambda, s, slack) and the computed dimensions of rows DMatrix (n, m).
    */
  def initWithSlack(c: DVector, rows: DMatrix, b: DenseVector)(
    implicit row: VectorSpace[DVector], col: VectorSpace[DenseVector]
  ): InitializationSlack = {

    row.cache(c)
    rows.cache()

    val n: Long = rows.count()
    println(s"number of unknowns: $n")

    val m: Int = rows.first().size
    println(s"number of equations: $m")

    val B = rows


    val BTB: BDV[Double] = B.gramianMatrix(m, depth = 2)
    val BTBtoArrayToInv = BTB.toArray
    Util.posSymDefInv(BTBtoArrayToInv, m) // less space with managed side effect

    val BTBInv: Matrix = Util.triuToFull(BTBtoArrayToInv, m)

    // xTilda = B^T * BTBInv * b
    // NOTE: BTBInv and BTBInv * b are local matrix and vector
    val xTilda: DVector = rows.product(BTBInv.multiply(b)) // DMatrix * DenseVector

    // deltax = max(1.5 * xTilda.max(), 0)
    val deltax: Double = math.max(1.5 * row.max(xTilda), 0)

    // lambdaTilda = BTBInv * B^T * c
    val lambdaTilda: DenseVector = BTBInv.multiply(rows.adjointProduct(c))

    // sTilda = c - B * lambdaTilda
    val sTilda: DVector = c.diff(rows.product(lambdaTilda))

    // deltas = max(1.5 * sTilda.max(), 0)
    // val deltas: Double = math.max(-1.5 * row.min(sTilda), 0)
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

    import LP._
    val slack = new org.apache.spark.mllib.linalg.DenseVector((b.toBreeze - rows.adjointProduct(x).toBreeze).toArray)

    val minSlack = slack.values.min
    if (minSlack < 0) {
      for (i <- 0 to slack.values.length) {
        slack.values(i) = slack.values(i) + minSlack + 0.1 // only positive slack
      }
    }

    InitializationSlack(x = x, lambda = lambdaTilda, s = s, slack = slack, rows = n, columns = m)
  }
}
