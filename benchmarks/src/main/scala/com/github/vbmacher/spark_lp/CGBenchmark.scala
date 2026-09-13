package com.github.vbmacher.spark_lp

/** Performance suite for the LP solver's matrix-free conjugate-gradient algorithm.
  * Runs the same CSV-defined cases and independent validation as Cholesky, measuring
  * convergence, outer/inner iterations, restarts, preconditioner rank and solve time.
  * The shared trait pins CG tolerance, step limit and regularization; configuration
  * and actual rank are recorded so results from different settings stay separate.
  * Select this instance with BenchmarkRunner's "cg" argument.
  */
object CGBenchmark extends Benchmark {
  override val name = "cg"
  override val algorithm: NewtonSolver = NewtonSolver.ConjugateGradient
}
