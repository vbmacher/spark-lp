package com.github.vbmacher.spark_lp

import breeze.linalg._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession


// Case class to hold the LP problem data
case class LPDataRDD(c: DenseVector[Double], A: RDD[DenseVector[Double]], b: DenseVector[Double])

object MehrotrasPC {

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder.appName("Parallel Mehrotras Predictor-Corrector").master("local[2]").getOrCreate()

    // Dummy LP data
    val c = DenseVector(4.0, 3.0)
    val A = DenseMatrix((-1.0, -1.0), (1.0, 0.0))
    val b = DenseVector(-5.0, 3.0)

    //

    // Convert matrix `A` into RDD of rows (for parallelization)
    val A_rdd = spark.sparkContext.parallelize(A(*, ::).iterator.toSeq)

    val lpDataRDD = LPDataRDD(c, A_rdd, b)

    // Solve using Parallel Mehrotra's Predictor-Corrector
    val solution = solveMehrotra(lpDataRDD, spark)
    println(s"Optimal solution: $solution")

    spark.stop()
  }

  def solveMehrotra(lp: LPDataRDD, spark: SparkSession, maxIter: Int = 500, tolerance: Double = 1e-8): DenseVector[Double] = {
    val (c, a_rdd, b) = (lp.c, lp.A, lp.b)
    val m = b.length // equations count
    val n = c.length // unknowns count
    val A_rdd = a_rdd

    // Transpose A_rdd (which contains rows) into columns
    val A_T_rdd = A_rdd.zipWithIndex.flatMap { case (row, rowIndex) =>
      row.iterator.zipWithIndex.map { case (value, colIndex) =>
        (colIndex, (rowIndex, value))
      }
    }.groupByKey().mapValues { rows =>
      DenseVector(rows.toArray.sortBy(_._1).map(_._2._2))
    }

    // Initial guesses, partition vectors for parallel operations
    val x = DenseVector.ones[Double](n)
    val s = DenseVector.ones[Double](n)
    val z = DenseVector.ones[Double](n)
    val y = DenseVector.zeros[Double](m)

    // Broadcast variables to avoid shuffling in every iteration
    val cBroadcast = spark.sparkContext.broadcast(c)
    var xBroadcast = spark.sparkContext.broadcast(x)
    var sBroadcast = spark.sparkContext.broadcast(s)
    var yBroadcast = spark.sparkContext.broadcast(y)
    var zBroadcast = spark.sparkContext.broadcast(z)

    for (iter <- 1 to maxIter) {
      // Residual r_b = A * x - b (parallel matrix-vector multiplication)
      val r_b_rdd = A_rdd.map(row => row.dot(xBroadcast.value)) // A * x
      val r_b = DenseVector(r_b_rdd.collect()) - b // changing when x changes

      // Residual r_c = A^T * y + s - c (parallel)
      val r_c_rdd = A_T_rdd.map { case (colIndex, colVector) =>
        colVector.dot(yBroadcast.value) + sBroadcast.value(colIndex) - cBroadcast.value(colIndex)
      }
      val r_c = DenseVector(r_c_rdd.collect())

      // Residual r_s = x * z (element-wise multiplication, parallel)
      val r_s_rdd = xBroadcast.value.data.zip(zBroadcast.value.data).map { case (xi, zi) => xi * zi }
      val r_s = DenseVector(r_s_rdd)

      // Check for convergence
      if (norm(r_b) < tolerance && norm(r_c) < tolerance && norm(r_s) < tolerance) {
        println(s"Converged in $iter iterations")
        return x
      } else {
        println(s"norm(r_b) = ${norm(r_b)}, norm(r_c) = ${norm(r_c)}, norm(r_s) = ${norm(r_s)}")
      }

      // Solve for predictor direction (Affine scaling)
      val (dx_aff, dy_aff, ds_aff) = computeKKTAndSolve(A_rdd, r_b, r_c, r_s, xBroadcast.value, zBroadcast.value, spark)

      // Compute step lengths
      val alpha_aff_pri = computeStepLength(xBroadcast.value, dx_aff)
      val alpha_aff_dual = computeStepLength(zBroadcast.value, ds_aff)

      // Corrector step (similar logic to predictor step)
      val sigma = math.pow((x.t * z) / n, 3)
      val r_s_corr = r_s + dx_aff *:* ds_aff - sigma * DenseVector.ones[Double](n)

      val (dx_corr, dy_corr, ds_corr) = computeKKTAndSolve(A_rdd, r_b, r_c, r_s_corr, xBroadcast.value, zBroadcast.value, spark)

      // Step lengths for corrector
      val alpha_pri = computeStepLength(xBroadcast.value, dx_corr)
      val alpha_dual = computeStepLength(zBroadcast.value, ds_corr)

      // Update variables
      x += alpha_pri * dx_corr
      y += alpha_pri * dy_corr
      s += alpha_dual * ds_corr
      z += alpha_dual * ds_corr

      // Re-broadcast updated variables
      xBroadcast = spark.sparkContext.broadcast(x)
      sBroadcast = spark.sparkContext.broadcast(s)
      yBroadcast = spark.sparkContext.broadcast(y)
      zBroadcast = spark.sparkContext.broadcast(z)
    }

    println(s"Reached maximum iterations ($maxIter)")
    x
  }

  // Function to solve the KKT system
  def computeKKTAndSolve(
    A_rdd: RDD[DenseVector[Double]],
    r_b: DenseVector[Double],
    r_c: DenseVector[Double],
    r_s: DenseVector[Double],
    x: DenseVector[Double],
    z: DenseVector[Double],
    spark: SparkSession
  ): (DenseVector[Double], DenseVector[Double], DenseVector[Double]) = {
    // Step 1: Compute A^T * A (gram matrix)
    val AtA_rdd: RDD[DenseMatrix[Double]] = A_rdd.map { row =>
      val rowMatrix = row.toDenseMatrix  // Convert DenseVector to DenseMatrix
      rowMatrix.t * rowMatrix  // Outer product: row' * row
    }

    // Sum all AtA parts together
    val AtA: DenseMatrix[Double] = AtA_rdd.reduce(_ + _)

    // Step 2: Compute A^T * r_b (right-hand side vector)
    val At_r_b_rdd: RDD[DenseVector[Double]] = A_rdd.map { row =>
      row * (row.t * r_b)
    }

    // Sum all the components of A^T * r_b
    val At_r_b: DenseVector[Double] = At_r_b_rdd.reduce(_ + _)

    // Step 3: Solve the KKT system (Simplified)
    // Here, we need to solve the system AtA * dx = At_r_b
    // Using Breeze's backslash operator `\` for solving linear systems
    val dx: DenseVector[Double] = AtA \ At_r_b

    // Step 4: Compute dz using r_c and x
    val dz: DenseVector[Double] = r_c - dx

    // Step 5: Compute ds using r_s and z
    val ds: DenseVector[Double] = r_s - dz

    // Return the corrections dx, dz, ds
    (dx, dz, ds)
  }

  // Compute the step length to avoid boundary violations
  def computeStepLength(x: DenseVector[Double], dx: DenseVector[Double]): Double = {
    min(1.0, min((-x /:/ dx).map(v => if (v > 0) v else Double.PositiveInfinity)))
  }
}