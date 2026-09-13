package com.github.vbmacher.spark_lp.examples

import com.github.vbmacher.spark_lp.LP
import com.github.vbmacher.spark_lp.vectors.DVector
import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.apache.spark.sql.SparkSession

/** Builds a small standard-form LP explicitly from costs, transposed constraint
  * columns and an RHS vector, then prints the core solver's objective and solution.
  * Run with examplesSpark_3_5/runMain and this object's fully qualified name.
  */
object ExampleStandardForm extends App {

  implicit val spark: SparkSession = SparkSession.builder
    .appName("ExampleStandardForm")
    .master("local[2]")
    .getOrCreate()

  val numPartitions = 2
  val cArray = Array(2.0, 1.5, 0.0, 0.0, 0.0, 0.0, 0.0)
  val ATArray = Array(
    Array(12.0, 16.0, 30.0, 1.0, 0.0),
    Array(24.0, 16.0, 12.0, 0.0, 1.0),
    Array(-1.0, 0.0, 0.0, 0.0, 0.0),
    Array(0.0, -1.0, 0.0, 0.0, 0.0),
    Array(0.0, 0.0, -1.0, 0.0, 0.0),
    Array(0.0, 0.0, 0.0, 1.0, 0.0),
    Array(0.0, 0.0, 0.0, 0.0, 1.0))
  val bArray = Array(120.0, 120.0, 120.0, 15.0, 15.0)

  val c = spark.sparkContext.parallelize(cArray, numPartitions).glom.map(new DenseVector(_))
  val rows = spark.sparkContext.parallelize(ATArray, numPartitions).map(Vectors.dense)
  val b = new DenseVector(bArray)

  try {
    val (v, x): (Double, DVector) = LP.solve(c, rows, b)
    try {
      val xx = Vectors.dense(x.flatMap(_.toArray).collect())
      println(s"optimal vector is $xx")
      println("optimal min value: " + v)
    } finally x.unpersist()
  } finally spark.stop()
}
