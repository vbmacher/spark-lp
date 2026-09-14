package com.github.vbmacher.spark_lp.dsl

import org.scalatest.funsuite.AnyFunSuite

class LpCombinatoricsSuite extends AnyFunSuite {
  import LpCombinatorics._

  test("fixed-size outputs have deterministic position-based order") {
    val data = Vector("a", "b", "c")
    assert(combinations(data, 2).toVector == Vector(Vector("a", "b"), Vector("a", "c"), Vector("b", "c")))
    assert(permutations(data, 2).toVector == Vector(
      Vector("a", "b"), Vector("b", "a"), Vector("a", "c"),
      Vector("c", "a"), Vector("b", "c"), Vector("c", "b")))
    assert(combinations(Vector("a", "a", "b"), 2).toVector ==
      Vector(Vector("a", "a"), Vector("a", "b"), Vector("a", "b")))
    assert(permutations(Vector("a", "a"), 2).toVector == Vector(Vector("a", "a"), Vector("a", "a")))
  }

  test("empty, oversized and negative sizes have explicit semantics") {
    assert(combinations(Vector.empty[Int], 0).toVector == Vector(Vector.empty[Int]))
    assert(permutations(Vector.empty[Int], 0).toVector == Vector(Vector.empty[Int]))
    assert(combinations(Vector(1), 2).isEmpty)
    assert(permutations(Vector(1), 2).isEmpty)
    assert(combinationsUpTo(Vector(1, 2, 3), 9).size == 8)
    assert(permutationsUpTo(Vector(1, 2, 3), 9).size == 16)
    intercept[LpModelException](combinations(Vector(1), -1))
    intercept[LpModelException](permutations(Vector(1), -1))
    intercept[LpModelException](combinationsUpTo(Vector(1), -1))
    intercept[LpModelException](permutationsUpTo(Vector(1), -1))
  }

  test("enormous output spaces can be consumed incrementally") {
    assert(combinations(0 until 100, 50).take(2).size == 2)
    assert(permutations(0 until 100, 50).take(2).size == 2)
  }
}
