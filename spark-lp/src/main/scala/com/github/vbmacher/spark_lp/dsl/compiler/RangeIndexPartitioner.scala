package com.github.vbmacher.spark_lp.dsl.compiler

import org.apache.spark.Partitioner

/**
  * Deterministic, contiguous range partitioner over column indices `0 until total`. With
  * `parts <= total` every partition is non-empty, which the solver's partition-aligned
  * `DVector`/`DMatrix` operations require.
  */
private[dsl] final class RangeIndexPartitioner(total: Long, parts: Int) extends Partitioner {
  require(parts >= 1 && parts <= total, s"parts=$parts must be in [1, $total]")

  override def numPartitions: Int = parts

  override def getPartition(key: Any): Int = {
    val idx = key.asInstanceOf[Long]
    math.min(parts - 1, (idx * parts / total).toInt)
  }
}
