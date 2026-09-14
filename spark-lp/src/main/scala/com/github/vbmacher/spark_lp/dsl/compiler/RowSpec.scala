package com.github.vbmacher.spark_lp.dsl.compiler

import com.github.vbmacher.spark_lp.dsl.LpSense

/** One expanded (user-facing) constraint row. */
private[dsl] final class RowSpec(
  val rowId: Int,
  val name: String,
  val group: Option[String],
  val sense: LpSense,
  val rhsUser: Double) {

  /** RHS after fixed-variable and bound-shift folding. */
  var b0: Double = rhsUser
  var note: Option[String] = None
  var emitted: Boolean = true
  var finalIdx: Int = -1
}
