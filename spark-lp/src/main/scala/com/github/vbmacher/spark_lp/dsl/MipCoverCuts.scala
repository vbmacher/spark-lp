package com.github.vbmacher.spark_lp.dsl

import java.math.{BigDecimal => Decimal}
import com.github.vbmacher.spark_lp.dsl.compiler.IntColumn

private[dsl] sealed trait MipCutScope {
  def permits(lower: Array[Double], upper: Array[Double]): Boolean
}

private[dsl] case object GlobalCut extends MipCutScope {
  override def permits(lower: Array[Double], upper: Array[Double]): Boolean = true
}

/**
  * Node domain within which a locally derived cut remains valid.
  *
  * @param lower integral-column lower bounds at the source node.
  * @param upper integral-column upper bounds at the source node.
  */
private[dsl] final case class LocalCut(
  lower: Vector[Double],
  upper: Vector[Double]
) extends MipCutScope {
  override def permits(lo: Array[Double], hi: Array[Double]): Boolean =
    lo.length == lower.size && hi.length == upper.size && lo.indices.forall(i => lo(i) >= lower(i) && hi(i) <= upper(i))
}

/**
  * Binary cover inequality over the root model's shifted nonnegative columns.
  *
  * Every listed column has coefficient one and their sum must not exceed `rhs`.
  *
  * @param columns global solver-column indices in deterministic order.
  * @param rhs right-hand side of the cover inequality.
  * @param scope tree domain in which the cut is valid.
  */
private[dsl] final case class MipCoverCut(
  columns: Vector[Long],
  rhs: Double,
  scope: MipCutScope
) {
  def signature: (Vector[Long], Double) = columns -> rhs
}

private[dsl] object MipCoverCuts {
  /** Necessary row knapsack inequalities drop nonnegative continuous terms and bound the others.
    * A negative unbounded continuous term prevents the deduction. Exact decimal arithmetic over
    * the binary input doubles plus a conservative acceptance margin protects cover validity.
    */
  def generate(columns: IndexedSeq[IntColumn], rhs: Array[Double], lower: Array[Double], upper: Array[Double],
    negativeContinuousRows: Set[Int], values: Map[Long, Double], global: Boolean,
    config: MipCutsConfig, existing: Set[(Vector[Long], Double)], limit: Int): Vector[MipCoverCut] = {
    if (!config.enabled || limit <= 0) return Vector.empty
    require(lower.length == columns.size && upper.length == columns.size, "Cut bound dimensions must match integral columns")
    require(columns.indices.forall(j => java.lang.Double.isFinite(lower(j)) && java.lang.Double.isFinite(upper(j)) &&
      lower(j) >= columns(j).rootLower && upper(j) <= columns(j).rootUpper && lower(j) <= upper(j)), "Cut domain must lie within root bounds")
    require(!global || columns.indices.forall(j => lower(j) == columns(j).rootLower && upper(j) == columns(j).rootUpper),
      "A global cover must be derived from root bounds")
    val out = Vector.newBuilder[MipCoverCut]
    val seen = scala.collection.mutable.Set.empty[(Vector[Long], Double)] ++ existing
    var count = 0
    rhs.indices.iterator.filterNot(negativeContinuousRows).takeWhile(_ => count < limit).foreach { row =>
      val entries = columns.indices.flatMap { j =>
        val column = columns(j)
        column.rowCoeffs.get(row).orElse(if (column.boundRow == row) Some(1.0) else None).map(a => (j, a))
      }.filter(_._2 != 0.0)
      if (java.lang.Double.isFinite(rhs(row)) && entries.nonEmpty && entries.forall(e => java.lang.Double.isFinite(e._2))) {
        val magnitudes = entries.map(e => math.abs(e._2))
        if (magnitudes.max / magnitudes.min <= config.maxCoefficientRatio) {
          var capacity = new Decimal(rhs(row))
          var scale = new Decimal(1.0).add(capacity.abs())
          entries.foreach { case (j, a) =>
            val contribution = new Decimal(a).multiply(new Decimal(upper(j)).subtract(new Decimal(lower(j))))
            if (a < 0.0) capacity = capacity.subtract(contribution)
            scale = scale.add(contribution.abs())
              .add(new Decimal(a).multiply(new Decimal(lower(j))).abs())
          }
          capacity = capacity.add(scale.multiply(new Decimal(config.numericalTolerance)))
          val candidates = entries.filter { case (j, a) =>
            a > 0.0 && upper(j) - lower(j) == 1.0 && columns(j).rootUpper - columns(j).rootLower == 1.0
          }.sortBy { case (j, a) => (-values.getOrElse(columns(j).g, 0.0), -a, columns(j).g) }
          var sum = Decimal.ZERO
          val selected = scala.collection.mutable.ArrayBuffer.empty[(Int, Double)]
          candidates.iterator.takeWhile(_ => sum.compareTo(capacity) <= 0).foreach { entry =>
            selected += entry; sum = sum.add(new Decimal(entry._2))
          }
          if (selected.nonEmpty && sum.compareTo(capacity) > 0) {
            // Delete redundant members while retaining an exact strict cover proof.
            selected.toVector.reverse.foreach { entry =>
              val without = sum.subtract(new Decimal(entry._2))
              if (without.compareTo(capacity) > 0) {
                selected -= entry
                sum = without
              }
            }
            val activity = selected.map { case (j, _) => values.getOrElse(columns(j).g, 0.0) }.sum
            val bound = selected.size - 1.0
            val rootRhs = bound + selected.map { case (j, _) => lower(j) - columns(j).rootLower }.sum
            val cut = MipCoverCut(selected.map(e => columns(e._1).g).sorted.toVector, rootRhs,
              if (global) GlobalCut else LocalCut(lower.toVector, upper.toVector))
            if (activity > bound + config.numericalTolerance && !seen(cut.signature)) {
              out += cut
              seen += cut.signature
              count += 1
            }
          }
        }
      }
    }
    out.result()
  }
}
