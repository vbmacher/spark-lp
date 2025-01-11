package com.github.vbmacher.spark_lp.vectors

import com.github.vbmacher.spark_lp.TestingUtils._
import com.github.vbmacher.spark_lp.vectors.dense_vector.implicits.DenseVectorOps
import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.scalatest.funsuite.AnyFunSuite

class DenseVectorSuite extends AnyFunSuite {

  test("combine is implemented properly") {
    // taken from first iteration

    val alpha = -1.0
    val a = new DenseVector(Array(
      1037.8830194476832, 919.2678172250901, 1215.8058227815707, 59.30760111129604, 59.307601111296094
    ))
    val beta = 1.0
    val b = new DenseVector(Array(
      130.74622637382816, 109.16609581526643, 124.20404493660249, 10.918025554611186, 11.577626670204133
    ))
    val expectedCombination = Vectors.dense(
      -907.1367930738562, -810.1017214098238, -1091.6017778449684, -48.38957555668489, -47.729974441092
    )
    assert(a.combine(alpha, beta, b) ~= expectedCombination relTol 1e-6,
      "combine should return the correct result.")
  }

  test("dot is implemented properly") {
    val a = new DenseVector(Array(2.0, 3.0))
    val b = new DenseVector(Array(5.0, 6.0))
    val expectedDot = 2.0 * 5.0 + 3.0 * 6.0

    assert(a.dot(b) == expectedDot, "dot should return the correct result.")
  }

  test("entrywiseProd is implemented properly") {
    val a = new DenseVector(Array(2.0, 3.0))
    val b = new DenseVector(Array(5.0, 6.0))
    val expectedEntrywiseProd = new DenseVector(Array(2.0 * 5.0, 3.0 * 6.0))

    assert(a.entrywiseProd(b) == expectedEntrywiseProd, "entrywiseProd should return the correct result.")
  }

  test("entrywiseNegDiv is implemented properly") {
    val a = new DenseVector(Array(2, 0, 3.0))
    val b = new DenseVector(Array(-1.0, 1.0))
    val expectedEntrywiseNegDiv = new DenseVector(Array(2.0 / math.abs(-1.0), Double.PositiveInfinity))

    assert(a.entrywiseNegDiv(b) == expectedEntrywiseNegDiv, "entrywiseNegDiv should return the correct result.")
  }

  test("sum is implemented properly") {
    val a = new DenseVector(Array(1.0, 2, 0, 3.0))
    val expectedSum = 1.0 + 2.0 + 3.0
    assert(a.sum == expectedSum, "sum should return the correct result.")
  }

  test("maxValue is implemented properly") {
    val a = new DenseVector(Array(1.0, 2, 0, 3.0))
    val expectedMax = 3.0
    assert(a.maxValue == expectedMax, "maxValue should return the correct result.")
  }

  test("minValue is implemented properly") {
    val a = new DenseVector(Array(1.0, 2.0, 3.0))
    val expectedMin = 1.0
    assert(a.minValue == expectedMin, "minValue should return the correct result.")
  }
}
