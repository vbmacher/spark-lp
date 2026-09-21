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
        vector.aggregate(Double.PositiveInfinity)((mi, x) => x.values.foldLeft(mi)(Math.min), Math.min)
      }

      lazy val maxValue: Double = {
        vector.aggregate(Double.NegativeInfinity)((ma, x) => x.values.foldLeft(ma)(Math.max), Math.max)
      }

      /** Applies `f` to every element while preserving the partition layout. */
      def mapElements(f: Double => Double): DVector =
        vector.map(part => new DenseVector(part.values.map(f)))

      /**
        * Applies `f` to elements at the same position in two partition-aligned vectors.
        *
        * An `IllegalArgumentException` is thrown if corresponding partitions contain different
        * numbers of elements.
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

      /** Reduces all elements with `seqOp`, then combines partition results with `combOp`. */
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

      /** Collects all elements to the driver in partition order. */
      def collectElements: Array[Double] = {
        // NOTE DenseVectors are assumed here (not sparse safe).
        vector.collect().flatMap(_.values)
      }

      /** Returns `vector - other` for two partition-aligned vectors. */
      def diff(other: DVector): DVector = {
        vector.zip(other).map {
          case (selfPart, otherPart) =>
            val ret = selfPart.copy
            BLAS.axpy(-1.0, otherPart, ret)
            ret
        }
      }

      /** Returns the sum of all distributed elements. */
      def sum(depth: Int = 2): Double = {
        vector.treeAggregate(0.0)((sum, x) => sum + x.values.sum, _ + _, depth)
      }

      /**
        * Returns the inner product `vector^T b` for two partition-aligned vectors.
        *
        * @param depth depth of Spark's tree aggregation.
        */
      def dot(b: DVector, depth: Int = 2): Double = {
        vector.zip(b).treeAggregate(0.0)((sum, x) => sum + BLAS.dot(x._1, x._2), _ + _, depth)
      }

      /**
        * Returns `diag(vector) * mat` by scaling each matrix row by the corresponding vector value.
        *
        * Each matrix partition must contain as many rows as the local dense vector in the
        * corresponding vector partition contains elements.
        *
        * @param mat matrix whose rows are scaled.
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
        * Returns `alpha * vector + beta * b` for two partition-aligned vectors.
        *
        * @param alpha multiplier for this vector.
        * @param beta multiplier for `b`.
        * @param b vector added to this vector after scaling.
        */
      def combine(alpha: Double, beta: Double, b: DVector): DVector = {
        if (alpha == 1.0 && beta == 1.0) {
          vector.zip(b).map {
            case (aPart, bPart) =>
              val result = bPart.copy
              BLAS.axpy(1.0, aPart, result)
              result
          }
        } else {
          vector.zip(b).map {
            case (aPart, bPart) =>
              // NOTE A DenseVector result is assumed here (not sparse safe).
              aPart.combine(alpha, beta, bPart).toDense
          }
        }
      }

      /** Returns the elementwise product with the partition-aligned vector `b`. */
      def entrywiseProd(b: DVector): DVector = {
        vector.zip(b).map { case (aPart, bPart) => aPart.entrywiseProd(bPart).toDense }
      }

      /**
        * Applies [[dense_vector.implicits.DenseVectorOps.entrywiseNegDiv]] to corresponding
        * partitions of this vector and `b`.
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
