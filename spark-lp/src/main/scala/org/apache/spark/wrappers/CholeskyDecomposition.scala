package org.apache.spark.wrappers

import org.apache.spark.mllib.linalg.{CholeskyDecomposition => ICholeskyDecomposition}

object CholeskyDecomposition {

  /**
    * Solves a symmetric positive definite linear system via Cholesky factorization.
    * The input arguments are modified in-place to store the factorization and the solution.
    *
    * @param A  the upper triangular part of A
    * @param bx right-hand side
    * @return the solution array
    */
  def solve(A: Array[Double], bx: Array[Double]): Array[Double] = {
    ICholeskyDecomposition.solve(A, bx)
  }
}
