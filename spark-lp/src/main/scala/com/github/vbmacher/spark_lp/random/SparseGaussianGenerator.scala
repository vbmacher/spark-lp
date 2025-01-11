package com.github.vbmacher.spark_lp.random

import org.apache.spark.mllib.random.RandomDataGenerator
import org.apache.spark.wrappers.XORShiftRandom

/**
  * Helper class for generating sparse values with normal (Gaussian) distribution.
  *
  * Under the hood, it uses XORShiftRandom generator.
  *
  * @param density The density parameter specifies the probability of generating a non-zero value.
  */
class SparseGaussianGenerator(density: Double) extends RandomDataGenerator[Double] {

  private val random = XORShiftRandom()

  override def nextValue(): Double = if (random.nextDouble < density) random.nextGaussian else 0.0

  override def setSeed(seed: Long): Unit = random.setSeed(seed)

  override def copy(): SparseGaussianGenerator = new SparseGaussianGenerator(density)
}

object SparseGaussianGenerator {
  def apply(density: Double): SparseGaussianGenerator = new SparseGaussianGenerator(density)
}
