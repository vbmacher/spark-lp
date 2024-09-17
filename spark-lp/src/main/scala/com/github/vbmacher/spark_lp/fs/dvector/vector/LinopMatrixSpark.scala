package com.github.vbmacher.spark_lp.fs.dvector.vector

import com.github.vbmacher.spark_lp.CheckedIteratorFunctions._
import com.github.vbmacher.spark_lp.LinearOperator
import com.github.vbmacher.spark_lp.VectorSpace._
import org.apache.spark.internal_access.BLAS
import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.apache.spark.storage.StorageLevel

/**
  * Compute the adjoint product of a DMatrix with a DVector to produce a Vector.
  * The implementation multiplies each row of 'matrix' by the corresponding value of the column
  * vector 'x' and sums the scaled vectors thus obtained.
  *
  * NOTE In order to multiply the transpose of a DMatrix 'm' by a DVector 'v', m and v must be
  * consistently partitioned. Each partition of m must contain the same number of rows as there
  * are vector elements in the corresponding partition of v. For example, if m contains two
  * partitions and there are two row Vectors in the first partition and three row Vectors in the
  * second partition, then v must have two partitions with a single Vector containing two elements
  * in its first partition and a single Vector containing three elements in its second partition.
  *
  * @param matrix a DMatrix to compute the adjoint product.
  * @param depth  to control the depth in treeAggregate. Higher number, more stages in Spark - does not have impact on result.
  */
class LinopMatrixSpark(@transient private val matrix: DMatrix, val depth: Int = 2)
  extends LinearOperator[DVector, DenseVector] with Serializable {

  if (matrix.getStorageLevel == StorageLevel.NONE) {
    matrix.cache()
  }

  private lazy val n = matrix.first().size // columns

  /**
    * Apply the multiplication.
    *
    * A_T * x:
    *
    * A = [ a b      x = [ x      A_T = [ a c
    *       c d ]          y ]            b d ]
    *
    * Normal multiplication = [ a x + b y
    *                           c x + d y ]
    *
    *
    * Adjoint multiplication = [ a x + c y
    *                            b x + d y ]   <---- this is what this function does
    *
    * @param x The vector on which to apply the operator.
    * @return The result of applying the operator on x.
    */
  override def apply(x: DVector): DenseVector = {
    val n = this.n
    matrix.zipPartitions(x)((matrixPartition, xPartition) =>
      Iterator.single(
        matrixPartition
          .checkedZip(xPartition.next.values.toIterator)
          .aggregate(Vectors.zeros(n).toDense)(   // NOTE A DenseVector result is assumed here (not sparse safe).
            seqop = {
              case (sum, (matrix_i, x_i)) =>
                // Multiply an element of x by its corresponding matrix row, and add to the accumulation sum vector.
                BLAS.axpy(x_i, matrix_i, sum)
                sum
            },
            combop = (sum1, sum2) => {
              // Add the intermediate sum vectors.
              BLAS.axpy(1.0, sum2, sum1)
              sum1
            }
          ))
    ).treeAggregate(Vectors.zeros(n).toDense)(
      seqOp = (sum1, sum2) => {
        // Add the intermediate sum vectors.  <=== will be always just 1 vector (we created 1 vector per partition above)
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
