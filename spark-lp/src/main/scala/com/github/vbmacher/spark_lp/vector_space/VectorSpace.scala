package com.github.vbmacher.spark_lp.vector_space

/**
  * A vector space trait with support for computing linear combinations and inner products.
  *
  * @tparam X A type representing a vector in the vector space.
  */
trait VectorSpace[X] {

  /**
    * Compute a linear combination of two vectors alpha * a + beta * b.
    *
    * @param alpha The first scalar coefficient.
    * @param a The first vector.
    * @param beta The second scalar coefficient.
    * @param b The second vector.
    *
    * @return The computed linear combination.
    */
  def combine(alpha: Double, a: X, beta: Double, b: X): X

  /**
    * Compute the inner product of two vectors.
    *
    * @param a The first vector.
    * @param b The second vector.
    *
    * @return The computed inner product.
    */
  def dot(a: X, b: X): Double

  /**
    * Compute the entrywise product (Hadamard product) of two vectors.
    *
    * @param a The first vector.
    * @param b The second vector.
    * @return The computed vector.
    */
  def entrywiseProd(a: X, b: X): X

  /**
    * Compute the entrywise division on negative values of two vectors.
    *
    * @param a The first vector.
    * @param b The second vector.
    * @return The computed vector.
    */
  def entrywiseNegDiv(a: X, b: X): X

  /**
    * Compute the sum of the elements of a vector.
    *
    * @param a The input vector.
    * @return The computed sum.
    */
  def sum(a: X): Double

  /**
    * Compute the max element of a vector.
    *
    * @param a The input vector.
    * @return The computed maximum value.
    */
  def max(a: X): Double

  /**
    * Compute the min element of a vector.
    *
    * @param a The input vector.
    * @return The computed minimum value.
    */
  def min(a: X): Double

  /**
    * Cache a vector for efficient access later.
    *
    * @param a The vector to cache.
    */
  def cache(a: X): Unit = {}
}

object VectorSpace {

  def apply[V: VectorSpace]: VectorSpace[V] = implicitly[VectorSpace[V]]
}
