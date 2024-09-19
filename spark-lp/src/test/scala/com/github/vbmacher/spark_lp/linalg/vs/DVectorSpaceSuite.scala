package com.github.vbmacher.spark_lp.linalg.vs

import com.github.vbmacher.spark_lp.linalg.dvector.implicits._
import com.github.vbmacher.spark_lp.util.MLlibTestSparkContext
import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.scalatest.funsuite.AnyFunSuite

class DVectorSpaceSuite extends AnyFunSuite with MLlibTestSparkContext {

  test("DVectorSpace.combine is implemented properly") {
    val alpha = 1.1
    val a = sc.parallelize(Array(new DenseVector(Array(2.0, 3.0)), new DenseVector(Array(4.0))), 2)
    val beta = 4.0
    val b = sc.parallelize(Array(new DenseVector(Array(5.0, 6.0)), new DenseVector(Array(7.0))), 2)
    val combination = DVectorSpace.combine(alpha, a, beta, b)
    val expectedCombination =
      Vectors.dense(1.1 * 2.0 + 4.0 * 5.0, 1.1 * 3.0 + 4.0 * 6.0, 1.1 * 4.0 + 4.0 * 7.0)
    assert(Vectors.dense(combination.collectElements) == expectedCombination,
      "DVectorSpace.combine should return the correct result.")
  }

  test("DVectorSpace.dot is implemented properly") {
    val a = sc.parallelize(Array(new DenseVector(Array(2.0, 3.0)), new DenseVector(Array(4.0))), 2)
    val b = sc.parallelize(Array(new DenseVector(Array(5.0, 6.0)), new DenseVector(Array(7.0))), 2)
    val expectedDot = 2.0 * 5.0 + 3.0 * 6.0 + 4.0 * 7.0
    assert(DVectorSpace.dot(a, b) == expectedDot,
      "DVectorSpace.dot should return the correct result.")
  }

  test("DVectorSpace.entrywiseProd is implemented properly") {
    val a = sc.parallelize(Array(new DenseVector(Array(2.0, 3.0)), new DenseVector(Array(4.0))), 2)
    val b = sc.parallelize(Array(new DenseVector(Array(5.0, 6.0)), new DenseVector(Array(7.0))), 2)
    val entrywiseProd = DVectorSpace.entrywiseProd(a, b)
    val expectedEntrywiseProd =
      Vectors.dense(Array(2.0 * 5.0, 3.0 * 6.0, 4.0 * 7.0))
    assert(Vectors.dense(entrywiseProd.collectElements) == expectedEntrywiseProd,
      "DVectorSpace.entrywiseProd should return the correct result.")
  }

  test("DVectorSpace.entrywiseNegDiv is implemented properly") {
    val a = sc.parallelize(Array(new DenseVector(Array(2.0, 3.0)), new DenseVector(Array(4.0))), 2)
    val b = sc.parallelize(Array(new DenseVector(Array(5.0, -6.0)), new DenseVector(Array(0.0))), 2)
    val entrywiseNegDiv = DVectorSpace.entrywiseNegDiv(a, b)
    val expectedEntrywiseNegDiv =
      Vectors.dense(Array(Double.PositiveInfinity, 3.0 / math.abs(-6.0), Double.PositiveInfinity))
    assert(Vectors.dense(entrywiseNegDiv.collectElements) == expectedEntrywiseNegDiv,
      "DVectorSpace.entrywiseNegDiv should return the correct result.")
  }

  test("DVectorSpace.sum is implemented properly") {
    val a = sc.parallelize(Array(new DenseVector(Array(2.0, 3.0)), new DenseVector(Array(4.0))), 2)
    val expectedSum = 2.0 + 3.0 + 4.0
    assert(DVectorSpace.sum(a) == expectedSum,
      "DVectorSpace.sum should return the correct result.")
  }

  test("DVectorSpace.max is implemented properly") {
    val a = sc.parallelize(Array(new DenseVector(Array(2.0, 3.0)), new DenseVector(Array(4.0))), 2)
    val expectedMax = 4.0
    assert(DVectorSpace.max(a) == expectedMax,
      "DVectorSpace.max should return the correct result.")
  }

  test("DVectorSpace.min is implemented properly") {
    val a = sc.parallelize(Array(new DenseVector(Array(2.0, 3.0)), new DenseVector(Array(4.0))), 2)
    val expectedMin = 2.0
    assert(DVectorSpace.min(a) == expectedMin,
      "DVectorSpace.min should return the correct result.")
  }
}
