package com.github.vbmacher.spark_lp

/** Stage reported by [[SolveProgress]]; setup and inner solving may repeat within one iteration. */
sealed trait SolvePhase
object SolvePhase {
  /** Constructs the initial variable values and constraint multipliers. */
  case object Initialization extends SolvePhase
  /** Factors a direct system or prepares an iterative preconditioner. */
  case object SystemSetup extends SolvePhase
  /** Solves one predictor or corrector linear system. */
  case object InnerSolve extends SolvePhase
  /** Reports one complete optimization iterate with objective and residuals. */
  case object OuterIteration extends SolvePhase
}
