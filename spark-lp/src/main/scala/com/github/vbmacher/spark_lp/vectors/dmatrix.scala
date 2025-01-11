package com.github.vbmacher.spark_lp.vectors

import breeze.linalg.{DenseVector => BDV}
import com.github.vbmacher.spark_lp.collections.implicits.IteratorOps
import com.typesafe.scalalogging.LazyLogging
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.wrappers.BLAS
import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.apache.spark.storage.StorageLevel

object dmatrix {

  object implicits {

    implicit class DMatrixOps(matrix: DMatrix) extends LazyLogging {

      if (matrix.getStorageLevel == StorageLevel.NONE) {
        matrix.cache()
      }

      private lazy val columns = matrix.first().size

      /**
        * Transposed Matrix
        */
      lazy val t: DMatrix = {
        // Convert each vector (row) into an indexed sequence of tuples (colIndex, value)
        val indexedRows = matrix.zipWithIndex.flatMap {
          case (vector, rowIndex) => vector.toArray.zipWithIndex.map {
            case (value, colIndex) => (colIndex, (rowIndex, value))
          }
        }

        // Group by column index and sort by row index to form the transposed rows
        val transposed = indexedRows.groupByKey().sortByKey().map {
          case (_, rowValues) =>
            Vectors.dense(rowValues.toSeq.sortBy(_._1).map(_._2).toArray)
        }

        transposed
      }

      /**
        * Computes the Gramian matrix `A^T A`. Note that this cannot be computed on matrices with more than 65535 columns.
        *
        * A Gramian matrix is a symmetric positive semi-definite matrix. It is symmetric because A^T A is symmetric,
        * and positive semi-definite because for any vector x, the dot product x^T (A^T A) x = (Ax)^T (Ax) >= 0.
        *
        * It is positive definite if the columns of A are linearly independent.
        *
        * @param ncol  number of columns
        * @param depth to control the depth in treeAggregate. Higher number, more stages in Spark - does not have impact on result.
        */
      def gramianMatrix(ncol: Int, depth: Int = 2): BDV[Double] = {

        checkNumColumns(ncol)
        // Computes n*(n+1)/2, avoiding overflow in the multiplication.
        // This succeeds when n <= 65535, which is checked above
        val nt =
          if (ncol % 2 == 0) (ncol / 2) * (ncol + 1)
          else ncol * ((ncol + 1) / 2)

        // Compute the upper triangular part of the gram matrix.
        val GU = matrix.treeAggregate(new BDV[Double](nt))(
          seqOp = (U, v) => {
            BLAS.spr(1.0, v, U.data)
            //NativeBLAS.dspr("U", ncol, 1.0, v, 1, U) //symmetric rk 1 update included in BLAS netlib-java
            U
          }, combOp = (U1, U2) => U1 += U2, depth)
        GU // column major == BLAS packed columnwise format
      }

      /**
        * Compute the adjoint product of this DMatrix with a DVector to produce a Vector.
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
        * @param x     The vector on which to apply the multiplication.
        * @param depth to control the depth in treeAggregate. Higher number, more stages in Spark - does not have impact on result.
        * @return result of multiplying this DMatrix with DVector
        */
      def adjointProduct(x: DVector, depth: Int = 2): DenseVector = {
        val n = columns
        matrix.zipPartitions(x)((matrixPartition, xPartition) =>
          Iterator.single(
            matrixPartition
              .checkedZip(xPartition.next.values.toIterator) // ignoring more rows
              .aggregate(Vectors.zeros(n).toDense)( // NOTE A DenseVector result is assumed here (not sparse safe).
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

      /**
        * Compute the product of a DMatrix with a Vector to produce a DVector.
        *
        * @param x The vector on which to apply the multiplication.
        * @return The result of the multiplication
        */
      def product(x: Broadcast[DenseVector]): DVector = {
        // Take the dot product of each matrix row with x.
        // NOTE A DenseVector result is assumed here (not sparse safe).
        matrix.mapPartitions(partitionRows =>
          Iterator.single(new DenseVector(partitionRows.map(row => BLAS.dot(row, x.value)).toArray)))
      }

      /**
        * Compute the product of a DMatrix with a Vector to produce a DVector.
        *
        * @param x The vector on which to apply the multiplication.
        * @return The result of the multiplication
        */
      def product(x: DenseVector): DVector = {
        // Take the dot product of each matrix row with x.
        // NOTE A DenseVector result is assumed here (not sparse safe).
        val brX = matrix.sparkContext.broadcast(x)
        matrix.mapPartitions(partitionRows =>
          Iterator.single(new DenseVector(partitionRows.map(row => BLAS.dot(row, brX.value)).toArray)))
      }

      /**
        * Check if the number of columns exceed 65535 to avoid Array overflow
        *
        * @param cols The number of columns
        */
      private def checkNumColumns(cols: Int): Unit = {
        if (cols > 65535) {
          throw new IllegalArgumentException(s"Argument with more than 65535 cols: $cols")
        }
        if (cols > 10000) {
          val memMB = (cols.toLong * cols) / 125000
          logger.warn(s"$cols columns will require at least $memMB megabytes of memory!")
        }
      }
    }
  }
}
