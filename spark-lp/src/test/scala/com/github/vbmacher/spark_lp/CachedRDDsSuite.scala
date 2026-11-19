package com.github.vbmacher.spark_lp

import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.storage.StorageLevel
import org.scalatest.funsuite.AnyFunSuite

class CachedRDDsSuite extends AnyFunSuite with DataFrameSuiteBase {

  test("checkpointed values survive cache loss without recomputing their source") {
    val caches = new CachedRDDs
    val evaluations = sc.longAccumulator("source evaluations")
    val source = sc.parallelize(1 to 8, 2).map { value =>
      evaluations.add(1L)
      value * 2
    }
    val expected = Array(2, 4, 6, 8, 10, 12, 14, 16)
    try {
      val checkpointed = caches.checkpoint(source)
      val valuesBeforeEviction = checkpointed.collect()

      assert(valuesBeforeEviction.sameElements(expected))
      assert(checkpointed.getCheckpointFile.nonEmpty)

      // Evict the in-memory data and check that the next action reads the checkpoint.
      val evaluationsBeforeEviction = evaluations.value

      checkpointed.unpersist(blocking = true)
      val valuesAfterEviction = checkpointed.collect()

      assert(valuesAfterEviction.sameElements(expected))
      assert(evaluations.value == evaluationsBeforeEviction)
    } finally caches.close()
  }

  test("checkpoint ownership preserves caller caches and releases owned caches") {
    val caches = new CachedRDDs
    val caller = sc.parallelize(1 to 4, 2).persist(StorageLevel.DISK_ONLY)
    try {
      caches.checkpoint(caller).count()
      val owned = caches.checkpoint(caller.map(_ * 2))
      owned.count()

      caches.close()

      assert(caller.getStorageLevel == StorageLevel.DISK_ONLY)
      assert(owned.getStorageLevel == StorageLevel.NONE)
      assert(owned.collect().sameElements(Array(2, 4, 6, 8)))
    } finally {
      caches.close()
      caller.unpersist(blocking = true)
    }
  }
}
