package com.github.vbmacher.spark_lp.vectors

import breeze.linalg.{DenseVector => BDV}
import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.wrappers.BLAS

object dense_vector {

  object implicits {

    implicit class DenseVectorOps(vector: DenseVector) {

      lazy val minValue: Double = vector.values.min

      lazy val maxValue: Double = vector.values.max

      lazy val sum: Double = vector.values.sum

      lazy val toBreeze: BDV[Double] = new BDV[Double](vector.values)

      /**
        * Returns `alpha * vector + beta * b` without modifying either input.
        *
        * @param alpha multiplier for this vector.
        * @param beta multiplier for `b`.
        * @param b vector added to this vector after scaling.
        */
      def combine(
        alpha: Double,
        beta: Double,
        b: DenseVector): DenseVector = {
        val ret = vector.copy
        BLAS.scal(alpha, ret)
        BLAS.axpy(beta, b, ret)
        ret
      }


      /** Returns the inner product `vector^T b`. */
      def dot(b: DenseVector): Double = BLAS.dot(vector, b)

      /** Returns a vector whose element `i` is `vector(i) * b(i)`. */
      def entrywiseProd(b: DenseVector): DenseVector = {
        require(vector.size == b.size, "Entrywise product requires vectors of equal size")
        val c = vector.values.zip(b.values).map { case (i: Double, j: Double) => i * j }
        new DenseVector(c)
      }

      /**
        * Divides by the negative elements of `b` and ignores non-negative elements.
        *
        * Result element `i` is `vector(i) / max(abs(b(i)), 1e-15)` when `b(i) < 0`, and positive
        * infinity otherwise. The solver uses the minimum result as a step-length bound.
        */
      def entrywiseNegDiv(b: DenseVector): DenseVector = {
        require(vector.size == b.size, "Entrywise division requires vectors of equal size")
        val c = vector.values.zip(b.values).map {
          case (ai, bi) if bi < 0 => ai / Math.max(Math.abs(bi), 1e-15)
          case (_, bi) if bi >= 0 => Double.PositiveInfinity // Make Infinity value to be neglected in min
        }
        new DenseVector(c)
      }
    }
  }
}
