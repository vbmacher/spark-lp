package com.github.vbmacher.spark_lp.linalg

import com.github.vbmacher.spark_lp.util.MLlibTestSparkContext
import com.github.vbmacher.spark_lp.linalg.dvector.implicits._
import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.scalatest.funsuite.AnyFunSuite

class DVectorSuite extends AnyFunSuite with MLlibTestSparkContext {

  test("Diagonal product is implemented properly") {
    val matrix: DMatrix = sc.parallelize(Array(
      Vectors.dense(1.0, 2.0, 3.0),
      Vectors.dense(4.0, 5.0, 6.0)),
      2)

    val vector: DVector = sc.parallelize(Array(2.0, 3.0), 2).glom.map(new DenseVector(_))

    val expectApply: DMatrix = sc.parallelize(Array(
      Vectors.dense(2.0 * 1.0, 2.0 * 2.0, 2.0 * 3.0),
      Vectors.dense(3.0 * 4.0, 3.0 * 5.0, 3.0 * 6.0)),
      2)
    assert(vector.diagonalProduct(matrix).collect().deep == expectApply.collect().deep, // or sameElements
      "SpLinopMatrix.apply should return the correct result.")
  }
}
