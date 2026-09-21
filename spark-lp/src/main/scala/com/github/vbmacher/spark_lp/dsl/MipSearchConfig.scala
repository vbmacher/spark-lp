package com.github.vbmacher.spark_lp.dsl

/**
  * Controls binary knapsack-cover cut generation.
  *
  * @param enabled whether node relaxations may be strengthened with cover cuts.
  * @param maxRounds maximum cut-and-resolve rounds per node.
  * @param maxCutsPerNode maximum accepted cuts at one node across all rounds.
  * @param maxCuts maximum accepted cuts across the whole search.
  * @param numericalTolerance conservative margin added while deriving a cover.
  * @param maxCoefficientRatio largest coefficient-magnitude ratio accepted in a source row.
  */
final case class MipCutsConfig(
  enabled: Boolean = false,
  maxRounds: Int = 2,
  maxCutsPerNode: Int = 20,
  maxCuts: Int = 100,
  numericalTolerance: Double = 1e-9,
  maxCoefficientRatio: Double = 1e12
) {
  require(Seq(maxRounds, maxCutsPerNode, maxCuts).forall(_ >= 0), "Cut budgets must be nonnegative")
  require(java.lang.Double.isFinite(numericalTolerance) && numericalTolerance > 0, "Cut tolerance must be finite and positive")
  require(java.lang.Double.isFinite(maxCoefficientRatio) && maxCoefficientRatio >= 1, "Cut coefficient ratio must be finite and at least one")
}

/**
  * Controls limited relaxation probes used to choose a branching variable.
  *
  * @param enabled whether strong-branching probes replace the default fractional choice.
  * @param maxCandidates maximum fractional variables probed at one node.
  * @param maxIterations outer-iteration limit for each probe relaxation.
  * @param maxProbes maximum probes across the whole search; zero disables all probes.
  */
final case class StrongBranchingConfig(
  enabled: Boolean = false,
  maxCandidates: Int = 4,
  maxIterations: Int = 5,
  maxProbes: Int = 100) {
  require(maxCandidates > 0 && maxIterations > 0 && maxProbes >= 0, "Strong branching budgets must be positive (probe count may be zero)")
}

/**
  * Optional mixed-integer search enhancements and driver-memory limits.
  *
  * @param cuts cover-cut generation settings.
  * @param strongBranching branch-variable probing settings.
  * @param parallelNodes maximum node relaxations solved concurrently.
  * @param maxConcurrentLocalBytes estimated aggregate driver-memory budget for concurrent nodes.
  */
final case class MipSearchConfig(
  cuts: MipCutsConfig = MipCutsConfig(),
  strongBranching: StrongBranchingConfig = StrongBranchingConfig(),
  parallelNodes: Int = 1,
  maxConcurrentLocalBytes: Long = 1024L * 1024 * 1024
) {
  require(parallelNodes > 0 && maxConcurrentLocalBytes > 0, "Parallel node and memory budgets must be positive")
}

/**
  * Work counters collected during mixed-integer search.
  *
  * @param relaxations node, cut-round, and strong-branching relaxations solved.
  * @param cutRounds completed cut-generation rounds.
  * @param globalCuts accepted cuts valid throughout the search tree.
  * @param localCuts accepted cuts valid only below one node domain.
  * @param strongProbes completed strong-branching probe solves.
  * @param peakConcurrentNodes largest number of simultaneous node solves.
  * @param estimatedPeakLocalBytes largest estimated aggregate driver-local node memory.
  */
final case class MipSearchStatistics(
  relaxations: Int = 0,
  cutRounds: Int = 0,
  globalCuts: Int = 0,
  localCuts: Int = 0,
  strongProbes: Int = 0,
  peakConcurrentNodes: Int = 0,
  estimatedPeakLocalBytes: Long = 0L
)
