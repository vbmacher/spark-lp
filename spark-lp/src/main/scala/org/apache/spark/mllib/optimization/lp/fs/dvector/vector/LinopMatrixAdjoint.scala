package org.apache.spark.mllib.optimization.lp.fs.dvector.vector

import org.apache.spark.mllib.optimization.lp.CheckedIteratorFunctions._
import org.apache.spark.mllib.optimization.lp.VectorSpace._
import org.apache.spark.mllib.linalg.{BLAS, DenseVector, Vectors}
import org.apache.spark.mllib.optimization.lp.LinearOperator
import org.apache.spark.storage.StorageLevel

/**
  * Compute the adjoin product of a DMatrix with a DVector to produce a Vector.
  * The implementation multiplies each row of 'matrix' by the corresponding value of the column
  * vector 'x' and sums the scaled vectors thus obtained.
  *
  * @param matrix a DMatrix to compute the adjoin product.
  * @param depth  to control the depth in treeAggregate.
  */
class LinopMatrixAdjoint(@transient private val matrix: DMatrix, val depth: Int = 2)
  extends LinearOperator[DVector, DenseVector] with Serializable {

  if (matrix.getStorageLevel == StorageLevel.NONE) {
    matrix.cache()
  }

  private lazy val n = matrix.first().size

  /**
    * Apply the multiplication.
    *
    * @param x The vector on which to apply the operator.
    * @return The result of applying the operator on x.
    */
  override def apply(x: DVector): DenseVector = {
    val n = this.n
    matrix.zipPartitions(x)((matrixPartition, xPartition) =>
      Iterator.single(
        matrixPartition.checkedZip(xPartition.next.values.toIterator).aggregate(
          // NOTE A DenseVector result is assumed here (not sparse safe).
          Vectors.zeros(n).toDense)(
          seqop = (_, _) match {
            case (sum, (matrix_i, x_i)) => {
              // Multiply an element of x by its corresponding matrix row, and add to the
              // accumulation sum vector.
              BLAS.axpy(x_i, matrix_i, sum)
              sum
            }
          },
          combop = (sum1, sum2) => {
            // Add the intermediate sum vectors.
            BLAS.axpy(1.0, sum2, sum1)
            sum1
          }
        ))
    ).treeAggregate(Vectors.zeros(n).toDense)(
      seqOp = (sum1, sum2) => {
        // Add the intermediate sum vectors.
        BLAS.axpy(1.0, sum2, sum1)
        sum1
      },
      combOp = (sum1, sum2) => {
        // Add the intermediate sum vectors.
        BLAS.axpy(1.0, sum2, sum1)
        sum1
      }
      , depth
    )
  }
}
