package com.github.vbmacher.spark_lp

import org.apache.spark.mllib.linalg.{DenseVector, Vector}
import org.apache.spark.rdd.RDD

package object vectors {

  /**
    * A distributed one dimensional vector stored as an RDD of mllib.linalg DenseVectors, where each
    * RDD partition contains a single DenseVector. This representation provides improved performance
    * over RDD[Double], which requires that each element be unboxed during elementwise operations.
    */
  type DVector = RDD[DenseVector]

  /**
    * A distributed two-dimensional matrix stored as an RDD of mllib.linalg Vectors, where each
    * Vector represents a row of the matrix. The Vectors may be dense or sparse.
    *
    * NOTE In order to multiply the transpose of a DMatrix 'm' by a DVector 'v', m and v must be
    * consistently partitioned. Each partition of m must contain the same number of rows as there
    * are vector elements in the corresponding partition of v. For example, if m contains two
    * partitions and there are two row Vectors in the first partition and three row Vectors in the
    * second partition, then v must have two partitions with a single Vector containing two elements
    * in its first partition and a single Vector containing three elements in its second partition.
    */
  type DMatrix = RDD[Vector]
}
