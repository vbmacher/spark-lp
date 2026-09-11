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
    * Compute the heuristic starting points with the supplied normal-equations solver:
    * `B^T B` for Cholesky, `B^T W0 B + Rd` for regularized matrix-free CG.
    *
    * @param c       the objective coefficient DVector.
    * @param A       the constraint DMatrix.
    * @param b       the constraint values.
    * @param factory the normal-equations solver and its initialization regularization.
    * @return starting points (x, lambda, s) and the computed dimensions of rows DMatrix (n, m).
    */
  private[spark_lp] def init(
    c: DVector,
    A: DMatrix,
    b: DenseVector,
    factory: NewtonSystemFactory): Initialization = {
    factory.check()
    require(!A.isEmpty(), "Matrix A (constraint matrix) must not be empty")

    c.cacheIfNoStorageLevel()
    if (A.getStorageLevel == StorageLevel.NONE) A.cache()

    factory.check()
    val rows = A.count()
    factory.check()
    val columns = A.first().size
    require(columns == b.size, s"Constraint vectors have size $columns but b has size ${b.size}")

    logger.debug(s"Number of unknowns: $rows; number of equations: $columns")

    // G = B^T W0 B + Rd, W0 = I/(1+Rp); Rp=Rd=0 for the direct reference.
    factory.check()
    val system = factory.build(A, columns, weights = None)
    try {
      // xTilda = W0 * B * G^(-1) * b
      val scale = 1.0 / (1.0 + factory.primalRegularization)
      val xTilda = A.product(system.solve(b)).mapElements(_ * scale)

      // deltax = max(-1.5 * xTilda.min(), 0)
      factory.check()
      val deltax: Double = math.max(-1.5 * xTilda.minValue, 0)

      // xHat = xTilda + deltax * e
      val xHat: DVector = xTilda.mapElements(a => a + deltax)

      // lambdaTilda = G^(-1) * B^T * W0 * c
      factory.check()
      val lambdaTilda: DenseVector = system.solve(A.adjointProduct(c.mapElements(_ * scale)))

      // sTilda = c - B * lambdaTilda
      val sTilda: DVector = c.diff(A.product(lambdaTilda))

      // deltas = max(-1.5 * sTilda.min(), 0)
      factory.check()
      val deltas: Double = math.max(-1.5 * sTilda.minValue, 0)

      // sHat = sTilda + deltas * e
      val sHat: DVector = sTilda.mapElements(a => a + deltas)

      // deltaxHat = 0.5 * (xHat, sHat) / (e, sHat)
      factory.check()
      val complementarity = xHat.dot(sHat)
      factory.check()
      val deltaxHat: Double = if (complementarity == 0.0) 1.0 else 0.5 * complementarity / sHat.sum()

      // deltasHat = 0.5 * (xHat, sHat) / (e, xHat)
      factory.check()
      val deltasHat: Double = if (complementarity == 0.0) 1.0 else 0.5 * complementarity / xHat.sum()

      // x = xHat + deltaxHat * e
      val x = xHat.mapElements(a => a + deltaxHat)

      // lambda = lambdaTilda
      // s = sHat + deltasHat * e
      val s = sHat.mapElements(a => a + deltasHat)

      factory.check()
      Initialization(x = x, lambda = lambdaTilda, s = s, rows = rows, cols = columns)
    } finally {
      system.release()
    }
  }
}
