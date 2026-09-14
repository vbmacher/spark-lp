package com.github.vbmacher.spark_lp

import com.github.vbmacher.spark_lp.newton.{CgConfig, NewtonSolver}

import breeze.linalg.{DenseMatrix => BDM, DenseVector => BDV, diag, norm}
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.scalatest.funsuite.AnyFunSuite

class NewtonSystemSuite extends AnyFunSuite with DataFrameSuiteBase {
  test("partial Cholesky agrees with dense Schur elimination at every requested rank") {
    val g = BDM((10.0, 9.0, 0.0, 0.1), (9.0, 9.0, 0.2, 0.0),
      (0.0, 0.2, 8.0, 0.5), (0.1, 0.0, 0.5, 2.0))
    (0 to 4).foreach { rank =>
      val schur = g.copy
      val expected = scala.collection.mutable.ArrayBuffer.empty[Int]
      val factors = BDM.zeros[Double](4, rank)
      (0 until rank).foreach { k =>
        val pivot = (0 until 4).filterNot(expected.contains).maxBy(i => schur(i, i))
        assert(schur(pivot, pivot) > 0.0)
        expected += pivot
        val rest = (0 until 4).filterNot(expected.contains)
        factors(pivot, k) = math.sqrt(schur(pivot, pivot))
        rest.foreach(i => factors(i, k) = schur(i, pivot) / factors(pivot, k))
        rest.foreach(i => rest.foreach(j => schur(i, j) -= factors(i, k) * factors(j, k)))
      }
      val partial = new newton.PartialCholesky(diag(g).toArray, rank,
        j => (0 until 4).map(i => g(i, j)).toArray, 1e-8)
      assert(partial.indices.toSeq == expected.toSeq)
      val explicit = factors * factors.t
      (0 until 4).filterNot(expected.contains).foreach(i => explicit(i, i) += schur(i, i))
      Seq(BDV(1.0, 2.0, -3.0, 4.0), BDV(-2.0, 0.5, 0.1, 0.0)).foreach { rhs =>
        assert(norm(partial(rhs) - (explicit \ rhs)) < 1e-11)
      }
    }
  }

  test("exhausted CG accepts only a bounded independently measured inexact defect") {
    val epsilon = 1e-4
    val rows = sc.parallelize(Seq(Vectors.dense(1.0, epsilon),
      Vectors.dense(0.0, math.sqrt(1.0 - epsilon * epsilon))), 2)
    val factory = new newton.CgFactory(1e-14, 1,
      CgConfig(preconditionerMemoryBytes = 0))(spark)
    val system = factory.build(rows, 2, None)
    try {
      val rhs = BDV(1.0, 2.0)
      val solution = new BDV(system.solve(new DenseVector(rhs.toArray)).values)
      val g = BDM((1.0, epsilon), (epsilon, 1.0)) / (1.0 + 1e-8) + BDM.eye[Double](2) * 1e-8
      val defect = norm(g * solution - rhs) / norm(rhs)
      assert(defect > 1e-14 && defect <= 1e-3)
      assert(factory.innerIterations == 1 && factory.maximumRank == 0)
    } finally system.release()
  }

  test("Auto uses the 10000-row cutoff across the historical boundaries") {
    assert(NewtonSolver.AutoCholeskyLimit == 10000)
    assert(LP.resolveNewtonSolver(NewtonSolver.Auto, 10000) == NewtonSolver.Cholesky)
    assert(LP.resolveNewtonSolver(NewtonSolver.Auto, 10001) == NewtonSolver.ConjugateGradient)
    Seq(1000, 1001, 5000, 5001).foreach { m =>
      assert(LP.resolveNewtonSolver(NewtonSolver.Auto, m) == NewtonSolver.Cholesky)
    }
  }
}
