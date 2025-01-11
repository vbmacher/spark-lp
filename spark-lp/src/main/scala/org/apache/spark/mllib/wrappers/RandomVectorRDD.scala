package org.apache.spark.mllib.wrappers

import org.apache.spark.mllib.random.RandomDataGenerator
import org.apache.spark.mllib.rdd.{RandomVectorRDD => IRandomVectorRDD}
import org.apache.spark.sql.SparkSession
import org.apache.spark.util.Utils

/**
  * Internal helper class to create spark.mllib.rdd.RandomVectorRDD instances.
  */
object RandomVectorRDD {

  /**
    * Create a RandomVectorRDD instance.
    *
    * @param size          RDD size (> 0)
    * @param vectorSize    Vector size (row in the RDD), (> 0)
    * @param numPartitions Number of partitions (> 0)
    * @param rng           Random number generator
    * @param seed          Random seed
    * @param spark         Spark session
    * @return
    */
  def apply(
    size: Long,
    vectorSize: Int,
    numPartitions: Int,
    rng: RandomDataGenerator[Double],
    seed: Long = Utils.random.nextLong
  )(implicit spark: SparkSession): IRandomVectorRDD = new IRandomVectorRDD(
    spark.sparkContext, size, vectorSize, numPartitions, rng, seed
  )
}
