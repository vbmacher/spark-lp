package com.github.vbmacher.spark_lp

/** Stages shared by every Newton backend. Setup and inner solving can repeat within an iteration. */
sealed trait SolvePhase
object SolvePhase {
  /** Construct the initial primal and dual iterates. */
  case object Initialization extends SolvePhase
  /** Prepare or strengthen a linear system: direct factors or an iterative preconditioner. */
  case object SystemSetup extends SolvePhase
  /** Solve one linear-system right-hand side. */
  case object InnerSolve extends SolvePhase
  /** A complete primal/dual iterate, with objective and residuals available. */
  case object OuterIteration extends SolvePhase
}
