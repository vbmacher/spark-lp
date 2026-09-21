package com.github.vbmacher.spark_lp.newton

/**
  * Configuration used only by [[NewtonSolver.ConjugateGradient]].
  *
  * Regularization stabilizes the inner linear equations; convergence is still tested against the
  * unregularized optimization model.
  *
  * @param primalRegularization positive diagonal value added on the primal side
  * @param dualRegularization positive diagonal value added on the equality-multiplier side
  * @param preconditionerRank requested partial-Cholesky rank; zero selects adaptive rank
  * @param preconditionerMemoryBytes maximum driver bytes for preconditioner factors; zero selects
  *                                  diagonal-only preconditioning
  */
final case class CgConfig(
  primalRegularization: Double = 1e-8,
  dualRegularization: Double = 1e-8,
  preconditionerRank: Int = 0,
  preconditionerMemoryBytes: Long = 256L * 1024 * 1024) {
  Seq(primalRegularization, dualRegularization).foreach { value =>
    require(value > 0.0 && !value.isInfinite, "Regularization must be finite and positive")
  }
  require(preconditionerRank >= 0, "Preconditioner rank must be nonnegative (0 selects adaptive rank)")
  require(preconditionerMemoryBytes >= 0, "Preconditioner memory budget must be nonnegative")
}
