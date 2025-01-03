package org.apache.spark.mllib.optimization.lp.fs.vector.dvector

import org.apache.spark.mllib.optimization.lp.VectorSpace._
import org.apache.spark.mllib.linalg.{BLAS, DenseVector}
import org.apache.spark.mllib.optimization.lp.LinearOperator
import org.apache.spark.storage.StorageLevel

/**
  * Compute the product of a DMatrix with a Vector to produce a DVector.
  *
  * @param matrix a DMatrix to compute the matrix product.
  */
class LinopMatrix(@transient private val matrix: DMatrix)
  extends LinearOperator[DenseVector, DVector] with Serializable {

  if (matrix.getStorageLevel == StorageLevel.NONE) {
    matrix.cache()
  }

  /**
    * Apply the multiplication.
    *
    * @param x The vector on which to apply the operator.
    * @return The result of applying the operator on x.
    */
  override def apply(x: DenseVector): DVector = {
    val bcX = matrix.context.broadcast(x)
    // Take the dot product of each matrix row with x.
    // NOTE A DenseVector result is assumed here (not sparse safe).
    matrix.mapPartitions(partitionRows =>
      Iterator.single(new DenseVector(partitionRows.map(row => BLAS.dot(row, bcX.value)).toArray)))
  }
}
