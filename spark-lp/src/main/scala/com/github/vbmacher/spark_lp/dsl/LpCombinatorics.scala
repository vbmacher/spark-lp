package com.github.vbmacher.spark_lp.dsl

/**
  * Enumerates combinations and permutations of driver-local values.
  *
  * Input positions are distinct: equal values at different positions may therefore produce equal
  * output vectors. Each method copies its input once and returns a lazy iterator.
  */
object LpCombinatorics {
  private def input[A](values: Iterable[A], size: Int): Vector[A] = {
    if (size < 0) throw new LpModelException("Enumeration size must be nonnegative")
    values.toVector
  }

  private def combinationsOf[A](values: Vector[A], size: Int): Iterator[Vector[A]] =
    if (size > values.size) Iterator.empty
    else values.indices.combinations(size).map(_.map(values).toVector)

  private def permutationsOf[A](values: Vector[A], size: Int): Iterator[Vector[A]] =
    if (size > values.size) Iterator.empty
    else values.indices.combinations(size).flatMap(_.permutations).map(_.map(values).toVector)

  def combinations[A](values: Iterable[A], size: Int): Iterator[Vector[A]] =
    combinationsOf(input(values, size), size)

  def permutations[A](values: Iterable[A], size: Int): Iterator[Vector[A]] =
    permutationsOf(input(values, size), size)

  /** Returns combinations for every size from zero through `maxSize`, capped at the input length. */
  def combinationsUpTo[A](values: Iterable[A], maxSize: Int): Iterator[Vector[A]] = {
    val data = input(values, maxSize)
    (0 to math.min(maxSize, data.size)).iterator.flatMap(combinationsOf(data, _))
  }

  def permutationsUpTo[A](values: Iterable[A], maxSize: Int): Iterator[Vector[A]] = {
    val data = input(values, maxSize)
    (0 to math.min(maxSize, data.size)).iterator.flatMap(permutationsOf(data, _))
  }
}
