package org.apache.spark.wrappers

import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.mllib.linalg.{BLAS => IBLAS}

/**
  * Methods for calling BLAS routines.
  * Internally, it calls the BLAS routines in `mllib.linalg.BLAS` which are private.
  */
object BLAS {

  /**
    * Adds alpha * v * v.t to a matrix in-place. This is the same as BLAS's ?SPR.
    *
    * @param U the upper triangular part of the matrix packed in an array (column major)
    */
  def spr(alpha: Double, v: Vector, U: Array[Double]): Unit = {
    IBLAS.spr(alpha, v, U)
  }

  /**
    * x = a * x
    */
  def scal(a: Double, x: Vector): Unit = {
    IBLAS.scal(a, x)
  }

  /**
    * y += a * x
    */
  def axpy(a: Double, x: Vector, y: Vector): Unit = {
    IBLAS.axpy(a, x, y)
  }

  /**
    * dot(x, y)
    */
  def dot(x: Vector, y: Vector): Double = {
    IBLAS.dot(x, y)
  }
}
