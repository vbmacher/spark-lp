package org.apache.spark.wrappers

import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.mllib.linalg.{BLAS => IBLAS}

/** Exposes the Spark MLlib BLAS operations used by spark-lp. */
object BLAS {

  /**
    * Adds `alpha * v * v^T` to a symmetric matrix in place.
    *
    * @param U packed upper triangle in column-major BLAS order.
    */
  def spr(alpha: Double, v: Vector, U: Array[Double]): Unit = {
    IBLAS.spr(alpha, v, U)
  }

  /** Replaces `x` with `a * x`. */
  def scal(a: Double, x: Vector): Unit = {
    IBLAS.scal(a, x)
  }

  /** Replaces `y` with `y + a * x`. */
  def axpy(a: Double, x: Vector, y: Vector): Unit = {
    IBLAS.axpy(a, x, y)
  }

  /** Returns the inner product `x^T y`. */
  def dot(x: Vector, y: Vector): Double = {
    IBLAS.dot(x, y)
  }
}
