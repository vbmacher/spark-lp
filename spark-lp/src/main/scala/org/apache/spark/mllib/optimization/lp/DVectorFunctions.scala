package org.apache.spark.mllib.optimization.lp

import org.apache.spark.mllib.linalg.{BLAS, DenseVector}
import org.apache.spark.mllib.optimization.lp.VectorSpace.DVector

import scala.language.implicitConversions

/**
  * Extra functions available on DVectors through an implicit conversion. DVectors are represented
  * using RDD[DenseVector], and these helper functions apply operations to the values within each
  * DenseVector of the RDD.
  */
private[lp] class DVectorFunctions(self: DVector) {

  /** Apply a function to each DVector element. */
  def mapElements(f: Double => Double): DVector =
    self.map(part => new DenseVector(part.values.map(f)))

  /**
    * Zip a DVector's elements with those of another DVector and apply a function to each pair of
    * elements.
    */
  def zipElements(other: DVector, f: (Double, Double) => Double): DVector =
    self.zip(other).map {
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

  /** Apply aggregation functions to the DVector elements. */
  def aggregateElements(zeroValue: Double)(
    seqOp: (Double, Double) => Double,
    combOp: (Double, Double) => Double): Double =
    self.aggregate(zeroValue)(
      seqOp = (aggregate, part) => {
        // NOTE DenseVectors are assumed here (not sparse safe).
        val partAggregate = part.values.aggregate(zeroValue)(seqop = seqOp, combop = combOp)
        combOp(partAggregate, aggregate)
      },
      combOp = combOp)

  /** Collect the DVector elements to a local array. */
  def collectElements: Array[Double] =
    // NOTE DenseVectors are assumed here (not sparse safe).
    self.collect().flatMap(_.values)

  /** Compute the elementwise difference of this DVector with another. */
  def diff(other: DVector): DVector =
    self.zip(other).map {
      case (selfPart, otherPart) =>
        val ret = selfPart.copy
        BLAS.axpy(-1.0, otherPart, ret)
        ret
    }

  /** Sum the DVector's elements. */
  def sum(depth: Int = 2): Double = self.treeAggregate(0.0)(
    (sum, x) => sum + x.values.sum, _ + _, depth)

  /** Compute the dot product with another DVector. */
  def dot(other: DVector, depth: Int = 2): Double =
    self.zip(other).treeAggregate(0.0)((sum, x) => sum + BLAS.dot(x._1, x._2), _ + _, depth)
}

private[lp] object DVectorFunctions {

  implicit def DVectorToDVectorFunctions(dVector: DVector): DVectorFunctions =
    new DVectorFunctions(dVector)
}
