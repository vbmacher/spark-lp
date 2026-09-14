package com.github.vbmacher.spark_lp.dsl.compiler

import com.github.vbmacher.spark_lp.dsl.{VarSetHandle, VariableMetadata}
import org.apache.spark.rdd.RDD

/** Compiled per-set layout: validated keys, transformation kind and the column offset. */
private[dsl] final class SetPlan(
  val handle: VarSetHandle,
  val keys: RDD[(String, Seq[String])],
  val count: Long,
  val kind: PlanKind,
  val integral: Boolean,
  val metadata: VariableMetadata) {

  var offset: Long = 0L

  def columns: Long = kind match {
    case FixedKind(_) => 0L
    case SplitKind => 2 * count
    case ShiftedKind(_, _) => count
    case ReflectedKind(_) => count
  }

  /** Keys sorted by encoded form; ordering never depends on partition order. */
  lazy val sortedKeys: RDD[(String, (Long, Seq[String]))] =
    keys.sortBy(_._1).zipWithIndex().map { case ((enc, disp), i) => (enc, (i, disp)) }
}
