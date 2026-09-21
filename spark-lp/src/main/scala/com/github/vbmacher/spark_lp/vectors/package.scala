package com.github.vbmacher.spark_lp

import org.apache.spark.mllib.linalg.{DenseVector, Vector}
import org.apache.spark.rdd.RDD

package object vectors {

  /**
    * Distributed vector represented by one Spark MLlib `DenseVector` per RDD partition.
    *
    * Element order is partition order followed by position inside each partition vector. Operations
    * involving two `DVector` values require the same partition count and the same local vector size
    * in corresponding partitions.
    */
  type DVector = RDD[DenseVector]

  /**
    * Distributed matrix represented by one dense or sparse MLlib `Vector` per matrix row.
    *
    * For operations that pair matrix rows with a [[DVector]], corresponding partitions must align:
    * a matrix partition containing `k` rows must match a vector partition whose local
    * `DenseVector` contains `k` elements.
    */
  type DMatrix = RDD[Vector]
}
