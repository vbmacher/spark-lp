package com.github.vbmacher.spark_lp.dsl.compiler

import org.apache.spark.mllib.linalg.{Vector => MLVector}

/**
  * One solver column. `kind`: 0 = plain shifted variable (`x = shift + y`), 1 = positive part of
  * a free split, 2 = negative part, 3 = internal slack.
  */
private[dsl] final case class ColData(
  setIndex: Int,
  enc: String,
  kind: Byte,
  shift: Double,
  cost: Double,
  vector: MLVector)
