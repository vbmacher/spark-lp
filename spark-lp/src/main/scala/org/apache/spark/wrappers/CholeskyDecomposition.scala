package org.apache.spark.wrappers

import com.github.fommil.netlib.LAPACK.{getInstance => lapack}
import org.netlib.util.intW

object CholeskyDecomposition {

  /**
    * Factorizes a symmetric positive-definite matrix stored in packed upper-triangular form.
    * The factor replaces `A` in place.
    */
  def factor(A: Array[Double], n: Int): Array[Double] = {
    val info = new intW(0)
    lapack.dpptrf("U", n, A, info)
    checkFactorization(info)
    A
  }

  /** Solves using a packed upper-triangular Cholesky factor. `bx` is replaced in place. */
  def solveFactored(factor: Array[Double], n: Int, bx: Array[Double]): Array[Double] = {
    val info = new intW(0)
    lapack.dpptrs("U", n, 1, factor, bx, n, info)
    check("dpptrs", info)
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
    check("dpptrf", info)
  }
}
