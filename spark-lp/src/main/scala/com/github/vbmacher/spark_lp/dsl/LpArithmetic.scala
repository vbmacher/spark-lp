package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.Numerics

private[dsl] object LpArithmetic {
  def reciprocal(divisor: Double): Double = {
    Numerics.requireFinite(divisor, "Divisor")
    if (divisor == 0.0) throw new LpModelException("Divisor must be nonzero")
    val result = 1.0 / divisor
    Numerics.requireFinite(result, "Reciprocal divisor")
    result
  }
}
