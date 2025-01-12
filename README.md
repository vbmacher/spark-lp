# spark-lp

![Build Status](https://github.com/vbmacher/spark-lp/actions/workflows/scala.yml/badge.svg)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue)](https://opensource.org/license/apache-2-0)

Originally, this project is a fork of Ehsan M. Kermani's project [spark-lp](https://github.com/ehsanmok/spark-lp), as part of his thesis [Distributed linear programming with Apache Spark](https://open.library.ubc.ca/cIRcle/collections/ubctheses/24/items/1.0340337). Original project was developed using Spark 1.6.0 and Scala 2.10.6.

It is a library for solving large-scale [linear programming](https://en.wikipedia.org/wiki/Linear_programming) problems using Apache Spark, implementation of [Mehrohra's predictor-corrector interior point algorithm](https://en.wikipedia.org/wiki/Mehrotra_predictor%E2%80%93corrector_method). 

It is built for multiple Apache Spark versions, and it is published to Maven Central.

## Installation

To use the library in your project, add the following dependency to your `build.sbt` file:

```scala
libraryDependencies += "com.github.vbmacher" %% "spark-lp" % "x.y.z"
```

On your cluster (or host where you run the example), install native LAPACK and BLAS libraries. For example, on Ubuntu, you can install them using the following command:

```bash
sudo apt-get install liblapack-dev libblas-dev
```


## Usage

Linear programming has the following standard form:

	minimize c^T x 
	subject to Ax=b and x >= 0

where `c, b` are given vectors ((.)^T is the transpose operation), `A` is a given `m` by `n` matrix and `x` is the objective vector. We assume that in `A` the number of rows (equations) is
at most equal to the number of columns (unknowns) (`m <= n`) and `A` has full row rank, thus `AA^T` is invertible.

The library provides a simple API to solve linear programming problems. The following example demonstrates how to use the library to solve a simple linear programming problem in parallel with 2 cores and 2 partitions:

```scala
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.mllib.linalg.{DenseVector, Vector, Vectors}
import org.apache.spark.mllib.optimization.lp.VectorSpace._
import org.apache.spark.mllib.optimization.lp.vs.dvector.DVectorSpace
import org.apache.spark.mllib.optimization.lp.vs.vector.DenseVectorSpace
import org.apache.spark.mllib.optimization.lp.LP

implicit val spark: SparkSession = SparkSession.builder
        .appName("ExampleRandomLP")
        .master("local[2]")
        .getOrCreate()

val numPartitions = 2
val cArray = Array(2.0, 1.5, 0.0, 0.0, 0.0, 0.0, 0.0)
val AArray = Array(
    Array(12.0, 16.0, 30.0, 1.0, 0.0),
    Array(24.0, 16.0, 12.0, 0.0, 1.0),
    Array(-1.0, 0.0, 0.0, 0.0, 0.0),
    Array(0.0, -1.0, 0.0, 0.0, 0.0),
    Array(0.0, 0.0, -1.0, 0.0, 0.0),
    Array(0.0, 0.0, 0.0, 1.0, 0.0),
    Array(0.0, 0.0, 0.0, 0.0, 1.0))
val bArray = Array(120.0, 120.0, 120.0, 15.0, 15.0)

val c: DVector = sc.parallelize(cArray, numPartitions).glom.map(new DenseVector(_))
val rows: DMatrix = sc.parallelize(AArray, numPartitions).map(Vectors.dense(_))
val b: DenseVector = new DenseVector(bArray)

val (v, x): (Double, DVector) = LP.solve(c, rows, b)
val xx = Vectors.dense(x.flatMap(_.toArray).collect())
println(s"optimal vector is $xx")
println("optimal min value: " + v)
```

## Software Architecture Overview

Detailed descriptions of our design is described in chapter 4 of the [thesis](https://open.library.ubc.ca/cIRcle/collections/ubctheses/24/items/1.0340337).

## Advantages

* spark-lp is unique because it is **open-source** and it can solve large-scale LP problems in a distributed way with **fault-tolerance** over **commodity clusters** of machines. Thus, it provides the *lowest cost* opportunity for such applications. See page 42 for cluster results [here](https://open.library.ubc.ca/cIRcle/collections/ubctheses/24/items/1.0340337).

* spark-lp is at least ~10X *faster* and more accurate than spark-tfocs for solving large-scale LP problems. See page 38 for local results [here](https://open.library.ubc.ca/cIRcle/collections/ubctheses/24/items/1.0340337). Our benchmark shows that spark-tfocs is *not* suitable even for small LP problems.

## Future plans:

* Implement a DSL for LP problems easily usable with DataFrames and DataSets.
* Add preprocessing to capture more general LP formats.
* Add infeasibility detection.
* Extend to QP solver.
* Add GPU support, as described in page 47 [here](https://open.library.ubc.ca/cIRcle/collections/ubctheses/24/items/1.0340337), using INDArray provided in [ND4J](http://nd4j.org/) library.
