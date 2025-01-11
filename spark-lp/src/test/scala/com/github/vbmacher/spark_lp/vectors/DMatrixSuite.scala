package com.github.vbmacher.spark_lp.vectors

import com.holdenkarau.spark.testing.DataFrameSuiteBase
import dmatrix.implicits._
import org.apache.spark.mllib.linalg.Vectors
import org.scalatest.funsuite.AnyFunSuite

class DMatrixSuite extends AnyFunSuite with DataFrameSuiteBase {

  test("transpose works") {
    val matrix = Array(
      Array(-1.0, -1.0, 1.0, 0.0),
      Array(1.0, 0.0, 0.0, 1.0))

    val matrixT = matrix.transpose

    val dMatrix = sc.parallelize(matrix).map(Vectors.dense)
    val dMatrixT = dMatrix.t

    assert(matrix.map(Vectors.dense) === dMatrix.collect())
    assert(matrixT.map(Vectors.dense) === dMatrixT.collect())
  }
}
