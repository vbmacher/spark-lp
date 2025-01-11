package com.github.vbmacher.spark_lp.vectors

import com.holdenkarau.spark.testing.DataFrameSuiteBase
import dvector.implicits._
import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.scalatest.funsuite.AnyFunSuite

class DVectorSuite extends AnyFunSuite with DataFrameSuiteBase {

  test("Diagonal product is implemented properly") {
    val matrix = sc.parallelize(Array(
      Vectors.dense(1.0, 2.0, 3.0),
      Vectors.dense(4.0, 5.0, 6.0)),
      2)

    val vector = sc.parallelize(Array(2.0, 3.0), 2).glom.map(new DenseVector(_))

    val expectApply = sc.parallelize(Array(
      Vectors.dense(2.0 * 1.0, 2.0 * 2.0, 2.0 * 3.0),
      Vectors.dense(3.0 * 4.0, 3.0 * 5.0, 3.0 * 6.0)),
      2)

    assert(vector.diagonalProduct(matrix).collect().deep == expectApply.collect().deep, // or sameElements
      "diagonalProduct should return the correct result.")
  }

  test("combine is implemented properly") {
    val alpha = 1.1
    val a = sc.parallelize(Array(new DenseVector(Array(2.0, 3.0)), new DenseVector(Array(4.0))), 2)
    val beta = 4.0
    val b = sc.parallelize(Array(new DenseVector(Array(5.0, 6.0)), new DenseVector(Array(7.0))), 2)
    val combination = a.combine(alpha, beta, b)
    val expectedCombination =
      Vectors.dense(1.1 * 2.0 + 4.0 * 5.0, 1.1 * 3.0 + 4.0 * 6.0, 1.1 * 4.0 + 4.0 * 7.0)

    assert(Vectors.dense(combination.collectElements) == expectedCombination,
      "combine should return the correct result.")
  }

  test("dot is implemented properly") {
    val a = sc.parallelize(Array(new DenseVector(Array(2.0, 3.0)), new DenseVector(Array(4.0))), 2)
    val b = sc.parallelize(Array(new DenseVector(Array(5.0, 6.0)), new DenseVector(Array(7.0))), 2)
    val expectedDot = 2.0 * 5.0 + 3.0 * 6.0 + 4.0 * 7.0

    assert(a.dot(b) == expectedDot, "dot should return the correct result.")
  }

  test("entrywiseProd is implemented properly") {
    val a = sc.parallelize(Array(new DenseVector(Array(2.0, 3.0)), new DenseVector(Array(4.0))), 2)
    val b = sc.parallelize(Array(new DenseVector(Array(5.0, 6.0)), new DenseVector(Array(7.0))), 2)
    val entrywiseProd = a.entrywiseProd(b)
    val expectedEntrywiseProd = Vectors.dense(Array(2.0 * 5.0, 3.0 * 6.0, 4.0 * 7.0))

    assert(Vectors.dense(entrywiseProd.collectElements) == expectedEntrywiseProd,
      "entrywiseProd should return the correct result.")
  }

  test("entrywiseNegDiv is implemented properly") {
    val a = sc.parallelize(Array(new DenseVector(Array(2.0, 3.0)), new DenseVector(Array(4.0))), 2)
    val b = sc.parallelize(Array(new DenseVector(Array(5.0, -6.0)), new DenseVector(Array(0.0))), 2)
    val entrywiseNegDiv = a.entrywiseNegDiv(b)
    val expectedEntrywiseNegDiv = Vectors.dense(Array(Double.PositiveInfinity, 3.0 / math.abs(-6.0), Double.PositiveInfinity))

    assert(Vectors.dense(entrywiseNegDiv.collectElements) == expectedEntrywiseNegDiv,
      "entrywiseNegDiv should return the correct result.")
  }

  test("sum is implemented properly") {
    val a = sc.parallelize(Array(new DenseVector(Array(2.0, 3.0)), new DenseVector(Array(4.0))), 2)
    val expectedSum = 2.0 + 3.0 + 4.0
    assert(a.sum() == expectedSum, "sum should return the correct result.")
  }

  test("maxValue is implemented properly") {
    val a = sc.parallelize(Array(new DenseVector(Array(2.0, 3.0)), new DenseVector(Array(4.0))), 2)
    val expectedMax = 4.0
    assert(a.maxValue == expectedMax, "maxValue should return the correct result.")
  }

  test("minValue is implemented properly") {
    val a = sc.parallelize(Array(new DenseVector(Array(2.0, 3.0)), new DenseVector(Array(4.0))), 2)
    val expectedMin = 2.0
    assert(a.minValue == expectedMin, "minValue should return the correct result.")
  }
}
