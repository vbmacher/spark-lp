package com.github.vbmacher.spark_lp

import com.github.vbmacher.spark_lp.dsl.LpModelException

/** Numeric validation shared by model construction, compilation, and solving. */
private[spark_lp] object Numerics {

  /** True when `value` is neither NaN nor infinite. */
  def isFinite(value: Double): Boolean = !value.isNaN && !value.isInfinite

  /** Returns `value`, or throws [[LpModelException]] when it is NaN or infinite. */
  def requireFinite(value: Double, description: String): Unit =
    if (!isFinite(value)) throw new LpModelException(s"$description must be finite")
}
