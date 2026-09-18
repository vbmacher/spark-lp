package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.{CachedRDDs, LP}
import com.github.vbmacher.spark_lp.dsl.compiler.{ColData, Compiled, RangeIndexPartitioner}
import com.github.vbmacher.spark_lp.vectors.{DMatrix, DVector}
import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.apache.spark.rdd.RDD

/** One node's optional cut rows. Shared compiled inputs remain owned by the outer compiler. */
private[dsl] final class MipNodeRelaxation(base: Compiled, nodeRhs: DenseVector,
  cuts: Vector[MipCoverCut], lower: Array[Double], upper: Array[Double]) extends AutoCloseable {
  require(base.numRows.toLong + cuts.size <= Int.MaxValue, "Cut rows exceed the supported dimension")
  private val integralIds = base.intCols.map(_.g).toSet
  require(cuts.forall(cut => cut.scope.permits(lower, upper) && cut.columns.forall(integralIds)), "Invalid cut identities or scope")
  private val caches = new CachedRDDs
  val rows: Int = base.numRows + cuts.size
  private val augmented: Option[RDD[(Long, ColData)]] = if (cuts.isEmpty) None else {
    require(cuts.forall(_.scope.permits(lower, upper)), "A node-local cut cannot escape its bound domain")
    val baseRows = base.numRows
    val totalRows = rows
    val extraRows = cuts.zipWithIndex.flatMap { case (cut, i) => cut.columns.map(_ -> (baseRows + i)) }
      .groupBy(_._1).map { case (g, rs) => g -> rs.map(_._2).sorted }
    val columns = base.sortedCols.map { case (g, column) =>
        val vector = column.vector.toSparse
        val appended = extraRows.getOrElse(g, Vector.empty)
        g -> column.copy(vector = Vectors.sparse(totalRows, vector.indices ++ appended,
          vector.values ++ Array.fill(appended.size)(1.0)))
      }
    val slackColumns = cuts.indices.map { i =>
      (base.numCols + i) -> ColData(-1, "", 3.toByte, 0.0, 0.0, Vectors.sparse(rows, Array(base.numRows + i), Array(1.0)))
    }
    val sc = base.sortedCols.sparkContext
    val partitioner = new RangeIndexPartitioner(base.numCols + cuts.size, base.sortedCols.getNumPartitions)
    Some(caches.cache(columns.union(sc.parallelize(slackColumns, math.min(cuts.size, partitioner.numPartitions)))
      .repartitionAndSortWithinPartitions(partitioner)))
  }
  val c: DVector = augmented.map(columns => caches.cache(columns.mapPartitions(it =>
    Iterator.single(new DenseVector(it.map(_._2.cost).toArray))))).getOrElse(base.c)
  val AT: DMatrix = augmented.map(columns => caches.cache(columns.map(_._2.vector))).getOrElse(base.AT)
  val b: DenseVector = if (cuts.isEmpty) nodeRhs else {
    val indices = base.intCols.zipWithIndex.map { case (column, i) => column.g -> i }.toMap
    val bounds = cuts.map { cut =>
      cut.rhs - cut.columns.map { g =>
        val i = indices.getOrElse(g, throw new IllegalArgumentException("Cover references a nonintegral column"))
        lower(i) - base.intCols(i).rootLower
      }.sum
    }
    new DenseVector(nodeRhs.values ++ bounds)
  }

  /** Remove auxiliary cut slacks and restore the base column partitioning before reconstruction. */
  def toBase(summary: LP.SolveSummary): LP.SolveSummary = augmented match {
    case None => summary
    case Some(columns) =>
      val baseCount = base.numCols
      try {
        val stripped = if (!summary.candidate.available) base.sortedCols.sparkContext.emptyRDD[DenseVector]
          else {
            val values = columns.zipPartitions(summary.x) { (metadata, blocks) =>
              val xs = blocks.next().values
              metadata.zipWithIndex.collect { case ((g, _), i) if g < baseCount => g -> xs(i) }
            }.repartitionAndSortWithinPartitions(base.sortedCols.partitioner.get)
              .mapPartitions(it => Iterator.single(new DenseVector(it.map(_._2).toArray)))
            val retained = caches.checkpoint(values)
            retained.count(); caches.keep(retained)
          }
        summary.copy(x = stripped, dualCertificate = None)
      } finally {
        summary.x.unpersist(false)
        summary.dualCertificate.foreach(_.unpersist(false))
      }
  }
  override def close(): Unit = caches.close()
}
