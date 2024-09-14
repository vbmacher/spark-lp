package org.apache.spark.internal_access

import org.apache.spark.util.random.{XORShiftRandom => IXORShiftRandom}

object XORShiftRandom {

  def apply(): IXORShiftRandom = new IXORShiftRandom()
}
