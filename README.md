# spark-lp

![Build Status](https://github.com/vbmacher/spark-lp/actions/workflows/scala.yml/badge.svg)
![Maven Central Version](https://img.shields.io/maven-central/v/com.github.vbmacher/spark-lp_2.12)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

Linear, mixed-integer linear, and continuous convex quadratic programming over
Apache Spark data. It provides a sparse modeling compiler, a
predictor-corrector interior-point solver ([Mehrotra][mehrotra]), and
branch-and-bound for integer variables.

## Features

- **LP & MILP** — [interior-point solver][mehrotra], branch-and-bound, continuous/integer/binary variables.
- **Convex QP** — [separable quadratics][gondzio], coupled PSD factors, weighted least squares.
- **Scala DSL** — DataFrames, typed Datasets, keyed variables, grouped constraints, min/max objectives.
- **Sparse compilation** — distributed coefficients, presolve, bounds, fixed/free variables.
- **Matrix-free solving** — [regularized CG, adaptive preconditioning][gondzio], warm starts, automatic Cholesky/CG selection.
- **Diagnostics & certificates** — infeasibility proofs, unbounded rays, independent numerical checks, constraint slack, residuals, solver status.
- **Runtime controls** — progress callbacks, deadlines, cancellation, stagnation detection, best feasible candidates.

## Install and build

Use **JDK 11** and **Scala 2.12**; Spark is provided by the application. The
release builds seven Spark variants (**2.4.8, 3.0.2, 3.1.3, 3.2.4, 3.3.2, 3.4.2,
3.5.3**) with artifact versions `<spark-version>_<library-version>`.

Build the Spark 3.5.3 artifact locally, then depend on it:

```sh
sbt 'spark-lpSpark_3_52_12/publishLocal'
```

```scala
libraryDependencies += "com.github.vbmacher" %% "spark-lp" % "3.5.3_1.5.0"
```

For remote installation, choose a published version matching your Spark runtime.
Native BLAS/LAPACK can accelerate CPU linear algebra, with a Java fallback.

## Linear programming example

Minimize `2*x + y` subject to `x + y >= 10`, with nonnegative variables:

```scala
import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.dsl.implicits._
import org.apache.spark.sql.SparkSession

implicit val spark: SparkSession = SparkSession.builder()
  .master("local[2]").appName("spark-lp-example").getOrCreate()

try {
  val model = LpProblem("minimum cost", Minimize)
  val x = model.variable("x")
  val y = model.variable("y")
  model += 2.0 * x + y
  model += (x + y >= 10.0).named("demand")

  val result = model.solve()
  try {
    if (result.status == LpStatus.Optimal) {
      println(result.objectiveValue) // approximately 10.0
      println(result.value(x))       // approximately 0.0
      println(result.value(y))       // approximately 10.0
    }
  } finally result.close()
} finally spark.stop()
```

For Spark data, declare `model.variables("amount", domain, col("id"))` and build
objectives with `amount.sum(col("cost"))`. Typed datasets use `variablesOf` and
`weightedBy`; `sumBy` and `lpSumBy` create grouped constraints.

## Quadratic objectives

Separable weighted deviations use the same model:

```scala
val model = LpProblem("weighted fit")
val x = model.variable("x")
val y = model.variable("y")
model += QpObjective.squaredDeviation(x, target = 2.0) +
         QpObjective.squaredDeviation(y, target = 4.0, weight = 2.0)
model += (x + y === 3.0).named("total")

val result = model.solve()
try println(result.objectiveValue) finally result.close() // approximately 6.0
```

`QpObjective.separable(diagonal, linear)` expresses `0.5 * sum(q_i*x_i^2) + c^T*x + k`.
For sparse cross-variable terms, use factors (weights guarantee convexity
structurally; negate a convex objective for concave maximization):

```scala
model.setObjective(
  QpObjective.squared(x + 2.0 * y - 5.0) +
  QpObjective.squared(x - y - 1.0))
```

## Documentation

- [Usage, installation and API vocabulary](docs/usage.adoc)
- [Algorithm, backend selection, scaling limits and certificate verification](docs/algorithm.adoc)
- [Runnable examples](examples/README.md)
- [CPU benchmarks](benchmarks/README.md), [QP comparisons](benchmarks/quadratic/README.md)
  and [GPU investigation](benchmarks/gpu/README.md)

Run the supported Spark matrix with `sbt +test`, or Spark 3.5 only:

```sh
sbt 'spark-lpSpark_3_52_12/test' 'examplesSpark_3_5/compile'
```

Tests cover LP/MIP behavior, both Newton backends, independently checked QP
solutions and KKT witnesses, transformed variables, certificate verification,
progress/stopping and resource cleanup.

## Research references

- **Predictor-corrector method:** Mehrotra (1992), [On the Implementation of a Primal-Dual Interior Point Method][mehrotra].
- **LP Newton equations:** Cui, Morikuni, Tsuchiya and Hayami (2019), [Interior-point methods for LP based on Krylov subspace iterative solvers][cui], §2.1.
- **Matrix-free regularization, preconditioning and QP equations:** Gondzio (2010, revised technical report), [Matrix-Free Interior Point Method][gondzio], §§2–6.

The [algorithm guide](docs/algorithm.adoc) describes how these methods are adapted
in spark-lp. Project attribution: [Ehsan M. Kermani's spark-lp](https://github.com/ehsanmok/spark-lp)
and his thesis, [Distributed linear programming with Apache Spark](https://open.library.ubc.ca/cIRcle/collections/ubctheses/24/items/1.0340337).

[mehrotra]: https://epubs.siam.org/doi/10.1137/0802028
[cui]: https://link.springer.com/article/10.1007/s10589-019-00103-y
[gondzio]: https://webhomes.maths.ed.ac.uk/~jgondzio/reports/mtxFree.pdf
