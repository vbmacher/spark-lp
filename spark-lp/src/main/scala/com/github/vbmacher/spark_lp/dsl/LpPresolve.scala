package com.github.vbmacher.spark_lp.dsl

sealed trait PresolveEffort
object PresolveEffort {
  case object Basic extends PresolveEffort
  case object Full extends PresolveEffort
}
final case class PresolveConfig(enabled: Boolean = true, effort: PresolveEffort = PresolveEffort.Basic,
  maxSubstitutions: Int = 1000) {
  require(effort != null && maxSubstitutions >= 0 && maxSubstitutions < Int.MaxValue,
    "Presolve requires a valid effort and a nonnegative bounded substitution budget")
}
final case class LpBoundReduction(variable: LpVariableId, original: LpBounds, implied: LpBounds)
/** Reconstruct x = (original row RHS - sum of the other original terms) / coefficient. */
final case class LpSubstitution(variable: LpVariableId, rowName: String, coefficient: Double, rhs: Double)
final case class LpPresolveSummary(enabled: Boolean, effort: PresolveEffort, passes: Int,
  originalVariables: Long, originalRows: Int, reducedVariables: Long, reducedRows: Int,
  solverColumns: Long, solverRows: Int, fixedVariables: Long,
  bounds: Vector[LpBoundReduction], substitutions: Vector[LpSubstitution])
