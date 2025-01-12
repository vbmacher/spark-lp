package com.github.vbmacher.spark_lp

import com.github.vbmacher.spark_lp.vectors.dense_vector.implicits.DenseVectorOps
import com.github.vbmacher.spark_lp.vectors.dmatrix.implicits._
import com.github.vbmacher.spark_lp.vectors.dvector.implicits._
import com.github.vbmacher.spark_lp.vectors.{DMatrix, DVector}
import com.typesafe.scalalogging.LazyLogging
import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.sql.SparkSession
import org.apache.spark.wrappers.CholeskyDecomposition

object LP extends LazyLogging {

  /**
    * Computes the optimal value and the corresponding vector for a LP problem.
    *
    * @param c         the objective coefficient DVector.
    * @param A         the constraint DMatrix.
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
    A: DMatrix,
    b: DenseVector,
    tolerance: Double = 1e-8,
    maxIter: Int = 50,
    etaIter: Double = 0.999,
    valueCap: Double = 1e20,
    eps: Double = 1e-20
  )(implicit spark: SparkSession): (Double, DVector) = {
    val valueCapSquareRoot = math.sqrt(valueCap)

    c.cacheIfNoStorageLevel()

    // run initialization
    val init = Initialize.init(c, A, b)

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

    while (!converged && iter <= maxIter) {
      println("-----------------------------")
      println(s"iteration $iter")

      // A^T * x - b
      var rb = A.adjointProduct(x).combine(1.0, -1.0, b)

      // A * lambda + s - c
      var rc = A.product(lambdaBroadcast).combine(1.0, 1.0, s.diff(c))
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
      val DA = D.diagonalProduct(A)

      // compute Gramian matrix A^T A
      val ATD2A = DA.gramianMatrix(equations)
      val ATD2rcx = A.adjointProduct(x.diff(D2.entrywiseProd(rc)))

      val dLambdaAffRightSide = ATD2rcx.combine(1.0, -1.0, rb)
      val dLambdaAffArray = dLambdaAffRightSide.toArray

      val upTriArray = ATD2A.data
      val upTriArrayCopy = upTriArray.clone() // capturing side effects

      // upTriArrayCopy  must be "positive definite".
      // That means:
      //   x^T A x > 0    where x != 0
      CholeskyDecomposition.solve(upTriArrayCopy, dLambdaAffArray) // inplace dLambdaAffArray
      val dLambdaAff = new DenseVector(dLambdaAffArray)
      val dLambdaAffBroadcast = spark.sparkContext.broadcast(dLambdaAff)

      // 2) dsAff = -rc - A * dLambdaAff
      val dsAff = rc.combine(-1.0, -1.0, A.product(dLambdaAffBroadcast))

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
      println(s"sigma = $sigma")

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
        A.adjointProduct(
          D2.entrywiseProd(
            s.diff(rc)
              .combine(1.0, 1.0, xInvdXAffdsAff)
              .combine(1.0, -1.0 * sigma * mu, xInv))))

      val dLambdaArray = CholeskyDecomposition.solve(upTriArray, dLambdaRightSide.toArray)
      val dLambda = new DenseVector(dLambdaArray)
      val dLambdaBroadcast = spark.sparkContext.broadcast(dLambda)

      // 2) ds = -rc - A * dLambda
      val ds = rc.combine(-1.0, -1.0, A.product(dLambdaBroadcast))

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
      lambdaBroadcast = spark.sparkContext.broadcast(lambda)

      // s = s + alphaDualIter * ds
      s = s.combine(1.0, alphaDualIter, ds)
      s.localCheckpoint()

      rb = A.adjointProduct(x).combine(1.0, -1.0, b)
      rc = A.product(lambdaBroadcast).combine(1.0, 1.0, s.diff(c))
      cTx = c.dot(x)

      val bTlambda = b.dot(lambdaBroadcast.value)
      val covg1 = math.sqrt(rb.dot(rb)) / (1 + math.sqrt(b.dot(b)))
      val covg2 = math.sqrt(rc.dot(rc)) / (1 + math.sqrt(c.dot(c)))
      val covg3 = math.abs(cTx - bTlambda) / (1 + math.abs(bTlambda))

      converged = (covg1 < tolerance) && (covg2 < tolerance) && (covg3 < tolerance)

      println(s"1. convergence condition: $covg1")
      println(s"2. convergence condition: $covg2")
      println(s"3. convergence condition: $covg3")
      println(s"cTx: $cTx")
      println(s"b dot lambda: $bTlambda")
      iter += 1
    }

    (cTx, x)
  }
}