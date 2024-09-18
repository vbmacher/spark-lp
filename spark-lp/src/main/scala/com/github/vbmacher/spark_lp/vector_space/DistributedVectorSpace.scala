package com.github.vbmacher.spark_lp.vector_space

import com.github.vbmacher.spark_lp.dvector.DVector
import com.github.vbmacher.spark_lp.dvector.implicits._
import org.apache.spark.internal_access.BLAS
import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.storage.StorageLevel

/**
  * Vector space for distributed computations
  */
object DistributedVectorSpace extends VectorSpace[DVector] {

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
          LocalVectorSpace.combine(alpha, aPart, beta, bPart).toDense
      }
    }

  override def dot(a: DVector, b: DVector): Double = a.dot(b)

  override def entrywiseProd(a: DVector, b: DVector): DVector = {
    a.zip(b).map {
      case (aPart, bPart) =>
        LocalVectorSpace.entrywiseProd(aPart, bPart).toDense
    }
  }

  override def entrywiseNegDiv(a: DVector, b: DVector): DVector = {
    a.zip(b).map {
      case (aPart, bPart) =>
        LocalVectorSpace.entrywiseNegDiv(aPart, bPart)
    }
  }

  override def sum(a: DVector): Double = a.aggregate(0.0)(
    seqOp = (acc: Double, v: DenseVector) => acc + v.values.sum,
    combOp = (acc1: Double, acc2: Double) => acc1 + acc2
  )

  override def min(a: DVector): Double = a.aggregate(Double.PositiveInfinity)(
    (mi, x) => Math.min(mi, x.values.min), Math.min
  )

  override def max(a: DVector): Double = a.aggregate(Double.NegativeInfinity)(
    (ma, x) => Math.max(ma, x.values.max), Math.max
  )


  override def cache(a: DVector): Unit =
    if (a.getStorageLevel == StorageLevel.NONE) {
      a.cache()
    }
}
