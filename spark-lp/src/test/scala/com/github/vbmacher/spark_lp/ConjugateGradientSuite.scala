package com.github.vbmacher.spark_lp

import org.apache.spark.wrappers.{CholeskyDecomposition, ConjugateGradient}
import org.scalatest.funsuite.AnyFunSuite

class ConjugateGradientSuite extends AnyFunSuite {
  private def product(x: Array[Double]): Array[Double] =
    Array(4.0 * x(0) + x(1), x(0) + 3.0 * x(1))

  test("BLAS CG matches LAPACK and preserves the right-hand side and warm start") {
    val rhs = Array(1.0, 2.0)
    val initial = Array(0.1, 0.2)

    val referenceSolution = CholeskyDecomposition.solve(Array(4.0, 1.0, 3.0), rhs.clone())
    val cg = new ConjugateGradient(rhs, product, 1e-12, initialGuess = Some(initial))

    cg.solve(_.clone(), 10)
    val solution = cg.solution
    val squaredResidual = product(solution).zip(rhs).map { case (actual, expected) =>
      val difference = actual - expected
      difference * difference
    }.sum

    assert(cg.converged)
    assert(solution.zip(referenceSolution).forall { case (actual, expected) => math.abs(actual - expected) < 1e-12 })
    assert(math.sqrt(squaredResidual) <= cg.targetNorm)
    assert(rhs.sameElements(Array(1.0, 2.0)))
    assert(initial.sameElements(Array(0.1, 0.2)))

    // Editing a returned solution must leave the solver's stored result intact.
    solution(0) = 100.0

    assert(math.abs(cg.solution(0) - referenceSolution(0)) < 1e-12)
  }

  test("a stronger preconditioner resumes an exhausted solve with cumulative counters") {
    val cg = new ConjugateGradient(Array(1.0, 2.0), product, 1e-12)

    cg.solve(_.clone(), 1)

    assert(!cg.converged)
    assert(cg.iterations == 1)

    val factor = CholeskyDecomposition.factor(Array(4.0, 1.0, 3.0), 2)

    cg.solve(r => CholeskyDecomposition.solveFactored(factor, 2, r.clone()), 1)

    assert(cg.converged)
    assert(cg.iterations == 2)
    assert(cg.restarts == 1)
  }

  test("a zero right-hand side returns the zero solution without applying the matrix") {
    val cg = new ConjugateGradient(Array(0.0, 0.0), _ => fail("Zero RHS needs no product"),
      1e-12, initialGuess = Some(Array(1.0, 1.0)))

    cg.solve(_.clone(), 10)

    assert(cg.converged)
    assert(cg.iterations == 0)
    assert(cg.solution.sameElements(Array(0.0, 0.0)))
  }

  test("an exact warm start converges without applying the preconditioner") {
    val cg = new ConjugateGradient(Array(6.0, 7.0), product, 1e-12,
      initialGuess = Some(Array(1.0, 2.0)))

    cg.solve(_ => fail("Exact warm start needs no preconditioner"), 10)

    assert(cg.converged)
    assert(cg.iterations == 0)
  }

  test("CG rejects nonpositive and non-finite curvature") {
    Seq(-1.0, Double.NaN, Double.PositiveInfinity).foreach { curvature =>
      val cg = new ConjugateGradient(Array(1.0), x => Array(curvature * x(0)), 1e-12)

      withClue(s"curvature $curvature: ") {
        intercept[IllegalStateException](cg.solve(_.clone(), 10))
      }
    }
  }

  test("absolute tolerance uses the true initial residual") {
    val cg = new ConjugateGradient(Array(1e-8, 0.0), product, 1e-12, absoluteTolerance = 1e-7)

    cg.solve(_ => fail("Initial residual already meets tolerance"), 10)

    assert(cg.converged)
    assert(cg.iterations == 0)
  }

  test("component tolerances keep a large RHS from hiding an unsolved small row") {
    val tolerances = Array(100.0, 1e-8)
    val cg = new ConjugateGradient(Array(1e12, 100.0), _.clone(), 1e-10,
      initialGuess = Some(Array(1e12, 0.0)), componentTolerances = Some(tolerances))
    tolerances(1) = 100.0
    assert(!cg.converged)

    cg.solve(_.clone(), 2)

    assert(cg.converged)
    assert(cg.solution.sameElements(Array(1e12, 100.0)))
    assert(cg.iterations == 1)
  }
}
