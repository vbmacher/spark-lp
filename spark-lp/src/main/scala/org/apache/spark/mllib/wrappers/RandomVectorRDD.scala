package org.apache.spark.mllib.wrappers

import org.apache.spark.mllib.random.RandomDataGenerator
import org.apache.spark.mllib.rdd.{RandomVectorRDD => IRandomVectorRDD}
import org.apache.spark.sql.SparkSession
import org.apache.spark.util.Utils

/** Accesses Spark MLlib's package-private random-vector RDD constructor. */
object RandomVectorRDD {

  /**
    * Creates an RDD whose rows are independently generated vectors.
    *
    * @param size number of rows; must be positive.
    * @param vectorSize number of values in each row; must be positive.
    * @param numPartitions number of RDD partitions; must be positive.
    * @param rng generator copied and seeded independently for each partition.
    * @param seed seed from which partition seeds are derived.
    * @param spark Spark session that owns the resulting RDD.
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
