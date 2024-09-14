package org.apache.spark.mllib.internal_access

import org.apache.spark.SparkContext
import org.apache.spark.mllib.random.RandomDataGenerator
import org.apache.spark.mllib.rdd.{RandomVectorRDD => IRandomVectorRDD}
import org.apache.spark.util.Utils

object RandomVectorRDD {

  def apply(
    sc: SparkContext,
    size: Long,
    vectorSize: Int,
    numPartitions: Int,
    rng: RandomDataGenerator[Double],
    seed: Long = Utils.random.nextLong
  ): IRandomVectorRDD = new IRandomVectorRDD(
    sc, size, vectorSize, numPartitions, rng, seed
  )
}
