package com.github.vbmacher.spark_lp

import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.rdd.RDD

package object dvector {

  /**
    * A distributed one dimensional vector stored as an RDD of mllib.linalg DenseVectors, where each
    * RDD partition contains a single DenseVector. This representation provides improved performance
    * over RDD[Double], which requires that each element be unboxed during elementwise operations.
    */
  type DVector = RDD[DenseVector]
}
