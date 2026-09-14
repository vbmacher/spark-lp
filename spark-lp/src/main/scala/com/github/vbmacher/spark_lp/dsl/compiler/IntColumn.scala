package com.github.vbmacher.spark_lp.dsl.compiler

/**
  * Driver-local description of one integral (Integer/Binary) solver column, everything the
  * discrete solver needs to retarget the equality-form RHS when the column's integral
  * bounds are tightened. `rowCoeffs` maps emitted constraint-row indices to the column's
  * coefficients; `boundRow` is the column's upper-bound row (`y + s = upper - lower`), which
  * every integral column has by construction.
  */
private[dsl] final case class IntColumn(
  g: Long,
  setIndex: Int,
  enc: String,
  rootLower: Double,
  rootUpper: Double,
  cost: Double,
  boundRow: Int,
  rowCoeffs: Map[Int, Double])
