package com.github.vbmacher.spark_lp.collections

object implicits {

  implicit class IteratorOps[A](fst: Iterator[A]) {

    /**
      * Pairs elements at the same position and rejects inputs with different lengths.
      *
      * Validation is lazy: a length mismatch is reported when the returned iterator reaches the
      * first position present in only one input.
      *
      * @param snd iterator providing the second element of each pair.
      * @return a lazy iterator of `(fstElement, sndElement)` pairs.
      */
    def checkedZip[B](snd: Iterator[B]): Iterator[(A, B)] = new Iterator[(A, B)] {

      /**
        * Returns whether both inputs have another element.
        *
        * @throws IllegalArgumentException if exactly one input has another element.
        */
      def hasNext: Boolean = (fst.hasNext, snd.hasNext) match {
        case (true, true) => true
        case (false, false) => false
        case _ => throw new IllegalArgumentException("Can only checkedZip Iterators with the same number of elements")
      }

      /** Returns the next pair, after checking that neither input ends before the other. */
      def next(): (A, B) = {
        // Check if both iterators have a next element - if not, we could accidentally exhaust the first one and then
        // an exception would be thrown anyway
        if (!hasNext) throw new NoSuchElementException("No more elements to zip")
        (fst.next(), snd.next())
      }
    }
  }
}
