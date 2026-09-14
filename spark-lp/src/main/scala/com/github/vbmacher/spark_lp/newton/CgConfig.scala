package com.github.vbmacher.spark_lp.newton

/** Controls for the conjugate-gradient Newton solver; ignored by direct Cholesky.
  * Regularizations are diagonal entries (not square roots).
  * Proximal reference points are reset to the current iterate, so the residuals are those
  * of the original LP. A fixed positive regularization bounds the Newton weights without
  * changing the convergence test. See docs/algorithm.adoc for equations and pivot policy.
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
