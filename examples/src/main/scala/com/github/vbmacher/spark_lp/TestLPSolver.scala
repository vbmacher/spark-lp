package com.github.vbmacher.spark_lp

import com.github.vbmacher.spark_lp.VectorSpace.{DMatrix, DVector}
import com.github.vbmacher.spark_lp.vs.dvector.DVectorSpace
import com.github.vbmacher.spark_lp.vs.vector.DenseVectorSpace
import org.apache.spark.internal_access.{BLAS, XORShiftRandom}
import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.apache.spark.mllib.random.RandomDataGenerator
import org.apache.spark.{SparkConf, SparkContext}

import scala.util.Random

/**
  * Helper class for generating sparse standard normal values.
  *
  * @param density The density of non-sparse values.
  */
private class SparseStandardNormalGenerator(density: Double) extends RandomDataGenerator[Double] {

  private val random = XORShiftRandom()

  override def nextValue(): Double = if (random.nextDouble < density) random.nextGaussian else 0.0

  override def setSeed(seed: Long): Unit = random.setSeed(seed)

  override def copy(): SparseStandardNormalGenerator = new SparseStandardNormalGenerator(density)
}

/**
  * This example generates a random linear programming problem and solves it using LP.solve
  *
  * The example can be executed as follows:
  * sbt 'test:run-main org.apache.spark.mllib.optimization.lp.examples.TestLPSolver'
  */
object TestLPSolver {

  def main(args: Array[String]): Unit = {
    val sparkConf = new SparkConf().setMaster("local[2]").setAppName("TestLPSolver")
    val sc = new SparkContext(sparkConf)


    sc.setLogLevel("ERROR")

    // minimize cT x
    //  Z = c1*x1 + c2*x2 + ... + cN*xN
    //  c - coefficients
    //  x - variables  <--- this I want
    //
    // subject to Ax <= b, x >= 0

    // For example, minimize function:
    // Z = 4*x1 + 3*x2

    // Constraints:
    // x1 + x2 > 5   ,  or -(x1 + x2) ≤ -5
    // x1      ≤ 3
    // x1, x2  ≥ 0

    // Transformed to inputs:

    // Vector c = [4, 3]
    // Matrix A (for Ax <= b):
    // A= [
    //      -1, -1
    //      1, 0
    //    ]
    // Because the rows should be linearly independent vectors!

    // Vector b = [-5, 3]

    // Answer:
    // x1 = 4, x2 = 1
    // Min value: Z = 3*4 + 5*1 = 12 + 5 = 17.

    // Ax =b
    // (-1) * x1 + (-1) * x2 = -3 - 2 = -5
    // (1) * x1 + (0) * x2 = 3

    val cArray = Array(4.0, 3.0)
    val AArray = Array(
      Array(-1.0, 1.0),
      Array(-1.0, 0.0)
    )
    val bArray = Array(-5.0, 3.0)

    val aRank = Util.matrixRank(AArray)
    println(s"Matrix A rank: " + aRank)

    require(aRank == AArray.length, "Requirement failed: rank(A) = m")
    require(AArray.length <= AArray(0).length, "Requirement failed: m <= n")


    val numPartitions = 2

    val c: DVector = sc.parallelize(cArray, numPartitions).glom.map(new DenseVector(_))
    val rows: DMatrix = sc.parallelize(AArray, numPartitions).map(Vectors.dense)
    val b: DenseVector = new DenseVector(bArray)


    // Solve the linear program using LP.solve, finding the optimal x vector 'optimalX'.
    println("Start solving ...")
    val (optimalVal, optimalX) = LP.solve(c, rows, b, sc = sc)
    println("optimalVal: " + optimalVal)
    println("optimalX: " + optimalX.collect().mkString(", "))

    sc.stop()
  }
}
