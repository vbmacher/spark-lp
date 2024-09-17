package com.github.vbmacher.spark_lp

import com.esotericsoftware.kryo.Kryo
import com.github.vbmacher.spark_lp.fs.dmatrix.vector.LPRowMatrix
import com.github.vbmacher.spark_lp.fs.dvector.dmatrix.SpLinopMatrix
import com.github.vbmacher.spark_lp.fs.dvector.vector.LinopMatrixSpark
import com.github.vbmacher.spark_lp.fs.vector.dvector.LinopMatrix
import org.apache.spark.serializer.KryoRegistrator

/**
  * A class for registering Kryo fast serialization.
  */
class LPKryoRegistrator extends KryoRegistrator {

  override def registerClasses(kryo: Kryo): Unit = {
    kryo.register(classOf[LP])
    kryo.register(classOf[Initialize])
    kryo.register(classOf[LPRowMatrix])
    kryo.register(classOf[SpLinopMatrix])
    kryo.register(classOf[LinopMatrixSpark])
    kryo.register(classOf[LinopMatrix])
  }
}