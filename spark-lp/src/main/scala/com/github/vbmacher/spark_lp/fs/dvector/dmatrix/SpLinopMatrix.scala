package com.github.vbmacher.spark_lp.fs.dvector.dmatrix

import com.github.vbmacher.spark_lp.CheckedIteratorFunctions._
import com.github.vbmacher.spark_lp.LinearOperator
import com.github.vbmacher.spark_lp.VectorSpace._
import org.apache.spark.internal_access.BLAS
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.storage.StorageLevel

/**
  * Compute the product of a DVector to a DMatrix where each element of DVector is multiplied to
  * the corresponding row to produce a DMatrix. This is for optimizing the product of diagonal
  * DMatrix to a DMatrix.
  *
  * @param dvector The DVector representing a diagonal matrix.
  */
class SpLinopMatrix(@transient private val dvector: DVector)
  extends LinearOperator[DMatrix, DMatrix] with Serializable {

  if (dvector.getStorageLevel == StorageLevel.NONE) {
    dvector.cache()
  }

  /**
    * Apply the multiplication.
    *
    * @param mat The DMatrix for multiplication.
    * @return The result of applying the operator on x.
    */
  override def apply(mat: DMatrix): DMatrix = {
    dvector.zipPartitions(mat)((vectorPartition, matPartition) =>
      vectorPartition.next().values.toIterator.checkedZip(matPartition).map {
          case (a: Double, x: Vector) =>
            val xc = x.copy
              BLAS.scal(a, xc)
            xc
        }
      )
  }
}