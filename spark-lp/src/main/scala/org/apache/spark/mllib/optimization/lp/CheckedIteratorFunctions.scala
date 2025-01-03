package org.apache.spark.mllib.optimization.lp

import scala.language.implicitConversions

/**
  * Helpers to perform validation when zipping two iterators, available using an implicit conversion.
  */
private[lp] class CheckedIteratorFunctions[A](self: Iterator[A]) {

  /** Zip two iterators, validating that the iterators are the same size. */
  def checkedZip[B](that: Iterator[B]): Iterator[(A, B)] =
    new Iterator[(A, B)] {
      def hasNext: Boolean = (self.hasNext, that.hasNext) match {
        case (true, true) => true
        case (false, false) => false
        case _ => throw new IllegalArgumentException("Can only checkedZip Iterators with the " +
          "same number of elements")
      }

      def next(): (A, B) = (self.next(), that.next())
    }
}

private[lp] object CheckedIteratorFunctions {

  implicit def iteratorToCheckedIterator[T](iterator: Iterator[T]): CheckedIteratorFunctions[T] =
    new CheckedIteratorFunctions(iterator)
}