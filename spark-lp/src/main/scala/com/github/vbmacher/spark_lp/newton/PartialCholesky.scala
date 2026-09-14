package com.github.vbmacher.spark_lp.newton

import breeze.linalg.{DenseVector => BDV}

/** Sequential complete diagonal pivoting, fetching only one Gramian column per pivot.
  * Storage is O(m * rank), plus O(m) scratch. Small Schur pivots stop factorization;
  * only the preconditioner's remaining diagonal is floored against roundoff. The
  * regularized operator itself is never silently perturbed after RHS construction.
  */
private[spark_lp] final class PartialCholesky(
  diagonal: Array[Double],
  requestedRank: Int,
  column: Int => Array[Double],
  dualRegularization: Double,
  onPivot: Int => Unit = _ => ()) {

  private val m = diagonal.length
  require(diagonal.forall(d => d > 0.0 && !d.isInfinite), "Invalid regularized Gramian diagonal")
  private val floor = math.max(dualRegularization, 64.0 * math.ulp(diagonal.max))
  private val schur = diagonal.clone()
  private val selected = new Array[Boolean](m)
  private val pivots = scala.collection.mutable.ArrayBuffer.empty[Int]
  private val factors = scala.collection.mutable.ArrayBuffer.empty[Array[Double]]
  private var stopped = false
  private var rest = Array.empty[Int]
  extendTo(requestedRank)

  /** Continue the same diagonal-pivoted factorization without fetching old columns again.
    * The raw Schur diagonal is retained for future pivots; flooring is applied only
    * when using its diagonal approximation in [[apply]].
    */
  def extendTo(rank: Int): Unit = {
    require(rank >= 0, "Preconditioner rank must be nonnegative")
    while (pivots.length < math.min(m, rank) && !stopped) {
      var pivot = -1
      var i = 0
      while (i < m) {
        if (!selected(i) && (pivot < 0 || schur(i) > schur(pivot))) pivot = i
        i += 1
      }
      if (schur(pivot) <= floor) stopped = true
      else {
        val l = column(pivot)
        require(l.length == m && l.forall(v => !v.isNaN && !v.isInfinite), "Invalid Gramian column")
        val root = math.sqrt(schur(pivot))
        i = 0
        while (i < m) {
          if (!selected(i) && i != pivot) {
            var j = 0
            while (j < factors.length) {
              l(i) -= factors(j)(i) * factors(j)(pivot)
              j += 1
            }
            l(i) /= root
            schur(i) -= l(i) * l(i)
            if (schur(i) < -floor || schur(i).isNaN || schur(i).isInfinite)
              throw new IllegalStateException(s"Unstable Schur pivot at row $i: ${schur(i)}")
          } else l(i) = 0.0
          i += 1
        }
        l(pivot) = root
        selected(pivot) = true
        pivots += pivot
        factors += l
        onPivot(pivots.length)
      }
    }
    rest = (0 until m).filterNot(selected).toArray
  }

  /** Pivot rows selected so far, in factorization order; its length is the current rank. */
  def indices: Array[Int] = pivots.toArray

  /** Number of pivots actually built, which can be below the requested rank. */
  def rank: Int = pivots.length

  /** Applies the preconditioner, returning `M^(-1) r`. Pivoted rows use the stored triangular
    * factors (forward then back substitution); the remaining rows use the floored Schur diagonal.
    */
  def apply(r: BDV[Double]): BDV[Double] = {
    val k = pivots.length
    val z = r.toArray
    var j = 0
    while (j < k) {
      val pivot = pivots(j)
      z(pivot) /= factors(j)(pivot)
      var i = 0
      while (i < m) {
        if (i != pivot) z(i) -= factors(j)(i) * z(pivot)
        i += 1
      }
      j += 1
    }
    rest.foreach(i => z(i) /= math.max(floor, schur(i)))
    j = k - 1
    while (j >= 0) {
      val pivot = pivots(j)
      var i = 0
      while (i < m) {
        if (i != pivot) z(pivot) -= factors(j)(i) * z(i)
        i += 1
      }
      z(pivot) /= factors(j)(pivot)
      j -= 1
    }
    new BDV(z)
  }
}
