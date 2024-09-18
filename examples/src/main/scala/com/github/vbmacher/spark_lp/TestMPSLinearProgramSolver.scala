package com.github.vbmacher.spark_lp

import com.github.vbmacher.spark_lp.dmatrix.DMatrix
import com.github.vbmacher.spark_lp.dvector.DVector
import com.joptimizer.optimizers.LPStandardConverter
import com.joptimizer.util.MPSParser
import org.apache.spark.mllib.linalg.{DenseVector, Vector, Vectors}
import org.apache.spark.{SparkConf, SparkContext}

import java.io.File

/**
  * This example reads a linear program in MPS format and solves it using LP.solve.
  *
  * The example can be executed as follows:
  * sbt 'test:run-main com.github.vbmacher.spark_lp.TestMPSLinearProgramSolver <mps file>'
  */
object TestMPSLinearProgramSolver {

  def main(args: Array[String]): Unit = {

    val conf = new SparkConf()
      .setMaster("local[2]")
      .setAppName("TestMPSLinearProgramSolver")

    val sc = new SparkContext(conf)

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
    val c: DVector = sc.parallelize(converter.getStandardC.toArray, numPartitions)
      .glom.map(new DenseVector(_))
    val B: DMatrix = sc.parallelize(converter.getStandardA.toArray.transpose.map(
      Vectors.dense(_).toSparse: Vector), numPartitions)
    val b = new DenseVector(converter.getStandardB.toArray)
    println("Start solving ... ")
    val (optimalVal, optimalX) = LP.solve(c, B, b, sc=sc)
    println("optimalVal: " + optimalVal)
    //println("optimalX: " + optimalX.collectElements.mkString(", "))

    sc.stop()
  }
}