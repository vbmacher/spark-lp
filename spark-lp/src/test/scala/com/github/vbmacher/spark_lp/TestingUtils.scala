package com.github.vbmacher.spark_lp

import org.apache.spark.mllib.linalg.Vector
import org.scalatest.exceptions.TestFailedException

/**
 * Testing utilities excerpted from the spark testing library.
 * @see [[https://github.com/apache/spark/blob/master/mllib/src/test/scala/org/apache/spark/mllib/util/TestingUtils.scala]]
 */
object TestingUtils {

  val ABS_TOL_MSG = " using absolute tolerance"
  val REL_TOL_MSG = " using relative tolerance"

  /**
   * Private helper function for comparing two values using relative tolerance.
   * Note that if x or y is extremely close to zero, i.e., smaller than Double.MinPositiveValue,
   * the relative tolerance is meaningless, so the exception will be raised to warn users.
   */
  private def RelativeErrorComparison(x: Double, y: Double, eps: Double): Boolean = {
    val absX = math.abs(x)
    val absY = math.abs(y)
    val diff = math.abs(x - y)
    if (x == y) {
      true
    } else if (absX < Double.MinPositiveValue || absY < Double.MinPositiveValue) {
      throw new TestFailedException(
        s"$x or $y is extremely close to zero, so the relative tolerance is meaningless.", 0)
    } else {
      diff < eps * math.min(absX, absY)
    }
  }

  /**
   * Private helper function for comparing two values using absolute tolerance.
   */
  private def AbsoluteErrorComparison(x: Double, y: Double, eps: Double): Boolean = {
    math.abs(x - y) < eps
  }

  case class CompareDoubleRightSide(fun: (Double, Double, Double) => Boolean, y: Double, eps: Double, method: String)

  /**
   * Implicit class for comparing two double values using relative tolerance or absolute tolerance.
   */
  implicit class DoubleWithAlmostEquals(val x: Double) {

    /**
     * When the difference of two values are within eps, returns true; otherwise, returns false.
     */
    def ~=(r: CompareDoubleRightSide): Boolean = r.fun(x, r.y, r.eps)

    /**
     * When the difference of two values are within eps, returns false; otherwise, returns true.
     */
    def !~=(r: CompareDoubleRightSide): Boolean = !r.fun(x, r.y, r.eps)

    /**
     * Throws exception when the difference of two values are NOT within eps;
     * otherwise, returns true.
     */
    def ~==(r: CompareDoubleRightSide): Boolean = {
      if (!r.fun(x, r.y, r.eps)) {
        throw new TestFailedException(
          s"Expected $x and ${r.y} to be within ${r.eps}${r.method}.", 0)
      }
      true
    }

    /**
     * Throws exception when the difference of two values are within eps; otherwise, returns true.
     */
    def !~==(r: CompareDoubleRightSide): Boolean = {
      if (r.fun(x, r.y, r.eps)) {
        throw new TestFailedException(
          s"Did not expect $x and ${r.y} to be within ${r.eps}${r.method}.", 0)
      }
      true
    }

    /**
     * Comparison using absolute tolerance.
     */
    def absTol(eps: Double): CompareDoubleRightSide =
      CompareDoubleRightSide(AbsoluteErrorComparison, x, eps, ABS_TOL_MSG)

    /**
     * Comparison using relative tolerance.
     */
    def relTol(eps: Double): CompareDoubleRightSide =
      CompareDoubleRightSide(RelativeErrorComparison, x, eps, REL_TOL_MSG)

    override def toString: String = x.toString
  }

  case class CompareVectorRightSide(fun: (Vector, Vector, Double) => Boolean,
                                    y: Vector, eps: Double, method: String)

  /**
   * Implicit class for comparing two vectors using relative tolerance or absolute tolerance.
   */
  implicit class VectorWithAlmostEquals(val x: Vector) {

    /**
     * When the difference of two vectors are within eps, returns true; otherwise, returns false.
     */
    def ~=(r: CompareVectorRightSide): Boolean = r.fun(x, r.y, r.eps)

    /**
     * When the difference of two vectors are within eps, returns false; otherwise, returns true.
     */
    def !~=(r: CompareVectorRightSide): Boolean = !r.fun(x, r.y, r.eps)

    /**
     * Throws exception when the difference of two vectors are NOT within eps;
     * otherwise, returns true.
     */
    def ~==(r: CompareVectorRightSide): Boolean = {
      if (!r.fun(x, r.y, r.eps)) {
        throw new TestFailedException(
          s"Expected $x and ${r.y} to be within ${r.eps}${r.method} for all elements.", 0)
      }
      true
    }

    /**
     * Throws exception when the difference of two vectors are within eps; otherwise, returns true.
     */
    def !~==(r: CompareVectorRightSide): Boolean = {
      if (r.fun(x, r.y, r.eps)) {
        throw new TestFailedException(
          s"Did not expect $x and ${r.y} to be within ${r.eps}${r.method} for all elements.", 0)
      }
      true
    }

    /**
     * Comparison using absolute tolerance.
     */
    def absTol(eps: Double): CompareVectorRightSide = CompareVectorRightSide(
      (x: Vector, y: Vector, eps: Double) => {
        x.toArray.zip(y.toArray).forall(xy => xy._1 ~= xy._2 absTol eps)
      }, x, eps, ABS_TOL_MSG)

    /**
     * Comparison using relative tolerance. Note that comparing against sparse vector
     * with elements having value of zero will raise exception because it involves with
     * comparing against zero.
     */
    def relTol(eps: Double): CompareVectorRightSide = CompareVectorRightSide(
      (x: Vector, y: Vector, eps: Double) => {
        x.toArray.zip(y.toArray).forall(xy => xy._1 ~= xy._2 relTol eps)
      }, x, eps, REL_TOL_MSG)

    override def toString: String = x.toString
  }
}
