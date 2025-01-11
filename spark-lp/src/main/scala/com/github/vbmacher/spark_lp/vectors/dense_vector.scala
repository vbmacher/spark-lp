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
        * Compute a linear combination of two vectors alpha * this + beta * b.
        *
        * @param alpha The first scalar coefficient.
        * @param beta  The second scalar coefficient.
        * @param b     The second vector.
        * @return The computed linear combination.
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


      /**
        * Compute the inner product of two vectors this * b.
        *
        * @param b The second vector.
        * @return The computed inner product.
        */
      def dot(b: DenseVector): Double = BLAS.dot(vector, b)

      /**
        * Compute the entrywise product (Hadamard product) of two vectors.
        *
        * @param b The second vector.
        * @return The computed vector.
        */
      def entrywiseProd(b: DenseVector): DenseVector = {
        val c = vector.values.zip(b.values).map { case (i: Double, j: Double) => i * j }
        new DenseVector(c)
      }

      /**
        * Compute the entrywise division on negative values of two vectors.
        *
        * @param b The second vector.
        * @return The computed vector.
        */
      def entrywiseNegDiv(b: DenseVector): DenseVector = {
        val c = vector.values.zip(b.values).map {
          case (ai, bi) if bi < 0 => ai / Math.max(Math.abs(bi), 1e-15)
          case (_, bi) if bi >= 0 => Double.PositiveInfinity // Make Infinity value to be neglected in min
        }
        new DenseVector(c)
      }
    }
  }
}
