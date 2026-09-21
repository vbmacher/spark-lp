package com.github.vbmacher.spark_lp.dsl

sealed trait PresolveEffort

object PresolveEffort {
  case object Basic extends PresolveEffort

  case object Full extends PresolveEffort
}

/**
  * Controls model simplifications performed before numerical solving.
  *
  * @param enabled whether presolve transformations run.
  * @param effort `Basic` applies structural simplifications; `Full` also permits substitutions.
  * @param maxSubstitutions maximum free zero-cost columns eliminated through equality rows.
  */
final case class PresolveConfig(
  enabled: Boolean = true,
  effort: PresolveEffort = PresolveEffort.Basic,
  maxSubstitutions: Int = 1000
) {
  require(effort != null && maxSubstitutions >= 0 && maxSubstitutions < Int.MaxValue,
    "Presolve requires a valid effort and a nonnegative bounded substitution budget")
}

/**
  * Bound tightening established during presolve.
  *
  * @param variable stable identity of the tightened variable.
  * @param original bounds before propagation.
  * @param implied bounds after intersecting deductions from constraints and variable domains.
  */
final case class LpBoundReduction(
  variable: LpVariableId,
  original: LpBounds,
  implied: LpBounds
)

/**
  * Records how a variable eliminated by one equality is reconstructed in original model units.
  *
  * @param variable identity of the eliminated free variable.
  * @param rowName equality row used for the substitution.
  * @param coefficient eliminated variable's nonzero coefficient in that row.
  * @param rhs row right-hand side before substitution.
  */
final case class LpSubstitution(
  variable: LpVariableId,
  rowName: String,
  coefficient: Double,
  rhs: Double
)

/**
  * Model-size changes and transformations applied before solving.
  *
  * @param enabled whether presolve was enabled for the run.
  * @param effort configured presolve effort.
  * @param passes completed bound-propagation passes.
  * @param originalVariables expanded variables before presolve.
  * @param originalRows expanded user constraint rows before presolve.
  * @param reducedVariables user variables remaining after fixed values and substitutions.
  * @param reducedRows user rows remaining after simplification.
  * @param solverColumns total internal columns, including splits and slacks.
  * @param solverRows total equality-form rows passed to the numerical solver.
  * @param fixedVariables variables eliminated because their bounds fix one value.
  * @param bounds recorded bound tightenings.
  * @param substitutions recorded equality substitutions.
  */
final case class LpPresolveSummary(
  enabled: Boolean,
  effort: PresolveEffort,
  passes: Int,
  originalVariables: Long,
  originalRows: Int,
  reducedVariables: Long,
  reducedRows: Int,
  solverColumns: Long,
  solverRows: Int,
  fixedVariables: Long,
  bounds: Vector[LpBoundReduction],
  substitutions: Vector[LpSubstitution]
)
