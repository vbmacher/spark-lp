package org.apache.spark.wrappers

import org.netlib.util.intW

object CholeskyDecomposition {

  /**
    * Factorizes a symmetric positive-definite matrix stored in packed upper-triangular form.
    * Expanding to full column-major storage lets optimized LAPACK implementations use their
    * blocked `dpotrf` path; the returned factor is therefore a full `n * n` matrix.
    */
  def factor(A: Array[Double], n: Int): Array[Double] = {
    val factor = new Array[Double](n * n)
    var column = 0
    var offset = 0
    while (column < n) {
      System.arraycopy(A, offset, factor, column * n, column + 1)
      offset += column + 1
      column += 1
    }
    val info = new intW(0)
    NativeNetlib.lapack.dpotrf("U", n, factor, n, info)
    checkFactorization(info)
    factor
  }

  /** Solves using a full upper-triangular Cholesky factor. `bx` is replaced in place. */
  def solveFactored(factor: Array[Double], n: Int, bx: Array[Double]): Array[Double] = {
    val info = new intW(0)
    NativeNetlib.lapack.dpotrs("U", n, 1, factor, n, bx, n, info)
    check("dpotrs", info)
    bx
  }

  /** Solves a SPD system via Cholesky factorization. Both input arrays are modified in place. */
  def solve(A: Array[Double], bx: Array[Double]): Array[Double] =
    solveFactored(factor(A, bx.length), bx.length, bx)

  private def check(routine: String, info: intW): Unit = {
    if (info.`val` != 0) {
      throw new IllegalArgumentException(s"lapack.$routine returned ${info.`val`}.")
    }
  }

  private def checkFactorization(info: intW): Unit = {
    if (info.`val` > 0) {
      throw new IllegalArgumentException(
        s"Matrix is not positive definite (leading minor ${info.`val`}).")
    }
    check("dpotrf", info)
  }
}
