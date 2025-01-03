package org.apache.spark.mllib.optimization.lp

import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.apache.spark.mllib.optimization.lp.util.TestingUtils._
import org.apache.spark.mllib.linalg
import org.apache.spark.mllib.optimization.lp.util.MLlibTestSparkContext
import org.apache.spark.mllib.optimization.lp.vs.dvector.DVectorSpace
import org.apache.spark.mllib.optimization.lp.vs.vector.DenseVectorSpace
import org.apache.spark.rdd.RDD
import org.scalatest.funsuite.AnyFunSuite

class LPSuite extends AnyFunSuite with MLlibTestSparkContext {

  val numPartitions = 2
  val cArray: Array[Double] = Array(2.0, 1.5, 0.0, 0.0, 0.0, 0.0, 0.0)
  val BArray: Array[Array[Double]] = Array(
    Array(12.0, 16.0, 30.0, 1.0, 0.0),
    Array(24.0, 16.0, 12.0, 0.0, 1.0),
    Array(-1.0, 0.0, 0.0, 0.0, 0.0),
    Array(0.0, -1.0, 0.0, 0.0, 0.0),
    Array(0.0, 0.0, -1.0, 0.0, 0.0),
    Array(0.0, 0.0, 0.0, 1.0, 0.0),
    Array(0.0, 0.0, 0.0, 0.0, 1.0))
  val bArray: Array[Double] = Array(120.0, 120.0, 120.0, 15.0, 15.0)

  lazy val c: RDD[DenseVector] = sc.parallelize(cArray, numPartitions).glom.map(new DenseVector(_))
  lazy val rows: RDD[linalg.Vector] = sc.parallelize(BArray, numPartitions).map(Vectors.dense)
  lazy val b = new DenseVector(bArray)

  test("LP solve is implemented properly") {
    val (v, x) = LP.solve(c, rows, b, sc = sc)
    // solution obtained from scipy.optimize.linprog and octave glgk lpsolver with fun_val = 12.083
    val expectedSol = Vectors.dense(
      Array(1.66666667, 5.83333333, 40.0, 0.0, 0.0, 13.33333333, 9.16666667))
    val xx = Vectors.dense(x.flatMap(_.toArray).collect())
    println(s"$xx")
    println("optimal min value: " + v)
    assert(xx ~== expectedSol absTol 1e-6, "LP.solve x should return the correct answer.")
  }
}