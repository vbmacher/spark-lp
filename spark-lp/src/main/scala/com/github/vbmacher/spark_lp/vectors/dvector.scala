package com.github.vbmacher.spark_lp.vectors

import com.github.vbmacher.spark_lp.collections.implicits.IteratorOps
import dense_vector.implicits.DenseVectorOps
import org.apache.spark.wrappers.BLAS
import org.apache.spark.mllib.linalg.{DenseVector, Vector}
import org.apache.spark.storage.StorageLevel

object dvector {

  object implicits {

    implicit class DVectorOps(vector: DVector) {

      lazy val minValue: Double = {
        vector.aggregate(Double.PositiveInfinity)((mi, x) => Math.min(mi, x.values.min), Math.min)
      }

      lazy val maxValue: Double = {
        vector.aggregate(Double.NegativeInfinity)((ma, x) => Math.max(ma, x.values.max), Math.max)
      }

      /**
        * Apply a function to each DVector element.
        */
      def mapElements(f: Double => Double): DVector =
        vector.map(part => new DenseVector(part.values.map(f)))

      /**
        * Zip a DVector's elements with those of another DVector and apply a function to each pair of
        * elements.
        */
      def zipElements(other: DVector, f: (Double, Double) => Double): DVector =
        vector.zip(other).map {
          case (selfPart, otherPart) =>
            if (selfPart.size != otherPart.size) {
              throw new IllegalArgumentException("Can only call zipElements on DVectors with the " +
                "same number of elements and consistent partitions.")
            }
            // NOTE DenseVectors are assumed here (not sparse safe).
            val ret = new Array[Double](selfPart.size)
            var i = 0
            while (i < ret.length) {
              ret(i) = f(selfPart(i), otherPart(i))
              i += 1
            }
            new DenseVector(ret)
        }

      /**
        * Apply aggregation functions to the DVector elements.
        */
      def aggregateElements(zeroValue: Double)(
        seqOp: (Double, Double) => Double,
        combOp: (Double, Double) => Double): Double =
        vector.aggregate(zeroValue)(
          seqOp = (aggregate, part) => {
            // NOTE DenseVectors are assumed here (not sparse safe).
            val partAggregate = part.values.aggregate(zeroValue)(seqop = seqOp, combop = combOp)
            combOp(partAggregate, aggregate)
          },
          combOp = combOp)

      /**
        * Collect the DVector elements to a local array.
        */
      def collectElements: Array[Double] = {
        // NOTE DenseVectors are assumed here (not sparse safe).
        vector.collect().flatMap(_.values)
      }

      /**
        * Compute the elementwise difference of this DVector with another.
        */
      def diff(other: DVector): DVector = {
        vector.zip(other).map {
          case (selfPart, otherPart) =>
            val ret = selfPart.copy
            BLAS.axpy(-1.0, otherPart, ret)
            ret
        }
      }

      /**
        * Sum the DVector's elements.
        */
      def sum(depth: Int = 2): Double = {
        vector.treeAggregate(0.0)((sum, x) => sum + x.values.sum, _ + _, depth)
      }

      /**
        * Compute the inner product of two vectors this * b.
        *
        * @param b The second vector.
        * @return The computed inner product.
        */
      def dot(b: DVector, depth: Int = 2): Double = {
        vector.zip(b).treeAggregate(0.0)((sum, x) => sum + BLAS.dot(x._1, x._2), _ + _, depth)
      }

      /**
        * Compute the product of a DVector to a DMatrix where each element of DVector is multiplied to
        * the corresponding row to produce a DMatrix. This is for optimizing the product of diagonal
        * DMatrix to a DMatrix.
        *
        * This DVector (this instance) represents diagonal matrix.
        *
        * @param mat The DMatrix for multiplication.
        * @return The result multiplication
        */
      def diagonalProduct(mat: DMatrix): DMatrix = {
        vector.zipPartitions(mat)((vectorPartition, matPartition) =>
          vectorPartition.next().values
            .toIterator
            .checkedZip(matPartition)
            .map {
              case (a: Double, x: Vector) =>
                val xc = x.copy
                BLAS.scal(a, xc)
                xc
            }
        )
      }

      /**
        * Compute a linear combination of two vectors alpha * this + beta * b.
        *
        * @param alpha The first scalar coefficient.
        * @param beta  The second scalar coefficient.
        * @param b     The second vector.
        * @return The computed linear combination.
        */
      def combine(alpha: Double, beta: Double, b: DVector): DVector = {
        if (alpha == 1.0 && beta == 1.0) {
          vector.zip(b).map {
            case (aPart, bPart) =>
              BLAS.axpy(1.0, aPart, bPart) // bPart += aPart
              bPart
          }
        } else {
          vector.zip(b).map {
            case (aPart, bPart) =>
              // NOTE A DenseVector result is assumed here (not sparse safe).
              aPart.combine(alpha, beta, bPart).toDense
          }
        }
      }

      /**
        * Compute the entrywise product (Hadamard product) of two vectors.
        *
        * @param b The second vector.
        * @return The computed vector.
        */
      def entrywiseProd(b: DVector): DVector = {
        vector.zip(b).map { case (aPart, bPart) => aPart.entrywiseProd(bPart).toDense }
      }

      /**
        * Compute the entrywise division on negative values of two vectors.
        *
        * @param b The second vector.
        * @return The computed vector.
        */
      def entrywiseNegDiv(b: DVector): DVector = {
        vector.zip(b).map { case (aPart, bPart) => aPart.entrywiseNegDiv(bPart) }
      }

      def cacheIfNoStorageLevel(): DVector = {
        if (vector.getStorageLevel == StorageLevel.NONE) {
          vector.cache()
        } else vector
      }
    }
  }
}
