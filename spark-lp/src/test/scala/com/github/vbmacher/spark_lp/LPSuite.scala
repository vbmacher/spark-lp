package com.github.vbmacher.spark_lp

import TestingUtils._
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.scalatest.funsuite.AnyFunSuite

class LPSuite extends AnyFunSuite with DataFrameSuiteBase {

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
  lazy val rows: RDD[org.apache.spark.mllib.linalg.Vector] = sc.parallelize(BArray, numPartitions).map(Vectors.dense)
  lazy val b = new DenseVector(bArray)

  test("LP solve is implemented properly") {
    implicit val s: SparkSession = spark

    val (v, x) = LP.solve(c, rows, b)
    // solution obtained from scipy.optimize.linprog and octave glgk lpsolver with fun_val = 12.083
    val expectedSol = Vectors.dense(
      Array(1.66666667, 5.83333333, 40.0, 0.0, 0.0, 13.33333333, 9.16666667))
    val xx = Vectors.dense(x.flatMap(_.toArray).collect())
    println(s"$xx")
    println("optimal min value: " + v)
    assert(xx ~== expectedSol absTol 1e-6, "LP.solve x should return the correct answer.")
  }

  test("primal infeasible: conflicting constraints terminate with a Farkas certificate") {
    implicit val s: SparkSession = spark
    // x <= 1 and x >= 2 in equality form: x + s1 = 1, x - s2 = 2 (distinct rows, full row rank)
    val cInf = sc.parallelize(Array(1.0, 0.0, 0.0), numPartitions).glom.map(new DenseVector(_))
    val atInf = sc.parallelize(Seq(
      Vectors.dense(1.0, 1.0), Vectors.dense(1.0, 0.0), Vectors.dense(0.0, -1.0)), numPartitions)
    val bInf = new DenseVector(Array(1.0, 2.0))

    val summary = LP.solveSummary(cInf, atInf, bInf)
    assert(summary.termination == LP.Termination.PrimalInfeasible)
    assert(summary.certificateResidual <= 1e-8)

    // the normalized ray y satisfies its Farkas inequalities: b^T y = 1, A^T y <= eps componentwise
    val y = summary.primalCertificate.get.toArray
    assert(math.abs(1.0 * y(0) + 2.0 * y(1) - 1.0) < 1e-9, "certificate is normalized to b^T y = 1")
    val aTy = Seq(y(0) + y(1), y(0), -y(1)) // columns x, s1, s2
    aTy.foreach(v => assert(v <= 1e-8, s"A^T y must be <= tolerance componentwise, got $aTy"))
  }

  test("dual infeasible (unbounded): diverging primal iterate terminates with a Farkas certificate") {
    implicit val s: SparkSession = spark
    // minimize -x1 subject to x1 - x2 = 1: feasible at (1, 0), unbounded along the ray (1, 1)
    val cUnb = sc.parallelize(Array(-1.0, 0.0), numPartitions).glom.map(new DenseVector(_))
    val atUnb = sc.parallelize(Seq(Vectors.dense(1.0), Vectors.dense(-1.0)), numPartitions)
    val bUnb = new DenseVector(Array(1.0))

    val summary = LP.solveSummary(cUnb, atUnb, bUnb)
    assert(summary.termination == LP.Termination.DualInfeasible)
    assert(summary.certificateResidual <= 1e-8)
    assert(summary.primalResidual < 1e-4, "the iterate stays near-feasible while diverging along the ray")

    // the normalized ray z satisfies its Farkas conditions: z >= 0, c^T z = -1, ||A z||_inf <= eps
    val z = summary.dualCertificate.get.flatMap(_.toArray).collect()
    assert(z.forall(_ >= 0.0), "certificate ray must be nonnegative")
    assert(math.abs(-z(0) + 1.0) < 1e-9, "certificate is normalized to c^T z = -1")
    assert(math.abs(z(0) - z(1)) <= 1e-8, "||A z||_inf must be <= tolerance")
  }
}