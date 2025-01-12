package com.github.vbmacher.spark_lp.examples

import com.github.vbmacher.spark_lp.LP
import com.github.vbmacher.spark_lp.random.SparseGaussianGenerator
import com.github.vbmacher.spark_lp.vectors.DMatrix
import com.github.vbmacher.spark_lp.vectors.dmatrix.implicits._
import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.mllib.random.RandomRDDs
import org.apache.spark.mllib.wrappers.RandomVectorRDD
import org.apache.spark.sql.SparkSession

import scala.util.Random

/**
  * This example generates a random linear programming problem and solves it using LP.solve
  *
  * The example can be executed as follows:
  * sbt 'test:run-main com.github.vbmacher.spark_lp.examples.ExampleRandomLP'
  */
object ExampleRandomLP extends App {

  implicit val spark: SparkSession = SparkSession.builder
    .appName("ExampleRandomLP")
    .master("local[2]")
    .getOrCreate()

  val rnd = new Random(12345)

  val n = 1000 // Transpose constraint matrix row count.
  val m = 100 // Transpose constraint matrix column count.
  val numPartitions = 2

  // Generate the starting vector from uniform distribution U(3.0, 5.0)
  println("generate x")
  val x0 = RandomRDDs.uniformRDD(spark.sparkContext, n, numPartitions).map(v => 3.0 + 2.0 * v).glom.map(new DenseVector(_))

  // Generate the transpose constraint matrix 'A' using sparse uniformly generated values.
  println("generate A")
  val A: DMatrix = RandomVectorRDD(
    n,
    m,
    numPartitions,
    SparseGaussianGenerator(0.1),
    rnd.nextLong)

  // Generate the cost vector 'c' using uniformly generated values.
  println("generate c")
  val c = RandomRDDs.uniformRDD(spark.sparkContext, n, numPartitions, rnd.nextLong).glom.map(new DenseVector(_))

  // Compute 'b' using the starting 'x' vector.
  println("generate b")
  val b = A.adjointProduct(x0)

  // Solve the linear program using LP.solve, finding the optimal x vector 'optimalX'.
  println("Start solving ...")
  val (optimalVal, optimalX) = LP.solve(c, A, b)
  println("optimalVal: " + optimalVal)
  println("optimalX: " + optimalX.collect().mkString(", "))

  spark.stop()
}
