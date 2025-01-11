package com.github.vbmacher.spark_lp.collections

object implicits {

  implicit class IteratorOps[A](fst: Iterator[A]) {

    /**
      * Zip two iterators, validating that the iterators are of the same size.
      *
      * @param snd the other iterator
      * @return an iterator of pairs of elements from `fst` and `snd`
      */
    def checkedZip[B](snd: Iterator[B]): Iterator[(A, B)] = new Iterator[(A, B)] {

      /**
        * Check if the both iterators have a next element.
        *
        * @return true if both iterators have a next element, false if not
        * @throws IllegalArgumentException if the iterators have different number of elements
        */
      def hasNext: Boolean = (fst.hasNext, snd.hasNext) match {
        case (true, true) => true
        case (false, false) => false
        case _ => throw new IllegalArgumentException("Can only checkedZip Iterators with the same number of elements")
      }

      /**
        * Get the next element from both iterators.
        *
        * @return a pair of elements from `fst` and `snd`
        */
      def next(): (A, B) = {
        // Check if both iterators have a next element - if not, we could accidentally exhaust the first one and then
        // an exception would be thrown anyway
        if (!hasNext) throw new NoSuchElementException("No more elements to zip")
        (fst.next(), snd.next())
      }
    }
  }
}
