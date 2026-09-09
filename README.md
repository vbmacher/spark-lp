# spark-lp

![Build Status](https://github.com/vbmacher/spark-lp/actions/workflows/scala.yml/badge.svg)
![Maven Central Version](https://img.shields.io/maven-central/v/com.github.vbmacher/spark-lp_2.12)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

Linear and mixed-integer programming over Apache Spark data, using a sparse compiler,
a predictor-corrector interior-point solver, and branch-and-bound for integer variables.

```scala
import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.dsl.implicits._
import spark.implicits._ // an implicit SparkSession named spark

val model = LpProblem("allocation")
val amount = model.variables("amount", offers, $"id")
model += amount.sum($"cost")
model += (amount.sumBy("region")() === demand).named("demand")

val solution = model.solve()
try {
  require(solution.status == LpStatus.Optimal)
  solution.values(amount).show()
} finally solution.close()
```

Here `offers` contains `id`, `region`, `cost`; `demand` contains `region`, `rhs`.

- [Usage, installation, and complete examples](docs/usage.adoc)
- [Algorithm, Mermaid diagrams, and scaling limits](docs/algorithm.adoc)
- [Runnable allocation example](examples/src/main/scala/com/github/vbmacher/spark_lp/examples/ExampleAllocationDsl.scala)

Build with JDK 11: `sbt +test`. For Spark 3.5 only:
`sbt 'spark-lpSpark_3_52_12/test' 'examplesSpark_3_5/compile'`.
Artifact versions are `<spark-version>_<library-version>`; this checkout builds library
version 1.1.0. Spark is provided by the application.

Originally forked from [Ehsan M. Kermani's spark-lp](https://github.com/ehsanmok/spark-lp),
which accompanies his thesis, [Distributed linear programming with Apache Spark](https://open.library.ubc.ca/cIRcle/collections/ubctheses/24/items/1.0340337).
