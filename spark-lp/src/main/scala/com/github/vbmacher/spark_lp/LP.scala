package com.github.vbmacher.spark_lp

import com.github.vbmacher.spark_lp.dsl.LpNumericalException
import com.github.vbmacher.spark_lp.vectors.dense_vector.implicits.DenseVectorOps
import com.github.vbmacher.spark_lp.vectors.dmatrix.implicits._
import com.github.vbmacher.spark_lp.vectors.dvector.implicits._
import com.github.vbmacher.spark_lp.vectors.{DMatrix, DVector}
import com.typesafe.scalalogging.LazyLogging
import org.apache.spark.SparkException
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.sql.SparkSession
import org.apache.spark.wrappers.CholeskyDecomposition

import scala.reflect.ClassTag

object LP extends LazyLogging {

  /**
    * Detailed result of one solver run.
    *
    * @param objectiveValue final objective value `c^T x` of the returned iterate.
    * @param x              the returned (last) iterate; primal-feasible only if `converged` is true.
    * @param iterations     the number of completed iterations.
    * @param converged      whether all three convergence conditions were met within tolerance.
    * @param primalResidual final `||A x - b|| / (1 + ||b||)`.
    * @param dualResidual   final `||A^T lambda + s - c|| / (1 + ||c||)`.
    * @param dualityGap     final `|c^T x - b^T lambda| / (1 + |b^T lambda|)`.
    */
  private[spark_lp] case class SolveSummary(
    objectiveValue: Double,
    x: DVector,
    iterations: Int,
    converged: Boolean,
    primalResidual: Double,
    dualResidual: Double,
    dualityGap: Double)

  /**
    * Computes the optimal value and the corresponding vector for a LP problem.
    *
    * @param c         the objective coefficient DVector.
    * @param AT        the constraint DMatrix (transposed).
    * @param b         the constraint values.
    * @param tolerance convergence tolerance.
    * @param maxIter   maximum number of iterations if it did not converge.
    * @param etaIter   step size. Shrinkage value.
    * @param valueCap  value cap
    * @param eps       numerical threshold
    * @param spark     a SparkSession instance.
    * @return optimal value and the corresponding solution vector.
    */
  def solve(
    c: DVector,
    AT: DMatrix,
    b: DenseVector,
    tolerance: Double = 1e-8,
    maxIter: Int = 50,
    etaIter: Double = 0.999,
    valueCap: Double = 1e20,
    eps: Double = 1e-20
  )(implicit spark: SparkSession): (Double, DVector) = {
    val summary = solveSummary(c, AT, b, tolerance, maxIter, etaIter, valueCap, eps)
    (summary.objectiveValue, summary.x)
  }

  /**
    * Solve variant that also reports the iteration count, the final convergence flag and the final
    * residuals the solver computes each iteration. Numerical failures (a non-positive-definite
    * Gramian during initialization or an iteration's Cholesky step, or a zero iterate element) are
    * wrapped in [[com.github.vbmacher.spark_lp.dsl.LpNumericalException]] naming the phase and the
    * count of completed iterations.
    */
  private[spark_lp] def solveSummary(
    c: DVector,
    AT: DMatrix,
    b: DenseVector,
    tolerance: Double = 1e-8,
    maxIter: Int = 50,
    etaIter: Double = 0.999,
    valueCap: Double = 1e20,
    eps: Double = 1e-20
  )(implicit spark: SparkSession): SolveSummary = {
    val valueCapSquareRoot = math.sqrt(valueCap)

    c.cacheIfNoStorageLevel()

    // run initialization
    val init =
      try {
        Initialize.init(c, AT, b)
      } catch {
        case e if isNumericalFailure(e) => throw numericalFailure("initialization", 0, e)
      }

    var x = init.x
    x.cacheIfNoStorageLevel()

    var lambda = init.lambda
    var lambdaBroadcast = spark.sparkContext.broadcast(lambda)

    var s = init.s
    s.cacheIfNoStorageLevel()

    // set number of unknown in lp
    val unknowns = init.rows

    // set number of equations in lp
    val equations = init.cols

    // duality gap parameter
    val mu = x.dot(s) / unknowns

    // initial objective value
    var cTx = Double.PositiveInfinity

    var converged = false
    var iter = 1

    var primalResidual = Double.NaN
    var dualResidual = Double.NaN
    var dualityGap = Double.NaN

    var dLambdaAffBroadcast: Broadcast[DenseVector] = null
    var dLambdaBroadcast: Broadcast[DenseVector] = null

    while (!converged && iter <= maxIter) {
      logger.info(s"LP iteration: $iter")

      try {
      // A^T * x - b
      var rb = AT.adjointProduct(x).combine(1.0, -1.0, b)

      // A * lambda + s - c
      var rc = AT.product(lambdaBroadcast).combine(1.0, 1.0, s.diff(c))
      rc.cacheIfNoStorageLevel()

      // D = X^(1/2) * S^(-1/2)
      val D = x.mapElements {
        case a if math.abs(a) < eps => math.signum(a) * valueCapSquareRoot
        case a if a >= 0.0 => math.sqrt(a)
      }.entrywiseProd(
        s.mapElements {
          case a if 0 < a && a < eps => valueCapSquareRoot
          case a if a >= eps => 1 / math.sqrt(a)
        }
      )

      val D2 = x.entrywiseProd(
        s.mapElements {
          case a if math.abs(a) < eps => math.signum(a) * valueCap
          case a if math.abs(a) >= eps => math.pow(a, -1)
        }
      )
      D2.cacheIfNoStorageLevel()

      // solve (14.30) for (dxAff, dLambdaAff, dsAff)
      // 1) solve for A^T D2 A dLambdaAff = -rb + A^T * (-D^2 * rc + x)
      val DA = D.diagonalProduct(AT)

      // compute Gramian matrix A^T A
      val ATD2A = DA.gramianMatrix(equations)
      val ATD2rcx = AT.adjointProduct(x.diff(D2.entrywiseProd(rc)))

      val dLambdaAffRightSide = ATD2rcx.combine(1.0, -1.0, rb)
      val dLambdaAffArray = dLambdaAffRightSide.toArray

      val upTriArray = ATD2A.data
      val upTriArrayCopy = upTriArray.clone() // capturing side effects

      // upTriArrayCopy  must be "positive definite".
      // That means:
      //   x^T A x > 0    where x != 0
      CholeskyDecomposition.solve(upTriArrayCopy, dLambdaAffArray) // inplace dLambdaAffArray
      val dLambdaAff = new DenseVector(dLambdaAffArray)
      dLambdaAffBroadcast = rebroadcast(dLambdaAffBroadcast, dLambdaAff)

      // 2) dsAff = -rc - A * dLambdaAff
      val jj = AT.product(dLambdaAffBroadcast).cache()
      jj.count()
      val dsAff = rc.combine(-1.0, -1.0,jj)

      // 3) dxAff = -x - D^2 * dsAff
      val dxAff = x.combine(-1.0, -1.0, D2.entrywiseProd(dsAff))

      // Calculate following Doubles alphaPriAff, alphaDualAff, muAff (14.32), (14.33)
      val alphaPriAff = math.min(1.0, x.entrywiseNegDiv(dxAff).minValue)
      val alphaDualAff = math.min(1.0, s.entrywiseNegDiv(dsAff).minValue)
      val muAff = {
        val nx = x.combine(1.0, alphaPriAff, dxAff)
        val ns = s.combine(1.0, alphaDualAff, dsAff)
        nx.dot(ns) / unknowns
      }

      val sigma = math.pow(muAff / mu, 3) // heuristic
      logger.info(s"sigma = $sigma")

      // Solve (14.35) for (dx, dLambda, ds)
      // 1) A^T D2 A dLambda = -rb + A^T * D2 *(-rc + s + X^(-1) dXAff dSAff e - sigma mu X^(-1)e)
      val xInv = x.mapElements {
        case a if 0 < math.abs(a) && math.abs(a) < eps => math.signum(a) * valueCap
        case a if a >= eps => math.pow(a, -1)
        case _ => throw new IllegalArgumentException(s"Found zero element in X")
      }

      val xInvdXAffdsAff = xInv.entrywiseProd(dxAff.entrywiseProd(dsAff))
      val dLambdaRightSide = rb.combine(
        -1.0, 1.0,
        AT.adjointProduct(
          D2.entrywiseProd(
            s.diff(rc)
              .combine(1.0, 1.0, xInvdXAffdsAff)
              .combine(1.0, -1.0 * sigma * mu, xInv))))

      val dLambdaArray = CholeskyDecomposition.solve(upTriArray, dLambdaRightSide.toArray)
      val dLambda = new DenseVector(dLambdaArray)
      dLambdaBroadcast = rebroadcast(dLambdaBroadcast, dLambda)

      // 2) ds = -rc - A * dLambda
      val jj1 = AT.product(dLambdaBroadcast).cache()
      jj1.count()
      val ds = rc.combine(-1.0, -1.0, jj1)

      // 3) dx = -D^2 dS e - x - S^(-1) dXAff dSAff e + sigma mu S^(-1) e
      val sInv = s.mapElements {
        case a if math.abs(a) < eps => math.signum(a) * valueCap
        case a if math.abs(a) >= eps => math.pow(a, -1)
      }

      val sInvdXAffdsAff = sInv.entrywiseProd(dxAff.entrywiseProd(dsAff))
      val dx = D2
        .entrywiseProd(ds)
        .combine(-1.0, -1.0, x)
        .combine(1.0, -1.0, sInvdXAffdsAff)
        .combine(1.0, sigma * mu, sInv)

      val alphaPrimalIterMax = x.entrywiseNegDiv(dx).minValue
      val alphaDualIterMax = s.entrywiseNegDiv(ds).minValue
      val alphaPrimalIter = math.min(1.0, etaIter * alphaPrimalIterMax)
      val alphaDualIter = math.min(1.0, etaIter * alphaDualIterMax)

      // x = x + alphaPriIter * dx
      x = x.combine(1.0, alphaPrimalIter, dx)
      x.localCheckpoint()

      // lambda = lambda + alphaDualIter * dLambda
      lambda = new DenseVector((lambdaBroadcast.value.toBreeze + alphaDualIter * dLambda.toBreeze).toArray)
      lambdaBroadcast = rebroadcast(lambdaBroadcast, lambda)

      // s = s + alphaDualIter * ds
      s = s.combine(1.0, alphaDualIter, ds)
      s.localCheckpoint()

      rb = AT.adjointProduct(x).combine(1.0, -1.0, b)
      val previousRc = rc
      rc = AT.product(lambdaBroadcast).combine(1.0, 1.0, s.diff(c)).cache()
      rc.count()
      cTx = c.dot(x)

      previousRc.unpersist(blocking = false)
      jj.unpersist(blocking = false)
      jj1.unpersist(blocking = false)
      D2.unpersist(blocking = false)

      val bTlambda = b.dot(lambda)
      val covg1 = math.sqrt(rb.dot(rb)) / (1 + math.sqrt(b.dot(b)))
      val covg2 = math.sqrt(rc.dot(rc)) / (1 + math.sqrt(c.dot(c)))
      val covg3 = math.abs(cTx - bTlambda) / (1 + math.abs(bTlambda))

      primalResidual = covg1
      dualResidual = covg2
      dualityGap = covg3

      converged = (covg1 < tolerance) && (covg2 < tolerance) && (covg3 < tolerance)

      rc.unpersist(blocking = false)

      logger.info(s"\n1. convergence condition: $covg1" +
        s"\n2. convergence condition: $covg2" +
        s"\n3. convergence condition: $covg3" +
        s"\nConverged = $converged\n" +
        s"\ncTx: $cTx" +
        s"\nb dot lambda: $bTlambda")

      } catch {
        case e if isNumericalFailure(e) => throw numericalFailure(s"iteration $iter", iter - 1, e)
      }
      iter += 1
    }

    if (dLambdaAffBroadcast != null) dLambdaAffBroadcast.unpersist(blocking = false)
    if (dLambdaBroadcast != null) dLambdaBroadcast.unpersist(blocking = false)
    lambdaBroadcast.unpersist(blocking = false)

    SolveSummary(
      objectiveValue = cTx,
      x = x,
      iterations = iter - 1,
      converged = converged,
      primalResidual = primalResidual,
      dualResidual = dualResidual,
      dualityGap = dualityGap)
  }

  /** Failure modes of the linear algebra underneath the solver, possibly wrapped by Spark. */
  private def isNumericalFailure(e: Throwable): Boolean = e match {
    case _: MatchError | _: IllegalArgumentException | _: IllegalStateException | _: AssertionError => true
    case e: SparkException => e.getCause != null && isNumericalFailure(e.getCause)
    case _ => false
  }

  private def numericalFailure(phase: String, completedIterations: Int, cause: Throwable): LpNumericalException = {
    val detail = Option(cause.getMessage).getOrElse(cause.toString)
    new LpNumericalException(
      phase = phase,
      completedIterations = completedIterations,
      message = s"Numerical failure during $phase (completed iterations: $completedIterations): $detail. " +
        "The solver requires a constraint matrix with full row rank (linearly independent constraint rows) " +
        "and strictly interior iterates; a non-positive-definite Gramian or a zero iterate element " +
        "indicates this precondition is violated.",
      cause = cause)
  }

  def rebroadcast[T: ClassTag](old: Broadcast[T], v: T)(implicit spark: SparkSession): Broadcast[T] = {
    if (old != null) old.unpersist(blocking = false)
    spark.sparkContext.broadcast(v)
  }
}
