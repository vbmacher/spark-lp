package com.github.vbmacher.spark_lp

import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

import scala.collection.mutable

/** Owns only caches created by this operation; never releases a caller's persisted inputs. */
private[spark_lp] final class CachedRDDs extends AutoCloseable {
  private val owned = mutable.Set.empty[RDD[_]]

  def cache[T](rdd: RDD[T]): RDD[T] = {
    if (rdd.getStorageLevel == StorageLevel.NONE) {
      rdd.cache()
      owned += rdd
    }
    rdd
  }

  def checkpoint[T](rdd: RDD[T]): RDD[T] = {
    rdd.localCheckpoint()
    owned += rdd
    rdd
  }

  def keep[T](rdd: RDD[T]): RDD[T] = { owned -= rdd; rdd }

  def release(rdd: RDD[_]): Unit = {
    if (owned.remove(rdd)) rdd.unpersist(blocking = false)
  }

  override def close(): Unit = owned.toVector.foreach(release)
}
