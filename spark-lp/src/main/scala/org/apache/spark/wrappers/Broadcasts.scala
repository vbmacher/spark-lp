package org.apache.spark.wrappers

import org.apache.spark.broadcast.Broadcast

/** Cross-version broadcast lifecycle helpers. */
object Broadcasts {

  /** Destroys a broadcast without waiting for executor block removal. */
  def destroyAsync[T](broadcast: Broadcast[T]): Unit = broadcast.destroy(blocking = false)
}
