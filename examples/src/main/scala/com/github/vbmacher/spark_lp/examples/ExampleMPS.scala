package com.github.vbmacher.spark_lp.examples

import com.github.vbmacher.spark_lp.LP
import com.joptimizer.optimizers.LPStandardConverter
import com.joptimizer.util.MPSParser
import org.apache.spark.mllib.linalg.{DenseVector, Vector, Vectors}
import org.apache.spark.sql.SparkSession

import java.io.File

/** Solves a continuous linear minimization problem supplied as an MPS file.
  *
  * The input file defines all problem data: a cost coefficient for each variable,
  * linear equality and inequality rows with their right-hand sides, and variable
  * bounds. The goal is to find variable values satisfying those rows and bounds while
  * minimizing the linear objective. There is no built-in business problem or expected
  * objective value; both depend on the file supplied by the caller.
  *
  * JOptimizer's MPS parser reads the input, and its standard-form converter transforms
  * the problem into minimizing c transpose x subject to A x = b and x >= 0. The example
  * distributes c and the columns of A with Spark, retains b on the driver, and calls
  * LP.solve. This is a continuous LP path; it does not perform integer optimization.
  *
  * Output consists of the objective value and the standard-form solution vector. That
  * vector may contain auxiliary variables and transformed original variables; this
  * example does not map it back to the original MPS variable names or coordinates.
  *
  * Supply exactly one argument, the path to an MPS file readable by the driver:
  * {{{
  * sbt 'examplesSpark_3_5/runMain com.github.vbmacher.spark_lp.examples.ExampleMPS /absolute/path/problem.mps'
  * }}}
  */
object ExampleMPS extends App {

  require(args.length == 1, "Supply one MPS file path")

  implicit val spark: SparkSession = SparkSession.builder
    .appName("ExampleMPS")
    .master("local[2]")
    .getOrCreate()

  // Parse the provided MPS file.
  val parser = new MPSParser()
  val mpsFile = new File(args(0))
  parser.parse(mpsFile)

  // Convert the parsed linear program to standard form.
  val converter = new LPStandardConverter(true)
  converter.toStandardForm(parser.getC,
    parser.getG,
    parser.getH,
    parser.getA,
    parser.getB,
    parser.getLb,
    parser.getUb)

  // Convert the parameters of the linear program to spark lp compatible formats.
  val numPartitions = 2
  val c = spark.sparkContext
    .parallelize(converter.getStandardC.toArray, numPartitions)
    .glom.map(new DenseVector(_))

  val AT = spark.sparkContext
    .parallelize(converter.getStandardA.toArray.transpose.map(Vectors.dense(_).toSparse: Vector), numPartitions)

  val b = new DenseVector(converter.getStandardB.toArray)
  println("Start solving ... ")
  val (optimalVal, optimalX) = LP.solve(c, AT, b)
  println("optimalVal: " + optimalVal)
  println("optimalX: " + optimalX.collect().mkString(", "))

  spark.stop()
}
