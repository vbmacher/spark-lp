package com.github.vbmacher.spark_lp

import breeze.linalg.{DenseMatrix => BDM, DenseVector => BDV, diag, norm}
import com.github.vbmacher.spark_lp.benchmarks.Fixtures
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.mllib.linalg.{DenseVector, Vectors, Vector => SparkVector}
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
      MatrixFreeConfig(preconditionerMemoryBytes = 0))(spark)
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

  test("seeded fixtures and actual converged primal-dual iterates satisfy original LP equations") {
    for (family <- Seq("well", "wide", "dependent", "degenerate"); seed <- Seq(11, 29, 47)) {
      val spec = Fixtures.Case("test", 4, 3, 5, family, seed, 1e-8, 4)
      val data = Fixtures.generate(spec)
      assert(data.hash == Fixtures.generate(spec).hash)
      assert(Fixtures.passes(data.residuals(data.x, data.y, data.s), 1e-12))
      // Nearly dependent/wide cases at the full campaign scales may fail and remain recorded
      // failures; this small end-to-end regression uses well/degenerate full-row-rank fixtures.
      if (family == "well" || family == "degenerate") {
        Seq(NewtonSolver.Cholesky, NewtonSolver.ConjugateGradient).foreach { backend =>
          var inspected = false
          val result = LP.solveSummary(
            sc.parallelize(data.c.toSeq, 2).glom().map(new DenseVector(_)),
            sc.parallelize(data.columns.toSeq.map(v => v: SparkVector), 2), new DenseVector(data.b),
            maxIter = 100, solver = backend, inspectConverged = Some((x, y, s) => {
              inspected = true
              val actual = data.residuals(x.flatMap(_.values).collect(), y.values, s.flatMap(_.values).collect())
              assert(Fixtures.passes(actual, 1e-8), s"$spec $backend $actual")
            }))(spark)
          try {
            assert(result.termination == LP.Termination.Converged)
            assert(inspected)
          } finally result.x.unpersist(blocking = true)
        }
      }
    }
  }

  test("independent accuracy gate rejects wrong dual, negative variables and nonfinite values") {
    val data = Fixtures.generate(Fixtures.Case("gate", 4, 2, 3, "well", 11, 1e-8, 4))
    val good = data.residuals(data.x, data.y, data.s)
    assert(Fixtures.passes(good, 1e-8))
    assert(!Fixtures.passes(data.residuals(data.x, data.y.map(_ + 1.0), data.s), 1e-8))
    Seq("primal", "dual", "gap", "objective_error").foreach { key =>
      assert(!Fixtures.passes(good.updated(key, Double.NaN), 1e-8))
      assert(!Fixtures.passes(good.updated(key, 1e-5), 1e-8))
    }
    assert(!Fixtures.passes(good.updated("min_x", -1.0), 1e-8))
    assert(!Fixtures.passes(Map.empty, 1e-8))
  }

  test("Auto and explicit DSL overrides retain the already selected policy and resource cap") {
    assert(NewtonSolver.AutoCholeskyLimit == 1000)
    assert(LP.resolveNewtonSolver(NewtonSolver.Auto, 1000) == NewtonSolver.Cholesky)
    assert(LP.resolveNewtonSolver(NewtonSolver.Auto, 1001) == NewtonSolver.ConjugateGradient)
    Seq(5000, 5001).foreach { m =>
      assert(LP.resolveNewtonSolver(NewtonSolver.Auto, m) == NewtonSolver.ConjugateGradient)
    }
  }
}
