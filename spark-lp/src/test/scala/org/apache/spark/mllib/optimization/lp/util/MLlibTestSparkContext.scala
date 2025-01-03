package org.apache.spark.mllib.optimization.lp.util

import org.apache.spark.{SparkConf, SparkContext}
import org.scalatest.{BeforeAndAfterAll, Suite}

/**
  * Testing helper excerpted from the spark testing library.
  *
  * @see [[https://github.com/apache/spark/blob/master/mllib/src/test/scala/org/apache/spark/mllib/util/MLlibTestSparkContext.scala]]
  */
trait MLlibTestSparkContext extends BeforeAndAfterAll {

  self: Suite =>

  @transient var sc: SparkContext = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    val conf = new SparkConf()
      .setMaster("local[2]")
      .setAppName("MLlibUnitTest")
    sc = new SparkContext(conf)
    sc.setLogLevel("WARN")
  }

  override def afterAll(): Unit = {
    if (sc != null) {
      sc.stop()
    }
    sc = null
    super.afterAll()
  }
}
