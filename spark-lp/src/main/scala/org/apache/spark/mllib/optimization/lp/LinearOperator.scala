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
  * @author Aaron Staple, Ehsan Mohyedin Kermani: ehsanmo1367@gmail.com
  */
package org.apache.spark.mllib.optimization.lp

/**
  * A linear operator trait, with support for applying the operator and getting its adjoint.
  *
  * @tparam X Type representing an input vector.
  * @tparam Y Type representing an output vector.
  */
trait LinearOperator[X, Y] {

  /**
    * Apply the operator for an operand.
    *
    * @param x The vector on which to apply the operator.
    * @return The result of applying the operator on x.
    */
  def apply(x: X): Y
}
