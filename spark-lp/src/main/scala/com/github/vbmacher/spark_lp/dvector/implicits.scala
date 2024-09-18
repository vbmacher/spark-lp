package com.github.vbmacher.spark_lp.dvector

import com.github.vbmacher.spark_lp.collections.implicits._
import com.github.vbmacher.spark_lp.dmatrix.DMatrix
import org.apache.spark.internal_access.BLAS
import org.apache.spark.mllib.linalg.{DenseVector, Vector}

object implicits {

  implicit class DVectorOps(vector: DVector) {

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
      * Compute the dot product with another DVector.
      */
    def dot(other: DVector, depth: Int = 2): Double = {
      vector.zip(other).treeAggregate(0.0)((sum, x) => sum + BLAS.dot(x._1, x._2), _ + _, depth)
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
        vectorPartition.next().values.toIterator.checkedZip(matPartition).map {
          case (a: Double, x: Vector) =>
            val xc = x.copy
            BLAS.scal(a, xc)
            xc
        }
      )
    }
  }
}
