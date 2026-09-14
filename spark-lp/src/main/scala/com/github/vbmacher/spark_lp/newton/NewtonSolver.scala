package com.github.vbmacher.spark_lp.newton

/**
  * Strategy for solving the m x m normal-equations ("Newton") systems `A^T D^2 A y = r` that the
  * interior-point solver forms during initialization and once per iteration.
  *
  * The choice governs the driver-side footprint of the constraint dimension `m`:
  *
  *  - [[NewtonSolver.Cholesky]]: the classic direct method. The weighted Gramian is aggregated to
  *    the driver (roughly `16*m*m` bytes of related allocations per solve) and factorized there
  *    (`O(m^3)` per iteration). Exact and fast for small `m`; limited to 65535 rows and by driver
  *    memory.
  *  - [[NewtonSolver.ConjugateGradient]]: matrix-free. The Gramian is never materialised; each CG
  *    step applies the operator with the distributed matrix-vector products already used
  *    elsewhere. Its dense iteration vectors use `O(m)` driver memory; the optional partial
  *    Cholesky preconditioner uses `O(m * rank)` driver and task-local storage. Automatic rank
  *    selection bounds that preconditioner storage and falls back to Jacobi CG when no
  *    column fits. This makes the constraint count a distributed-friendly dimension, at the cost
  *    of extra Spark jobs per iteration (one per CG step) and slightly inexact search directions.
  *    Convergence checks are unaffected: the outer loop recomputes its residuals from the
  *    iterates each iteration.
  *  - [[NewtonSolver.Auto]]: Cholesky while `m` is small enough for the driver, ConjugateGradient
  *    beyond [[NewtonSolver.AutoCholeskyLimit]]. The DataFrame DSL can lower this cutoff with
  *    its separate `SolveConfig.maxLocalConstraints` resource cap.
  */
sealed trait NewtonSolver

object NewtonSolver {

  /** Cholesky through the direct-solver row cutoff, matrix-free CG beyond it. */
  case object Auto extends NewtonSolver

  /** Always use the driver-local Cholesky factorization of the Gramian. */
  case object Cholesky extends NewtonSolver

  /** Always use the matrix-free partial-Cholesky-preconditioned conjugate gradient method. */
  case object ConjugateGradient extends NewtonSolver

  /** Largest Auto Cholesky row count. Prefer the direct solver for medium-sized allocation
    * systems where CG can require hundreds of distributed steps and preconditioner pivots.
    * This is a workload-driven policy, not a universal measured crossover; see benchmarks/src/results/REPORT.md.
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
