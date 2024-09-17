package com.github.vbmacher.spark_lp

import org.apache.spark.sql.{DataFrame, SparkSession}
import breeze.linalg._
import breeze.numerics._
import breeze.stats.distributions._
import com.github.fommil.netlib.LAPACK
import org.netlib.util.intW

object TestLp4 {

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("Mehrotra LP Solver with LAPACK")
      .master("local[*]")  // Adjust as necessary
      .getOrCreate()

    import spark.implicits._

    // Example input data
    val A = DenseMatrix(
      (1.0, 0.0),
      (0.0, 1.0),
      (1.0, 1.0)
    )
    val c = DenseVector(1.0, 1.0)
    val b = DenseVector(1.0, 1.0, 2.0)

    // Initialize variables
    val (x, lambda, s, mu) = initializeVariables(A, c, b)

    // Main iteration loop
    var currentX = x
    var currentLambda = lambda
    var currentS = s
    var currentMu = mu
    var iteration = 0
    val maxIterations = 100

    while (iteration < maxIterations) {
      val (newX, newLambda, newS, newMu) = mehrotraIteration(A, c, b, currentX, currentLambda, currentS, currentMu)
      if (norm(newX - currentX) < 1e-6 && abs(newMu - currentMu) < 1e-6) {
        println(s"Converged after $iteration iterations")
        currentX = newX
        currentLambda = newLambda
        currentS = newS
        currentMu = newMu
        iteration = maxIterations  // Exit loop
      } else {
        currentX = newX
        currentLambda = newLambda
        currentS = newS
        currentMu = newMu
        iteration += 1
      }
    }

    println(s"Optimal x: $currentX")
    println(s"Optimal lambda: $currentLambda")
    println(s"Optimal s: $currentS")
    println(s"Optimal mu: $currentMu")

    spark.stop()
  }

  // Initialize variables correctly using b
  def initializeVariables(A: DenseMatrix[Double], c: DenseVector[Double], b: DenseVector[Double]): (DenseVector[Double], DenseVector[Double], DenseVector[Double], Double) = {
    val n = A.cols
    val m = A.rows

    // Example initialization based on A and b
    val x = DenseVector.fill(n)(1.0)  // Initial guess for x
    val lambda = DenseVector.fill(m)(1.0) // Initial guess for lambda

    // Compute initial s
    val s = b - A * x

    // Compute initial mu
    val mu = (x dot s) / x.length

    (x, lambda, s, mu)
  }

  // Solve KKT system using LAPACK for Cholesky decomposition
  def solveKKT(A: DenseMatrix[Double],
    c: DenseVector[Double],
    b: DenseVector[Double],
    x: DenseVector[Double],
    lambda: DenseVector[Double],
    s: DenseVector[Double]): (DenseVector[Double], DenseVector[Double], DenseVector[Double]) = {
    val n = A.cols
    val m = A.rows

    // Construct KKT matrix
    val H = DenseMatrix.eye[Double](n)
    val KKTMatrix = DenseMatrix.vertcat(
      DenseMatrix.horzcat(A.t * A, -DenseMatrix.eye[Double](n)),
      DenseMatrix.horzcat(DenseMatrix.zeros[Double](n, m), DenseMatrix.eye[Double](n))
    )

    // Cholesky decomposition using LAPACK
    val lapack = LAPACK.getInstance()
    val nRows = KKTMatrix.rows
    val flatKKT = KKTMatrix.toArray
    val info = new intW(0)

    // Perform Cholesky factorization
    lapack.dpotrf("L", nRows, flatKKT, nRows, info)

    if (info.`val` != 0) {
      throw new IllegalStateException(s"Cholesky decomposition failed with info ${info.`val`}")
    }

    val L = new DenseMatrix(nRows, nRows, flatKKT)

    // Solve the system using the Cholesky factor L
    val bMatrix = DenseVector.vertcat(
      A.t * (lambda - c) + s,
      DenseVector.zeros[Double](n)
    )

    val solution = L \ bMatrix
    val xNew = solution(0 until n)
    val lambdaNew = solution(n until n + m)
    val sNew = solution(n + m until n + 2 * n)

    (xNew, lambdaNew, sNew)
  }

  // Update x and mu
  def mehrotraIteration(A: DenseMatrix[Double],
    c: DenseVector[Double],
    b: DenseVector[Double],
    x: DenseVector[Double],
    lambda: DenseVector[Double],
    s: DenseVector[Double],
    mu: Double): (DenseVector[Double], DenseVector[Double], DenseVector[Double], Double) = {
    val (xNew, lambdaNew, sNew) = solveKKT(A, c, b, x, lambda, s)

    // Update slack variable s
    val updatedS = updateSlackVariable(xNew, lambdaNew, mu)

    // Compute new mu
    val newMu = (xNew dot updatedS) / xNew.length

    (xNew, lambdaNew, updatedS, newMu)
  }

  // Update slack variable s
  def updateSlackVariable(x: DenseVector[Double],
    lambda: DenseVector[Double],
    mu: Double): DenseVector[Double] = {

    // Compute s as element-wise multiplication of x and lambda, then subtract mu element-wise
    val s = (x * lambda) - mu
    max(s, 0.0).toDenseVector  // Ensure non-negative values
  }
}