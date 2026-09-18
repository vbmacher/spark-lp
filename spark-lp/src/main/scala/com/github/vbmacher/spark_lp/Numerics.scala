package com.github.vbmacher.spark_lp

import com.github.vbmacher.spark_lp.dsl.LpModelException

/**
  * Shared numeric predicates. Provides the single definition of "finite" used across the DSL,
  * compiler, solver and vector layers so the NaN/infinity convention lives in one place.
  */
private[spark_lp] object Numerics {

  /** True when `value` is neither NaN nor infinite. */
  def isFinite(value: Double): Boolean = !value.isNaN && !value.isInfinite

  /** Throws [[LpModelException]] with a `"<description> must be finite"` message on NaN/infinity. */
  def requireFinite(value: Double, description: String): Unit =
    if (!isFinite(value)) throw new LpModelException(s"$description must be finite")
}
