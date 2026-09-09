package com.github.vbmacher.spark_lp

import com.github.vbmacher.spark_lp.newton.NewtonSystemFactory
import com.github.vbmacher.spark_lp.vectors.dmatrix.implicits._
import com.github.vbmacher.spark_lp.vectors.dvector.implicits._
import com.github.vbmacher.spark_lp.vectors.{DMatrix, DVector}
import com.typesafe.scalalogging.LazyLogging
import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.storage.StorageLevel

object Initialize extends LazyLogging {

  case class Initialization(
    x: DVector,
    lambda: DenseVector,
    s: DVector,
    rows: Long,
    cols: Int
  )

  /**
    * Compute the heuristic starting points.
    *
    * Ax + s = b
    *
    * @param c the objective coefficient DVector.
    * @param A the constraint DMatrix.
    * @param b the constraint values.
    * @return starting points (x, lambda, s) and the computed dimensions of rows DMatrix (n, m).
    */
  def init(c: DVector, A: DMatrix, b: DenseVector): Initialization =
    init(c, A, b, newton.CholeskyFactory)

  /**
    * Compute the heuristic starting points, solving the two `B^T B` systems with the supplied
    * normal-equations solver (driver-local Cholesky or the matrix-free conjugate gradient).
    *
    * @param c       the objective coefficient DVector.
    * @param A       the constraint DMatrix.
    * @param b       the constraint values.
    * @param factory the normal-equations solver to use for the `B^T B` systems.
    * @return starting points (x, lambda, s) and the computed dimensions of rows DMatrix (n, m).
    */
  private[spark_lp] def init(
    c: DVector,
    A: DMatrix,
    b: DenseVector,
    factory: NewtonSystemFactory): Initialization = {
    require(!A.isEmpty(), "Matrix A (constraint matrix) must not be empty")

    c.cacheIfNoStorageLevel()
    if (A.getStorageLevel == StorageLevel.NONE) A.cache()

    val rows = A.count()
    val columns = A.first().size
    require(columns == b.size, s"Constraint vectors have size $columns but b has size ${b.size}")

    logger.debug(s"Number of unknowns: $rows; number of equations: $columns")

    // Solver for B^T B systems (positive definite, if A columns are linearly independent)
    val system = factory.build(A, columns, weights = None)
    try {
      // xTilda = B * (B^T B)^(-1) * b
      val xTilda = A.product(system.solve(b))

      // deltax = max(-1.5 * xTilda.min(), 0)
      val deltax: Double = math.max(-1.5 * xTilda.minValue, 0)

      // xHat = xTilda + deltax * e
      val xHat: DVector = xTilda.mapElements(a => a + deltax)

      // lambdaTilda = (B^T B)^(-1) * B^T * c
      val lambdaTilda: DenseVector = system.solve(A.adjointProduct(c))

      // sTilda = c - B * lambdaTilda
      val sTilda: DVector = c.diff(A.product(lambdaTilda))

      // deltas = max(-1.5 * sTilda.min(), 0)
      val deltas: Double = math.max(-1.5 * sTilda.minValue, 0)

      // sHat = sTilda + deltas * e
      val sHat: DVector = sTilda.mapElements(a => a + deltas)

      // deltaxHat = 0.5 * (xHat, sHat) / (e, sHat)
      val complementarity = xHat.dot(sHat)
      val deltaxHat: Double = if (complementarity == 0.0) 1.0 else 0.5 * complementarity / sHat.sum()

      // deltasHat = 0.5 * (xHat, sHat) / (e, xHat)
      val deltasHat: Double = if (complementarity == 0.0) 1.0 else 0.5 * complementarity / xHat.sum()

      // x = xHat + deltaxHat * e
      val x = xHat.mapElements(a => a + deltaxHat)

      // lambda = lambdaTilda
      // s = sHat + deltasHat * e
      val s = sHat.mapElements(a => a + deltasHat)

      Initialization(x = x, lambda = lambdaTilda, s = s, rows = rows, cols = columns)
    } finally {
      system.release()
    }
  }
}
