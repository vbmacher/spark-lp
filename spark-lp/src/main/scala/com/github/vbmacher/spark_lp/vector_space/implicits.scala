package com.github.vbmacher.spark_lp.vector_space

import com.github.vbmacher.spark_lp.dvector.DVector
import org.apache.spark.mllib.linalg.DenseVector

object implicits {

  implicit val vectorVS: VectorSpace[DenseVector] = LocalVectorSpace

  implicit val dvectorVS: VectorSpace[DVector] = DistributedVectorSpace
}
