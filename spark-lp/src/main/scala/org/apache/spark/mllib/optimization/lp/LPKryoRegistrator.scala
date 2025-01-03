package org.apache.spark.mllib.optimization.lp

import com.esotericsoftware.kryo.Kryo
import org.apache.spark.mllib.optimization.lp.fs.dmatrix.vector.LPRowMatrix
import org.apache.spark.mllib.optimization.lp.fs.dvector.dmatrix.SpLinopMatrix
import org.apache.spark.mllib.optimization.lp.fs.dvector.vector.LinopMatrixAdjoint
import org.apache.spark.mllib.optimization.lp.fs.vector.dvector.LinopMatrix
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
    kryo.register(classOf[LinopMatrixAdjoint])
    kryo.register(classOf[LinopMatrix])
  }
}