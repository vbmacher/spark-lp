/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
  * @author Ehsan Mohyedin Kermani: ehsanmo1367@gmail.com
  */
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