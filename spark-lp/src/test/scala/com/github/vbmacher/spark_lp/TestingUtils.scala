package com.github.vbmacher.spark_lp

import org.apache.spark.mllib.linalg.Vector
import org.scalatest.exceptions.TestFailedException

/**
 * Adds scalar and vector comparison operators for test assertions with explicit absolute or
 * relative tolerances.
 *
 * @see [[https://github.com/apache/spark/blob/master/mllib/src/test/scala/org/apache/spark/mllib/util/TestingUtils.scala Spark test utility source]]
 */
object TestingUtils {

  val ABS_TOL_MSG = " using absolute tolerance"
  val REL_TOL_MSG = " using relative tolerance"

  /**
   * Returns whether two nonzero values differ by less than `eps * min(abs(x), abs(y))`.
   *
   * Exact equality succeeds, including equality at zero. A non-equal value below
   * `Double.MinPositiveValue` cannot define a meaningful relative scale and fails the test.
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

  /** Returns whether `abs(x - y)` is strictly less than `eps`. */
  private def AbsoluteErrorComparison(x: Double, y: Double, eps: Double): Boolean = {
    math.abs(x - y) < eps
  }

  /**
   * Right-hand side and policy for the test-only scalar comparison operators.
   *
   * @param fun comparison function receiving the two values and tolerance.
   * @param y expected scalar value.
   * @param eps absolute or relative comparison tolerance.
   * @param method phrase identifying the tolerance policy in assertion messages.
   */
  case class CompareDoubleRightSide(fun: (Double, Double, Double) => Boolean, y: Double, eps: Double, method: String)

  /** Adds tolerance-aware comparison and assertion operators to a scalar value. */
  implicit class DoubleWithAlmostEquals(val x: Double) {

    /** Returns whether `x` and the configured right-hand side are within tolerance. */
    def ~=(r: CompareDoubleRightSide): Boolean = r.fun(x, r.y, r.eps)

    /** Returns whether `x` and the configured right-hand side are outside tolerance. */
    def !~=(r: CompareDoubleRightSide): Boolean = !r.fun(x, r.y, r.eps)

    /** Returns true within tolerance; otherwise throws a test failure with both values. */
    def ~==(r: CompareDoubleRightSide): Boolean = {
      if (!r.fun(x, r.y, r.eps)) {
        throw new TestFailedException(
          s"Expected $x and ${r.y} to be within ${r.eps}${r.method}.", 0)
      }
      true
    }

    /** Returns true outside tolerance; otherwise throws a test failure with both values. */
    def !~==(r: CompareDoubleRightSide): Boolean = {
      if (r.fun(x, r.y, r.eps)) {
        throw new TestFailedException(
          s"Did not expect $x and ${r.y} to be within ${r.eps}${r.method}.", 0)
      }
      true
    }

    /** Builds a right-hand side that uses strict absolute error below `eps`. */
    def absTol(eps: Double): CompareDoubleRightSide =
      CompareDoubleRightSide(AbsoluteErrorComparison, x, eps, ABS_TOL_MSG)

    /** Builds a right-hand side that uses strict relative error below `eps`. */
    def relTol(eps: Double): CompareDoubleRightSide =
      CompareDoubleRightSide(RelativeErrorComparison, x, eps, REL_TOL_MSG)

    override def toString: String = x.toString
  }

  /**
   * Right-hand side and policy for the test-only vector comparison operators.
   *
   * @param fun comparison function receiving the two vectors and tolerance.
   * @param y expected vector.
   * @param eps absolute or relative comparison tolerance.
   * @param method phrase identifying the tolerance policy in assertion messages.
   */
  case class CompareVectorRightSide(fun: (Vector, Vector, Double) => Boolean,
                                    y: Vector, eps: Double, method: String)

  /** Adds elementwise tolerance-aware comparison and assertion operators to an MLlib vector. */
  implicit class VectorWithAlmostEquals(val x: Vector) {

    /** Returns whether corresponding vector elements are all within tolerance. */
    def ~=(r: CompareVectorRightSide): Boolean = r.fun(x, r.y, r.eps)

    /** Returns whether at least one pair of corresponding elements is outside tolerance. */
    def !~=(r: CompareVectorRightSide): Boolean = !r.fun(x, r.y, r.eps)

    /** Returns true when all elements match; otherwise throws a test failure. */
    def ~==(r: CompareVectorRightSide): Boolean = {
      if (!r.fun(x, r.y, r.eps)) {
        throw new TestFailedException(
          s"Expected $x and ${r.y} to be within ${r.eps}${r.method} for all elements.", 0)
      }
      true
    }

    /** Returns true when any element differs; otherwise throws a test failure. */
    def !~==(r: CompareVectorRightSide): Boolean = {
      if (r.fun(x, r.y, r.eps)) {
        throw new TestFailedException(
          s"Did not expect $x and ${r.y} to be within ${r.eps}${r.method} for all elements.", 0)
      }
      true
    }

    /** Builds a vector right-hand side using strict elementwise absolute error below `eps`. */
    def absTol(eps: Double): CompareVectorRightSide = CompareVectorRightSide(
      (x: Vector, y: Vector, eps: Double) => {
        x.toArray.zip(y.toArray).forall(xy => xy._1 ~= xy._2 absTol eps)
      }, x, eps, ABS_TOL_MSG)

    /**
     * Builds a vector right-hand side using strict elementwise relative error below `eps`.
     *
     * A non-equal zero or subnormal element has no meaningful relative scale and fails the test.
     */
    def relTol(eps: Double): CompareVectorRightSide = CompareVectorRightSide(
      (x: Vector, y: Vector, eps: Double) => {
        x.toArray.zip(y.toArray).forall(xy => xy._1 ~= xy._2 relTol eps)
      }, x, eps, REL_TOL_MSG)

    override def toString: String = x.toString
  }
}
