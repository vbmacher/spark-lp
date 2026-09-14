package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.dsl.implicits._
import org.apache.spark.sql.SparkSession
import java.io.{File, PrintWriter}
import java.lang.management.ManagementFactory
import scala.collection.JavaConverters._

/** Reproducible end-to-end comparison against the same analytic optimum.
  * No timing assertions: emit successful and failed attempts with original-space accuracy.
  */
object QpComparison {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Pass an absolute CSV output path")
    val output = new File(args(0))
    require(output.isAbsolute && !output.exists(), "Output must be new and absolute")
    implicit val spark: SparkSession = SparkSession.builder().master("local[2]")
      .appName("coupled-qp-comparison").config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "2").getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")
    val writer = new PrintWriter(output)
    writer.println("n,representation,backend,repetition,status,seconds,max_value_error,objective_error,primal,dual,gap,peak_heap_pool_bytes")
    try {
      for (n <- Seq(4, 12); backend <- Seq(NewtonSolver.Cholesky, NewtonSolver.ConjugateGradient);
           representation <- Seq("separable", "factor"); repetition <- 0 to 1) {
        val model = LpProblem("comparison")
        val targets = (0 until n).map(i => 1.0 + i.toDouble / n)
        val variables = targets.indices.map(i => model.variable(s"x$i"))
        val terms = variables.zip(targets).map { case (v, target) =>
          if (representation == "factor") QpObjective.squared(v - target)
          else QpObjective.squaredDeviation(v, target)
        }
        model += terms.reduce(_ + _)
        model += (variables.map(v => 1.0 * v).reduce(_ + _) === targets.sum - n * 0.25)
        ManagementFactory.getMemoryPoolMXBeans.asScala.foreach(_.resetPeakUsage())
        val start = System.nanoTime()
        try {
          val result = model.solve(SolveConfig(newtonSolver = backend, maxIterations = 100))
          val seconds = (System.nanoTime() - start) / 1e9
          try {
            val error = variables.zip(targets).map { case (v, target) => math.abs(result.value(v) - (target - 0.25)) }.max
            val objError = math.abs(result.objectiveValue - n * 0.0625)
            val peak = ManagementFactory.getMemoryPoolMXBeans.asScala.filter(_.getType.toString == "Heap memory")
              .map(_.getPeakUsage.getUsed).sum
            writer.println(s"$n,$representation,$backend,$repetition,${result.status},$seconds,$error,$objError," +
              s"${result.residuals.primal},${result.residuals.dual},${result.residuals.gap},$peak")
          } finally result.close()
        } catch {
          case scala.util.control.NonFatal(error) =>
            writer.println(s"$n,$representation,$backend,$repetition,${error.getClass.getSimpleName}," +
              s"${(System.nanoTime() - start) / 1e9},,,,,,")
            error.printStackTrace()
        }
        writer.flush()
      }
    } finally { writer.close(); spark.stop() }
  }
}
