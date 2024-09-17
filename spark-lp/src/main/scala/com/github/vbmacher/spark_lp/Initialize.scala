package com.github.vbmacher.spark_lp

import breeze.linalg.{DenseVector => BDV}
import com.github.vbmacher.spark_lp.VectorSpace._
import com.github.vbmacher.spark_lp.fs.dmatrix.vector.LPRowMatrix
import com.github.vbmacher.spark_lp.fs.dvector.vector.LinopMatrixSpark
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
    * Ax = b
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
    val dmatT = new LinopMatrixSpark(rows)

    val n: Long = rows.count()
    println(s"number of unknowns: $n")

    val m: Int = rows.first().size
    println(s"number of equations: $m")

    val B: LPRowMatrix = new LPRowMatrix(rows, n, m)

    println(s"B = ${B.rows.collect().mkString(",")}")

    val BTB: BDV[Double] = B.computeGramianMatrixColumn(m, depth = 2)

    println(s"gramB = ${BTB}")

    val BTBtoArrayToInv = BTB.toArray

    Util.posSymDefInv(BTBtoArrayToInv, m) // less space with managed side effect

    println(s"BTBtoArrayToInv = ${BTBtoArrayToInv.toList}")

    val BTBInv: Matrix = Util.triuToFull(BTBtoArrayToInv, m)

    println(s"BTBInv = ${BTBInv}")

    // xTilda = B^T * BTBInv * b
    // NOTE: BTBInv and BTBInv * b are local matrix and vector

    println(s"BTBInv.multiply(b) = ${BTBInv.multiply(b)}" )

    val xTilda: DVector = dmat(BTBInv.multiply(b)) // DMatrix * DenseVector
   // xTilda.cache()

    println(s"xTilda = ${xTilda.collect().mkString(",")}")

    // deltax = max(1.5 * xTilda.max(), 0)
    val deltax: Double = math.max(1.5 * row.max(xTilda), 0)

    println(s"deltax = ${deltax}")

    // lambdaTilda = BTBInv * B^T * c
    val lambdaTilda: DenseVector = BTBInv.multiply(dmatT(c))

    // sTilda = c - B * lambdaTilda
    val sTilda: DVector = c.diff(dmat(lambdaTilda))

    // deltas = max(1.5 * sTilda.max(), 0)
   // val deltas: Double = math.max(-1.5 * row.min(sTilda), 0)
    val deltas: Double = math.max(1.5 * row.max(sTilda), 0)

    // xHat = xTilda + deltax * e
    val xHat: DVector = xTilda.mapElements(a => a + deltax)
    println(s"xHat = ${xHat.collect().mkString(",")}")

    // sHat = sTilda + deltas * e
    val sHat: DVector = sTilda.mapElements(a => a + deltas)
    println(s"sHat = ${sHat.collect().mkString(",")}")

    // deltaxHat = 0.5 * (xHat, sHat) / (e, sHat)
    val deltaxHat: Double = 0.5 * (xHat.dot(sHat) / row.sum(sHat))
    println(s"deltaxHat = $deltaxHat")

    // deltasHat = 0.5 * (xHat, sHat) / (e, xHat)
    val deltasHat: Double = 0.5 * (xHat.dot(sHat) / row.sum(xHat))
    println(s"deltasHat = $deltasHat")

    // x = xHat + deltaxHat * e
    val x = xHat.mapElements(a => a + deltaxHat)

    // lambda = lambdaTilda
    // s = sHat + deltasHat * e
    val s = sHat.mapElements(a => a + deltasHat)

    (x, lambdaTilda, s, n, m)
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
  ): (DVector, DenseVector, DVector, DenseVector, Long, Int) = {

    row.cache(c)
    rows.cache()
    val dmat = new LinopMatrix(rows)
    val dmatT = new LinopMatrixSpark(rows)

    val n: Long = rows.count()
    println(s"number of unknowns: $n")

    val m: Int = rows.first().size
    println(s"number of equations: $m")

    val B: LPRowMatrix = new LPRowMatrix(rows, n, m)

    println(s"B = ${B.rows.collect().mkString(",")}")

    val BTB: BDV[Double] = B.computeGramianMatrixColumn(m, depth = 2)

    println(s"gramB = ${BTB}")

    val BTBtoArrayToInv = BTB.toArray

    Util.posSymDefInv(BTBtoArrayToInv, m) // less space with managed side effect

    println(s"BTBtoArrayToInv = ${BTBtoArrayToInv.toList}")

    val BTBInv: Matrix = Util.triuToFull(BTBtoArrayToInv, m)

    println(s"BTBInv = ${BTBInv}")

    // xTilda = B^T * BTBInv * b
    // NOTE: BTBInv and BTBInv * b are local matrix and vector

    println(s"BTBInv.multiply(b) = ${BTBInv.multiply(b)}" )

    val xTilda: DVector = dmat(BTBInv.multiply(b)) // DMatrix * DenseVector
    // xTilda.cache()

    println(s"xTilda = ${xTilda.collect().mkString(",")}")

    // deltax = max(1.5 * xTilda.max(), 0)
    val deltax: Double = math.max(1.5 * row.max(xTilda), 0)

    println(s"deltax = ${deltax}")

    // lambdaTilda = BTBInv * B^T * c
    val lambdaTilda: DenseVector = BTBInv.multiply(dmatT(c))

    // sTilda = c - B * lambdaTilda
    val sTilda: DVector = c.diff(dmat(lambdaTilda))

    // deltas = max(1.5 * sTilda.max(), 0)
    // val deltas: Double = math.max(-1.5 * row.min(sTilda), 0)
    val deltas: Double = math.max(1.5 * row.max(sTilda), 0)

    // xHat = xTilda + deltax * e
    val xHat: DVector = xTilda.mapElements(a => a + deltax)
    println(s"xHat = ${xHat.collect().mkString(",")}")

    // sHat = sTilda + deltas * e
    val sHat: DVector = sTilda.mapElements(a => a + deltas)
    println(s"sHat = ${sHat.collect().mkString(",")}")

    // deltaxHat = 0.5 * (xHat, sHat) / (e, sHat)
    val deltaxHat: Double = 0.5 * (xHat.dot(sHat) / row.sum(sHat))
    println(s"deltaxHat = $deltaxHat")

    // deltasHat = 0.5 * (xHat, sHat) / (e, xHat)
    val deltasHat: Double = 0.5 * (xHat.dot(sHat) / row.sum(xHat))
    println(s"deltasHat = $deltasHat")

    // x = xHat + deltaxHat * e
    val x = xHat.mapElements(a => a + deltaxHat)

    // lambda = lambdaTilda
    // s = sHat + deltasHat * e
    val s = sHat.mapElements(a => a + deltasHat)

    import LP._
    val slack = new org.apache.spark.mllib.linalg.DenseVector((b.toBreeze - dmatT(x).toBreeze).toArray)

    val minSlack = slack.values.min
    if (minSlack < 0) {
      for (i <- 0 to slack.values.length) {
        slack.values(i) = slack.values(i) + minSlack + 0.1 // only positive slack
      }
    }

    (x, lambdaTilda, s, slack, n, m)
  }
}
