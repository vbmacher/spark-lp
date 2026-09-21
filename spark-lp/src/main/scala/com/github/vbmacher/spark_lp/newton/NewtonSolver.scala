package com.github.vbmacher.spark_lp.newton

/**
  * Strategy for solving the linear equations used by each interior-point predictor and corrector.
  *
  * Let `m` be the number of equality-form constraint rows:
  *
  *  - [[NewtonSolver.Cholesky]] collects and factors an `m x m` matrix on the driver.
  *  - [[NewtonSolver.ConjugateGradient]] applies that matrix through distributed operations and
  *    keeps only vectors and a bounded preconditioner on the driver.
  *  - [[NewtonSolver.Auto]] chooses between them from `m` and the caller's driver-memory cap.
  */
sealed trait NewtonSolver

object NewtonSolver {

  /** Uses Cholesky up to the configured row cutoff and conjugate gradient above it. */
  case object Auto extends NewtonSolver

  /** Always collects and factors the weighted constraint matrix on the driver. */
  case object Cholesky extends NewtonSolver

  /** Always uses distributed matrix operations with preconditioned conjugate gradient. */
  case object ConjugateGradient extends NewtonSolver

  /**
    * Largest equality-form row count at which [[Auto]] may choose [[Cholesky]].
    *
    * This is a conservative policy, not a workload-independent performance crossover.
    */
  val AutoCholeskyLimit: Int = 10000

  /** Resolve Auto using the row count and the caller's driver resource cap. */
  private[spark_lp] def resolve(solver: NewtonSolver, equations: Long,
    maxLocalConstraints: Long = AutoCholeskyLimit.toLong): NewtonSolver = solver match {
    case Auto if equations <= math.min(maxLocalConstraints, AutoCholeskyLimit.toLong) => Cholesky
    case Auto => ConjugateGradient
    case selected => selected
  }
}
