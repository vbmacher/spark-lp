package com.github.vbmacher.spark_lp.dsl

import org.apache.spark.sql.Row
import org.apache.spark.sql.types.{DoubleType, StringType, StructField, StructType}

/** Shared public shape and row conventions for per-constraint solution diagnostics. */
private[dsl] object ConstraintDiagnostics {
  val schema: StructType = StructType(Seq(
    StructField("name", StringType, nullable = false),
    StructField("group", StringType, nullable = true),
    StructField("activity", DoubleType, nullable = false),
    StructField("sense", StringType, nullable = false),
    StructField("rhs", DoubleType, nullable = false),
    StructField("slack", DoubleType, nullable = false),
    StructField("dual", DoubleType, nullable = true),
    StructField("note", StringType, nullable = true),
    StructField("dual_note", StringType, nullable = true)))

  def row(name: String, group: String, activity: Double, sense: String, rhs: Double,
    dual: java.lang.Double, note: String, dualNote: String): Row =
    Row(name, group, activity, sense, rhs, slack(sense, activity, rhs), dual, note, dualNote)

  private def slack(sense: String, activity: Double, rhs: Double): Double =
    if (sense == LpSense.Ge.symbol) activity - rhs else rhs - activity
}
