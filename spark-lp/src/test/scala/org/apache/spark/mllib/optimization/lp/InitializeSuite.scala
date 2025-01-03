package org.apache.spark.mllib.optimization.lp

import breeze.linalg.{DenseMatrix => BDM, DenseVector => BDV, _}
import VectorSpace._
import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.apache.spark.mllib.optimization.lp.util.TestingUtils._
import org.apache.spark.mllib.optimization.lp.util.MLlibTestSparkContext
import org.apache.spark.mllib.optimization.lp.vs.dvector.DVectorSpace
import org.apache.spark.mllib.optimization.lp.vs.vector.DenseVectorSpace
import org.scalatest.funsuite.AnyFunSuite

class InitializeSuite extends AnyFunSuite with MLlibTestSparkContext {

  val numPartitions = 2
  val cArray: Array[Double] = Array(2.0, 1.5, 0.0, 0.0, 0.0, 0.0, 0.0)
  val BArray: Array[Array[Double]] = Array(
    Array(12.0, 16.0, 30.0, 1.0, 0.0),
    Array(24.0, 16.0, 12.0, 0.0, 1.0),
    Array(-1.0, 0.0, 0.0, 0.0, 0.0),
    Array(0.0, -1.0, 0.0, 0.0, 0.0),
    Array(0.0, 0.0, -1.0, 0.0, 0.0),
    Array(0.0, 0.0, 0.0, 1.0, 0.0),
    Array(0.0, 0.0, 0.0, 0.0, 1.0))
  val bArray: Array[Double] = Array(120.0, 120.0, 120.0, 15.0, 15.0)

  lazy val c: DVector = sc.parallelize(cArray, numPartitions).glom.map(new DenseVector(_))
  lazy val rows: DMatrix = sc.parallelize(BArray, numPartitions).map(Vectors.dense)
  lazy val b: DenseVector = new DenseVector(bArray)

  val cBrz = new BDV[Double](cArray)
  val BBrz = new BDM[Double](7, 5,
    BArray.flatten,
    offset = 0,
    majorStride = 5,
    isTranspose = true)
  val bBrz = new BDV[Double](bArray)

  // (BT * B) ^(-1)
  val BTBInv: BDM[Double] = inv(BBrz.t * BBrz)

  // xTilda = B * BTBInv * b
  val xTilda: BDV[Double] = BBrz * (BTBInv * bBrz)
  // lambdaTilda = BTBInv * (B^T * c)
  val lambdaTilda: BDV[Double] = BTBInv * (BBrz.t * cBrz)

  // sTilda = c - B * lambdaTilda
  val sTilda: BDV[Double] = cBrz - BBrz * lambdaTilda
  val deltax: Double = Math.max(1.5 * max(xTilda), 0)
  val deltas: Double = Math.max(1.5 * max(sTilda), 0)
  val xHat: BDV[Double] = xTilda + deltax
  val sHat: BDV[Double] = sTilda + deltas
  val deltaxHat: Double = 0.5 * (xHat.t * sHat) / sum(sHat)
  val deltasHat: Double = 0.5 * (xHat.t * sHat) / sum(xHat)

  // x = xHat + deltaxHat * e
  val expectedx: BDV[Double] = xHat + deltaxHat
  // val expectedLambda = lambdaTilda
  val expecteds: BDV[Double] = sHat + deltasHat


  test("Initialize.init is implemented properly") {

    val result = Initialize.init(c, rows, b)
    //println(LP.solve(c, rows, b, 1e-4, 1).collect())
    assert(Vectors.dense(expectedx.toArray) ~= Vectors.dense(result._1.flatMap(_.toArray).collect()) relTol 1e-6,
      "Initialize.init x0 is not computed correctly.")
    assert(Vectors.dense(lambdaTilda.toArray) ~= Vectors.dense(result._2.toArray) relTol 1e-6,
      "Initialize.init lambda0 is not computed correctly.")
    assert(Vectors.dense(expecteds.toArray) ~= Vectors.dense(result._3.flatMap(_.toArray).collect()) relTol 1e-6,
      "Initialize.init s0 should return the correct answer.")
  }
}
