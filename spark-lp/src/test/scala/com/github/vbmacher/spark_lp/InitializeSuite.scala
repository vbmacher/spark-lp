package com.github.vbmacher.spark_lp

import breeze.linalg.{DenseMatrix => BDM, DenseVector => BDV, _}
import TestingUtils._
import com.github.vbmacher.spark_lp.vectors.{DMatrix, DVector}
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.scalatest.funsuite.AnyFunSuite

class InitializeSuite extends AnyFunSuite with DataFrameSuiteBase {

  val numPartitions = 2
  val cArray: Array[Double] = Array(2.0, 1.5, 0.0, 0.0, 0.0, 0.0, 0.0)
  val AArray: Array[Array[Double]] = Array(
    Array(12.0, 16.0, 30.0, 1.0, 0.0),
    Array(24.0, 16.0, 12.0, 0.0, 1.0),
    Array(-1.0, 0.0, 0.0, 0.0, 0.0),
    Array(0.0, -1.0, 0.0, 0.0, 0.0),
    Array(0.0, 0.0, -1.0, 0.0, 0.0),
    Array(0.0, 0.0, 0.0, 1.0, 0.0),
    Array(0.0, 0.0, 0.0, 0.0, 1.0))
  val bArray: Array[Double] = Array(120.0, 120.0, 120.0, 15.0, 15.0)

  lazy val c: DVector = sc.parallelize(cArray, numPartitions).glom.map(new DenseVector(_))
  lazy val A: DMatrix = sc.parallelize(AArray, numPartitions).map(Vectors.dense)
  lazy val b: DenseVector = new DenseVector(bArray)

  val cBrz = new BDV[Double](cArray)
  val ABrz = new BDM[Double](7, 5,
    AArray.flatten,
    offset = 0,
    majorStride = 5,
    isTranspose = true)
  val bBrz = new BDV[Double](bArray)

  // (A^T * A) ^(-1)
  val ATAInv: BDM[Double] = inv(ABrz.t * ABrz)

  // xTilda = A * ATAInv * b
  val xTilda: BDV[Double] = ABrz * (ATAInv * bBrz)
  // lambdaTilda = ATAInv * (A^T * c)
  val lambdaTilda: BDV[Double] = ATAInv * (ABrz.t * cBrz)

  // sTilda = c - A * lambdaTilda
  val sTilda: BDV[Double] = cBrz - ABrz * lambdaTilda
  val deltax: Double = Math.max(1.5 * max(xTilda), 0)
  val deltas: Double = Math.max(1.5 * max(sTilda), 0)
  val xHat: BDV[Double] = xTilda + deltax
  val sHat: BDV[Double] = sTilda + deltas
  val deltaxHat: Double = 0.5 * (xHat.t * sHat) / sum(sHat)
  val deltasHat: Double = 0.5 * (xHat.t * sHat) / sum(xHat)

  // x = xHat + deltaxHat * e
  val expectedX: BDV[Double] = xHat + deltaxHat
  // val expectedLambda = lambdaTilda
  val expectedS: BDV[Double] = sHat + deltasHat


  test("Initialize.init is implemented properly") {
    val result = Initialize.init(c, A, b)
    //println(LP.solve(c, A, b, 1e-4, 1).collect())
    assert(Vectors.dense(expectedX.toArray) ~= Vectors.dense(result.x.flatMap(_.toArray).collect()) relTol 1e-6,
      "Initialize.init x0 is not computed correctly.")
    assert(Vectors.dense(lambdaTilda.toArray) ~= Vectors.dense(result.lambda.toArray) relTol 1e-6,
      "Initialize.init lambda0 is not computed correctly.")
    assert(Vectors.dense(expectedS.toArray) ~= Vectors.dense(result.s.flatMap(_.toArray).collect()) relTol 1e-6,
      "Initialize.init s0 should return the correct answer.")
  }
}
