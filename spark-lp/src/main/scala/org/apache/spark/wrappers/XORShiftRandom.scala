package org.apache.spark.wrappers

import org.apache.spark.util.random.{XORShiftRandom => IXORShiftRandom}

/** Accesses Spark's package-private XORShift random-number generator. */
object XORShiftRandom {

  def apply(): IXORShiftRandom = new IXORShiftRandom()
}
