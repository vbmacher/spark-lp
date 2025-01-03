package org.apache.spark.mllib.optimization.lp

import breeze.linalg.{DenseMatrix => BDM}
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.optimization.lp.util.TestingUtils._
import org.scalatest.funsuite.AnyFunSuite

class UtilSuite extends AnyFunSuite {

  test(" toUpperTriangularArray is implemented properly") {
    val A: BDM[Double] = new BDM[Double](3, 3,
      Array(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0))
    assert(Util.toUpperTriangularArray(A).deep == Array(1.0, 4.0, 5.0, 7.0, 8.0, 9.0).deep,
      "Arrays are not equal!")
  }

  test("posSymDefInv is implemented properly") {
    val A = Array(5.0, 8.0, 13.0) // packed column-wise format sym pos def mat
    Util.posSymDefInv(A, 2)
    assert(Vectors.dense(A) ~== Vectors.dense(Array(13.0, -8.0, 5.0)) absTol 1e-8)
  }
}
