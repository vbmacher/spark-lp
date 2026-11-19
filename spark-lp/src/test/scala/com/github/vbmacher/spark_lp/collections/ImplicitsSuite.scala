package com.github.vbmacher.spark_lp.collections

import implicits.IteratorOps
import org.scalatest.funsuite.AnyFunSuite

class ImplicitsSuite extends AnyFunSuite {
  test("checkedZip pairs equal-length iterators in input order") {
    val numbers = Iterator(1, 2, 3)
    val letters = Iterator("a", "b", "c")

    val pairs = numbers.checkedZip(letters).toList

    assert(pairs == List((1, "a"), (2, "b"), (3, "c")))
  }

  test("checkedZip rejects unequal lengths without consuming the unmatched element") {
    val numbers = Iterator(1, 2, 3)
    val letters = Iterator("a", "b")

    val pairs = numbers.checkedZip(letters)
    val matchedPrefix = List(pairs.next(), pairs.next())

    assert(matchedPrefix == List((1, "a"), (2, "b")))

    // Exhausting the shorter iterator must leave the unmatched number available.
    intercept[IllegalArgumentException](pairs.next())

    assert(numbers.hasNext)
    assert(!letters.hasNext)
    assert(numbers.next() == 3)
  }
}
