package com.github.vbmacher.spark_lp

import com.github.vbmacher.spark_lp.newton.{CgConfig, NewtonSolver}

import org.apache.spark.mllib.linalg.{DenseVector, Vector}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession

/** Algorithm contract for performance benchmarks; CSV inventories define campaigns.
  * All implementations share solver settings, distributed generation, measurement
  * and independent validation. Keeping this contract in spark_lp permits direct
  * access to solver diagnostics without adapters or changes to the public LP API.
  */
trait Benchmark {
  def name: String
  def algorithm: NewtonSolver
  final def suite: String = getClass.getName.stripSuffix("$")
  private[spark_lp] final def solve(costs: RDD[DenseVector], columns: RDD[Vector], rhs: DenseVector,
                  tolerance: Double, progress: SolveProgress => Unit,
                  inspect: (RDD[DenseVector], DenseVector, RDD[DenseVector]) => Unit)
                 (implicit spark: SparkSession): LP.SolveSummary =
    LP.solveSummary(costs, columns, rhs, tolerance = tolerance, maxIter = 100,
      solver = algorithm, cgTolerance = 1e-10, cgMaxIterations = 1000,
      cgConfig = CgConfig(1e-8, 1e-8, 0, 256L * 1024 * 1024),
      control = SolveControl(onProgress = progress), inspectConverged = Some(inspect))
}

/** Explicit algorithm registry used by the command-line runner. */
object Benchmark {
  val all: Seq[Benchmark] = Seq(CholeskyBenchmark, CGBenchmark)
  def named(name: String): Benchmark = all.find(_.name == name.toLowerCase).getOrElse(
    throw new IllegalArgumentException(s"Unknown benchmark '$name'; choose ${all.map(_.name).mkString(", ")}"))
}
