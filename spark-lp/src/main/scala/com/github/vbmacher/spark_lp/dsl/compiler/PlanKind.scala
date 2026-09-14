package com.github.vbmacher.spark_lp.dsl.compiler

/**
  * How a variable set is realised in the solver model after presolve: a set is either eliminated
  * ([[FixedKind]]), represented by a single shifted non-negative column ([[ShiftedKind]]), or split
  * into a positive/negative column pair for a free variable ([[SplitKind]]).
  */
private[dsl] sealed trait PlanKind

/** Fixed variable (`lower == upper`): folded into the RHS/objective constant, given no solver column. */
private[dsl] final case class FixedKind(value: Double) extends PlanKind

/**
  * Lower-bounded variable `x = shift + y` with `y >= 0` and an optional finite `upper`. A defined
  * `upper` adds one bound row `y + s = upper - shift` per key.
  */
private[dsl] final case class ShiftedKind(shift: Double, upper: Option[Double]) extends PlanKind

/** Free variable, represented as the difference of two non-negative columns `x = x_plus - x_minus`. */
private[dsl] case object SplitKind extends PlanKind
