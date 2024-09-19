package com.github.vbmacher.spark_lp

import com.github.vbmacher.spark_lp.linalg.breeze_ops.matrixRank
import com.github.vbmacher.spark_lp.linalg.{DMatrix, DVector}
import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.apache.spark.{SparkConf, SparkContext}

/**
  * This example generates a random linear programming problem and solves it using LP.solve
  *
  * The example can be executed as follows:
  * sbt 'test:run-main com.github.vbmacher.spark_lp.TestLPSolver'
  */
object TestLPSolver {

  def main(args: Array[String]): Unit = {
    val sparkConf = new SparkConf().setMaster("local[2]").setAppName("TestLPSolver")
    val sc = new SparkContext(sparkConf)

    sc.setLogLevel("ERROR")

    // For example, minimize function:
    // Z = 4*x1 + 3*x2

    // Constraints:
    // x1 + x2 > 5   ,  or -(x1 + x2) ≤ -5
    // x1      ≤ 3
    // x1, x2  ≥ 0

    // Pulp gives:
    // x1 = 0.0, x2 = 5.0
    // Z = 15.0



    val cArray = Array(4.0, 3.0, 0.0, 0.0)
//    val AArray = Array(
//      Array(-1.0, 1.0),
//      Array(-1.0, 0.0))

    val AArray = Array(
      Array(-1.0, 1.0),
      Array(-1.0, 0.0),
      Array(1.0, 0.0),
      Array(0.0, 1.0)
    )

    val bArray = Array(-5.0, 3.0)

    val aRank = matrixRank(AArray)
    println(s"Matrix A rank: " + aRank)

//    require(aRank == AArray.length, s"Requirement failed: rank(A) = $aRank should equal to ${AArray.length}")
//    require(AArray.length <= AArray(0).length, "Requirement failed: m <= n")


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
