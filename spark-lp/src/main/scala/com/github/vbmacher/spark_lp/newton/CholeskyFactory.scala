package com.github.vbmacher.spark_lp.newton

import com.github.vbmacher.spark_lp.{SolveMonitor, SolvePhase, WorkProgress}
import com.github.vbmacher.spark_lp.vectors.DMatrix
import com.github.vbmacher.spark_lp.vectors.dmatrix.implicits._
import com.github.vbmacher.spark_lp.vectors.dvector.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.wrappers.CholeskyDecomposition

/**
  * Direct [[NewtonSystemFactory]]. Splits the weighted Gramian
  * into its exact contiguous diagonal blocks, factorizes each once on the driver and solves
  * every right-hand side by back-substitution. The injected `monitor` reports the Cholesky
  * phase and per-block progress and supplies the cooperative cancellation checks.
  */
private[spark_lp] final class CholeskyFactory(monitor: SolveMonitor = new SolveMonitor())
  extends NewtonSystemFactory with LazyLogging {

  import CholeskyFactory.blockEnds
  override def check(): Unit = monitor.check()

  override def build(B: DMatrix, m: Int, weights: Option[Weights]): NewtonSystem = {
    monitor.phase(SolvePhase.SystemSetup)
    val scaled = weights.map(_.sqrt.diagonalProduct(B)).getOrElse(B)
    val packedGramian = scaled.gramianMatrix(m).data
    val ends = blockEnds(packedGramian, m)
    val starts = Array(0) ++ ends.dropRight(1)
    val factors = starts.zip(ends).zipWithIndex.map { case ((start, end), blockIndex) =>
      monitor.check()
      val size = end - start
      val packed = if (size == m) packedGramian else {
        val block = new Array[Double]((size.toLong * (size + 1) / 2).toInt)
        var col = 0
        var offset = 0
        while (col < size) {
          val globalCol = start + col
          val source = (globalCol.toLong * (globalCol + 1) / 2 + start).toInt
          System.arraycopy(packedGramian, source, block, offset, col + 1)
          offset += col + 1
          col += 1
        }
        block
      }
      val factor = CholeskyDecomposition.factor(packed, size)
      monitor.report(SolvePhase.SystemSetup,
        work = Some(WorkProgress(blockIndex + 1, total = Some(ends.length))))
      (start, size, factor)
    }
    logger.debug(s"Cholesky blocks=${ends.length} largest=${factors.map(_._2).max} rows=$m")

    new NewtonSystem {
      override def solve(rhs: DenseVector, absTolerance: Double): DenseVector = {
        monitor.phase(SolvePhase.InnerSolve)
        // dpptrs overwrites only the right-hand side; preserve the caller's vector.
        val solution = rhs.values.clone()
        factors.zipWithIndex.foreach { case ((start, size, factor), blockIndex) =>
          monitor.check()
          val block = java.util.Arrays.copyOfRange(solution, start, start + size)
          CholeskyDecomposition.solveFactored(factor, size, block)
          System.arraycopy(block, 0, solution, start, size)
          monitor.report(SolvePhase.InnerSolve,
            work = Some(WorkProgress(blockIndex + 1, total = Some(factors.length))))
        }
        monitor.check()
        new DenseVector(solution)
      }

      override def release(): Unit = ()
    }
  }
}

private[spark_lp] object CholeskyFactory {
  /** Exclusive ends of exact contiguous diagonal blocks of a packed symmetric matrix.
    * Every nonzero off-diagonal entry joins its entire row/column interval. No numerical
    * threshold is used: even a tiny coupling prevents a split. General matrices stay whole.
    * Recomputed for each Newton system, so changes in weights cannot leave stale blocks.
    */
  def blockEnds(packed: Array[Double], m: Int): Array[Int] = {
    val reach = Array.tabulate(m)(identity)
    var col = 0
    var offset = 0
    while (col < m) {
      var row = 0
      while (row < col) {
        if (packed(offset + row) != 0.0) reach(row) = col
        row += 1
      }
      offset += col + 1
      col += 1
    }
    val ends = scala.collection.mutable.ArrayBuffer.empty[Int]
    var end = 0
    var row = 0
    while (row < m) {
      end = math.max(end, reach(row))
      if (row == end) ends += row + 1
      row += 1
    }
    ends.toArray
  }
}
