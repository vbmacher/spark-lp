package com.github.vbmacher.spark_lp.dsl

/** The supported cut family is a (possibly non-minimal) binary knapsack cover. */
final case class MipCutsConfig(enabled: Boolean = false, maxRounds: Int = 2,
  maxCutsPerNode: Int = 20, maxCuts: Int = 100, numericalTolerance: Double = 1e-9,
  maxCoefficientRatio: Double = 1e12) {
  require(Seq(maxRounds, maxCutsPerNode, maxCuts).forall(_ >= 0), "Cut budgets must be nonnegative")
  require(java.lang.Double.isFinite(numericalTolerance) && numericalTolerance > 0, "Cut tolerance must be finite and positive")
  require(java.lang.Double.isFinite(maxCoefficientRatio) && maxCoefficientRatio >= 1, "Cut coefficient ratio must be finite and at least one")
}
final case class StrongBranchingConfig(enabled: Boolean = false, maxCandidates: Int = 4,
  maxIterations: Int = 5, maxProbes: Int = 100) {
  require(maxCandidates > 0 && maxIterations > 0 && maxProbes >= 0, "Strong branching budgets must be positive (probe count may be zero)")
}
final case class MipSearchConfig(cuts: MipCutsConfig = MipCutsConfig(),
  strongBranching: StrongBranchingConfig = StrongBranchingConfig(), parallelNodes: Int = 1,
  maxConcurrentLocalBytes: Long = 1024L * 1024 * 1024) {
  require(parallelNodes > 0 && maxConcurrentLocalBytes > 0, "Parallel node and memory budgets must be positive")
}
final case class MipSearchStatistics(relaxations: Int = 0, cutRounds: Int = 0,
  globalCuts: Int = 0, localCuts: Int = 0, strongProbes: Int = 0,
  peakConcurrentNodes: Int = 0, estimatedPeakLocalBytes: Long = 0L)
