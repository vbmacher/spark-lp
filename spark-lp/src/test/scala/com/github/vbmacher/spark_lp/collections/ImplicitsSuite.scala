package com.github.vbmacher.spark_lp.collections

import implicits.IteratorOps
import org.scalatest.funsuite.AnyFunSuite

class ImplicitsSuite extends AnyFunSuite {

  test("checkedZip should zip two iterators") {
    val fst = Iterator(1, 2, 3)
    val snd = Iterator("a", "b", "c")
    val zipped = fst.checkedZip(snd)
    assert(zipped.toList === List((1, "a"), (2, "b"), (3, "c")))
  }

  test("checkedZip should not exhaust iterators if they have different number of elements") {
    val fst = Iterator(1, 2, 3)
    val snd = Iterator("a", "b")
    val zipped = fst.checkedZip(snd)

    val n1 = zipped.next()
    val n2 = zipped.next()

    assert(n1 === Tuple2(1, "a"))
    assert(n2 === Tuple2(2, "b"))
    assertThrows[IllegalArgumentException](zipped.next())
    assert(fst.hasNext) // 3 is still there
    assert(!snd.hasNext)
  }
}
