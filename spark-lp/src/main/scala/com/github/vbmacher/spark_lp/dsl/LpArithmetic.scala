package com.github.vbmacher.spark_lp.dsl

private[dsl] object LpArithmetic {
  def requireFinite(value: Double, description: String): Unit = {
    if (value.isNaN || value.isInfinity)
      throw new LpModelException(s"$description must be finite")
  }

  def reciprocal(divisor: Double): Double = {
    requireFinite(divisor, "Divisor")
    if (divisor == 0.0) throw new LpModelException("Divisor must be nonzero")
    val result = 1.0 / divisor
    requireFinite(result, "Reciprocal divisor")
    result
  }
}
