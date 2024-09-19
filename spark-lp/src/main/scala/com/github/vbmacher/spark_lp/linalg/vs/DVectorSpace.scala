package com.github.vbmacher.spark_lp.linalg.vs

import com.github.vbmacher.spark_lp.linalg.DVector
import com.github.vbmacher.spark_lp.linalg.dvector.implicits._
import org.apache.spark.internal_access.BLAS

/**
  * Vector space for distributed computations
  */
object DVectorSpace extends VectorSpace[DVector] {

  override def combine(alpha: Double, a: DVector, beta: Double, b: DVector): DVector =
    if (alpha == 1.0 && beta == 1.0) {
      a.zip(b).map {
        case (aPart, bPart) =>
          BLAS.axpy(1.0, aPart, bPart) // bPart += aPart
          bPart
      }
    } else {
      a.zip(b).map {
        case (aPart, bPart) =>
          // NOTE A DenseVector result is assumed here (not sparse safe).
          DenseVectorSpace.combine(alpha, aPart, beta, bPart).toDense
      }
    }

  override def dot(a: DVector, b: DVector): Double = a.dot(b)

  override def entrywiseProd(a: DVector, b: DVector): DVector = {
    a.zip(b).map {
      case (aPart, bPart) =>
        DenseVectorSpace.entrywiseProd(aPart, bPart).toDense
    }
  }

  override def entrywiseNegDiv(a: DVector, b: DVector): DVector = {
    a.zip(b).map {
      case (aPart, bPart) =>
        DenseVectorSpace.entrywiseNegDiv(aPart, bPart)
    }
  }

  override def sum(a: DVector): Double = a.sum()

  override def min(a: DVector): Double = a.minValue

  override def max(a: DVector): Double = a.maxValue
}