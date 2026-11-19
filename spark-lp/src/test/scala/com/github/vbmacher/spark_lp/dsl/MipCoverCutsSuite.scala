package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.dsl.compiler.IntColumn
import org.scalatest.funsuite.AnyFunSuite

class MipCoverCutsSuite extends AnyFunSuite {
  private def columns(weights: Vector[Double]): Vector[IntColumn] = weights.zipWithIndex.map { case (a, i) =>
    IntColumn(i.toLong, i, "", 0.0, 1.0, 0.0, i + 1, Map(0 -> a))
  }
  private val enabled = MipCutsConfig(enabled = true)

  test("global covers preserve every independently enumerated feasible binary assignment") {
    val weights = Vector(2.0, 3.0, 4.0, 5.0, 7.0, 9.0)
    val cols = columns(weights)
    val capacity = 12.0
    val lower = Array.fill(weights.size)(0.0)
    val upper = Array.fill(weights.size)(1.0)
    val relaxedValues = Map(0L -> 1.0, 1L -> 1.0, 2L -> 1.0, 3L -> 0.6)
    val feasibleAssignments = (0 until 64).map { mask =>
      weights.indices.map(index => if ((mask & (1 << index)) != 0) 1.0 else 0.0)
    }.filter(bits => weights.zip(bits).map { case (weight, bit) => weight * bit }.sum <= capacity)

    val cuts = MipCoverCuts.generate(cols, Array(capacity), lower, upper, Set.empty,
      relaxedValues, global = true, enabled, Set.empty, 10)

    assert(cuts.nonEmpty)
    assert(cuts.forall(_.scope == GlobalCut))
    feasibleAssignments.foreach { bits =>
      cuts.foreach { cut =>
        assert(cut.columns.map(column => bits(column.toInt)).sum <= cut.rhs,
          s"cut $cut ruled out feasible assignment $bits")
      }
    }

    // Feeding the same relaxation back with already known signatures must add no cuts.
    val knownCuts = cuts.map(_.signature).toSet

    val repeated = MipCoverCuts.generate(cols, Array(capacity), lower, upper, Set.empty,
      relaxedValues, global = true, enabled, knownCuts, 10)

    assert(repeated.isEmpty)
  }

  test("local covers apply only within their branch domain") {
    val cols = columns(Vector(2.0, 2.0, 2.0))
    val lower = Array(0.0, 1.0, 0.0)
    val upper = Array(1.0, 1.0, 1.0)
    val cut = MipCoverCuts.generate(cols, Array(1.0), lower, upper, Set.empty,
      Map(0L -> 0.5), global = false, enabled, Set.empty, 10).head

    assert(cut.columns == Vector(0L))
    assert(cut.rhs == 0.0)
    assert(cut.scope.permits(lower, upper))
    assert(cut.scope.permits(Array(0.0, 1.0, 0.0), Array(0.0, 1.0, 1.0)))
    assert(!cut.scope.permits(Array(0.0, 0.0, 0.0), Array(1.0, 0.0, 1.0)))
    intercept[IllegalArgumentException](MipCoverCuts.generate(cols, Array(1.0), lower, upper, Set.empty,
      Map(0L -> 0.5), global = true, enabled, Set.empty, 10))
  }

  test("cover generation declines unsafe candidates or an exhausted cut budget") {
    val lower = Array(0.0, 0.0)
    val upper = Array(1.0, 1.0)
    val relaxedValues = Map(0L -> 1.0, 1L -> 1.0)
    val inputs = Seq(
      ("capacity exceeded by numerical noise", Vector(1.0, 1.0), 2.0 - 1e-12, Set.empty[Int], 10),
      ("unbounded negative contributor", Vector(1.0, 1.0), 1.5, Set(0), 10),
      ("no remaining cut budget", Vector(1.0, 1.0), 1.5, Set.empty[Int], 0),
      ("numerically tiny coefficient", Vector(1e-15, 1.0), 0.5, Set.empty[Int], 10))

    inputs.foreach { case (scenario, weights, capacity, unboundedRows, budget) =>
      val cuts = MipCoverCuts.generate(columns(weights), Array(capacity), lower, upper, unboundedRows,
        relaxedValues, global = true, enabled, Set.empty, budget)

      assert(cuts.isEmpty, scenario)
    }
  }

  test("covers remain valid with shifted binaries and negative bounded integer contributions") {
    val random = new scala.util.Random(66L)
    var accepted = 0
    for (_ <- 0 until 100) {
      val weights = Vector.fill(3)(1.0 + random.nextInt(7)) :+ -(1.0 + random.nextInt(3))
      val roots = Vector(-2.0, 1.0, 0.0, -1.0)
      val widths = Vector(1.0, 1.0, 1.0, 3.0)
      val cols = weights.indices.map(i => IntColumn(i.toLong, i, "", roots(i), roots(i) + widths(i),
        0.0, i + 1, Map(0 -> weights(i)))).toVector
      val capacity = random.nextInt(12) - 3.0
      val cuts = MipCoverCuts.generate(cols, Array(capacity), roots.toArray,
        roots.zip(widths).map { case (a, b) => a + b }.toArray, Set.empty,
        Map(0L -> 1.0, 1L -> 1.0, 2L -> 1.0, 3L -> 1.5), true, enabled, Set.empty, 10)
      accepted += cuts.size
      for (a <- 0 to 1; b <- 0 to 1; c <- 0 to 1; d <- 0 to 3) {
        val shifted = Vector(a.toDouble, b.toDouble, c.toDouble, d.toDouble)
        if (weights.zip(shifted).map { case (w, v) => w * v }.sum <= capacity)
          assert(cuts.forall(cut => cut.columns.map(g => shifted(g.toInt)).sum <= cut.rhs))
      }
    }

    assert(accepted > 0)
  }

}
