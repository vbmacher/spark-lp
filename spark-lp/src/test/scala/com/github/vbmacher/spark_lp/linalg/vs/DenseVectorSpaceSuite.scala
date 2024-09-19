package com.github.vbmacher.spark_lp.linalg.vs

import com.github.vbmacher.spark_lp.util.TestingUtils._
import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.scalatest.funsuite.AnyFunSuite

class DenseVectorSpaceSuite extends AnyFunSuite {

  // taken from first iteration
  test("DenseVectorSpace.combine is implemented properly") {
    val alpha = -1.0
    val a = new DenseVector(Array(
      1037.8830194476832,919.2678172250901,1215.8058227815707,59.30760111129604,59.307601111296094
    ))
    val beta = 1.0
    val b = new DenseVector(Array(
      130.74622637382816,109.16609581526643,124.20404493660249,10.918025554611186,11.577626670204133
    ))
    val expectedCombination = Vectors.dense(
      -907.1367930738562, -810.1017214098238, -1091.6017778449684, -48.38957555668489, -47.729974441092
    )
    assert(DenseVectorSpace.combine(alpha, a, beta, b) ~= expectedCombination relTol 1e-6,
      "DenseVectorSpace.combine should return the correct result.")
  }

  test("DenseVectorSpace.dot is implemented properly") {
    val a = new DenseVector(Array(2.0, 3.0))
    val b = new DenseVector(Array(5.0, 6.0))
    val expectedDot = 2.0 * 5.0 + 3.0 * 6.0
    assert(DenseVectorSpace.dot(a, b) == expectedDot,
      "DenseVectorSpace.dot should return the correct result.")
  }

  test("DenseVectorSpace.entrywiseProd is implemented properly") {
    val a = new DenseVector(Array(2.0, 3.0))
    val b = new DenseVector(Array(5.0, 6.0))
    val expectedEntrywiseProd = new DenseVector(Array(2.0 * 5.0, 3.0 * 6.0))
    assert(DenseVectorSpace.entrywiseProd(a, b) == expectedEntrywiseProd,
      "DenseVectorSpace.entrywiseProd should return the correct result.")
  }

  test("DenseVectorSpace.entrywiseNegDiv is implemented properly") {
    val a = new DenseVector(Array(2,0, 3.0))
    val b = new DenseVector(Array(-1.0, 1.0))
    val expectedEntrywiseNegDiv = new DenseVector(Array(2.0 / math.abs(-1.0), Double.PositiveInfinity ))
    assert(DenseVectorSpace.entrywiseNegDiv(a, b) == expectedEntrywiseNegDiv,
      "DenseVectorSpace.entrywiseNegDiv should return the correct result.")
  }

  test("DenseVectorSpace.sum is implemented properly") {
    val a = new DenseVector(Array(1.0, 2,0, 3.0))
    val expectedSum = 1.0 + 2.0 + 3.0
    assert(DenseVectorSpace.sum(a) == expectedSum,
      "DenseVectorSpace.sum should return the correct result.")
  }

  test("DenseVectorSpace.max is implemented properly") {
    val a = new DenseVector(Array(1.0, 2,0, 3.0))
    val expectedMax = 3.0
    assert(DenseVectorSpace.max(a) == expectedMax,
      "DenseVectorSpace.max should return the correct result.")
  }

  test("DenseVectorSpace.min is implemented properly") {
    val a = new DenseVector(Array(1.0, 2.0, 3.0))
    val expectedMin = 1.0
    assert(DenseVectorSpace.min(a) == expectedMin,
      "DenseVectorSpace.min should return the correct result.")
  }


}
