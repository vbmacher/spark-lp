# spark-lp

![Build Status](https://github.com/vbmacher/spark-lp/actions/workflows/scala.yml/badge.svg)
![Maven Central Version](https://img.shields.io/maven-central/v/com.github.vbmacher/spark-lp_2.12)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

Linear, mixed-integer linear, and continuous convex quadratic programming over
Apache Spark data. Version **2.0.0** provides a sparse modeling compiler, a
predictor-corrector interior-point solver ([Mehrotra][mehrotra]), and branch-and-bound
for integer variables.

## Features

- **LP & MILP** — [interior-point solving][mehrotra], feasibility models, rowless models, continuous/integer/binary domains.
- **Convex QP** — [separable quadratics][gondzio], coupled PSD factors, weighted least squares.
- **MIP search** — branch-and-bound, SOS1/SOS2, cover cuts, strong branching, parallel nodes, continuous relaxation.
- **Scala & Spark DSL** — DataFrames, typed Datasets, keyed lookup, local dictionaries/matrices, grouped constraints, dot products, scalar division, combinatorics.
- **Editable models** — coefficient/RHS updates, bounds, fixing/unfixing, renaming, independent copies, prioritized objectives.
- **Sparse compilation & presolve** — distributed coefficients, fixed/free/upper-only variables, inferred integer bounds, propagation, substitution, original-coordinate reconstruction.
- **Matrix-free solving** — [regularized CG, adaptive preconditioning][gondzio], automatic Cholesky/CG selection, LP warm starts, MIP starts.
- **Runtime controls** — progress callbacks, deadlines, cancellation, stagnation detection, absolute/relative MIP gaps.
- **Solution analysis** — expression evaluation, explicit rounding, independent candidate validation, slack, residuals, dual prices, reduced costs.
- **Model interchange** — LP export, MPS import/export, portable JSON, structured serialization, algebra printing, distributed introspection.
- **Solver extensions** — custom adapters, optional HiGHS native sessions, parameters, callbacks, solution files, managed resources.
- **Verifiable evidence** — infeasibility certificates, unbounded rays, independent numerical checks, CPU/QP/MIP benchmarks, experimental GPU investigation.

## Build and use 2.0.0

Use **JDK 11** and **Scala 2.12**; Spark is provided by the application. The
release builds seven Spark variants (**2.4.8, 3.0.2, 3.1.3, 3.2.4, 3.3.2, 3.4.2,
3.5.3**) with artifact versions `<spark-version>_<library-version>`.

Build the Spark 3.5.3 artifact locally, then depend on it:

```sh
sbt 'spark-lpSpark_3_52_12/publishLocal'
```

```scala
libraryDependencies += "com.github.vbmacher" %% "spark-lp" % "3.5.3_2.0.0"
```

These coordinates describe the 2.0.0 build; `publishLocal` makes it available on
this machine. For remote installation, choose a published version matching your
Spark runtime. Native BLAS/LAPACK can accelerate CPU linear algebra; a Java fallback
is available.

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

Factor weights guarantee convexity structurally. Negate a convex objective for a
concave maximization problem. The [QP guide](docs/quadratic-programming.md) explains
representations, transformations, backend requirements and numerical checks.

## Evidence, progress and limits

`result.evidence` is optional. `proof.verify(tolerance)` independently checks its
snapshot's coefficients, bounds, normalization and, for QP directions, curvature.
An unboundedness proof needs a feasible point as well as an improving direction;
missing evidence remains explicit. See [certificate verification](docs/certificates.md).

Use `SolveConfig(control = SolveControl(...))` for progress callbacks, cooperative
time limits, cancellation and opt-in stagnation detection. Before consuming a
limited result, inspect `status`, `candidate.available`, `candidate.feasible` and
`stopReason`. Complete Spark actions before closing the result. See
[progress and result handling](docs/usage.adoc). MIP search uses
`SolveConfig(mip = MipConfig(control = MipControl(...)))` for its global progress
and deadline controls; `MipConfig.search` configures cuts, probing and concurrency.

- Quadratic objectives support **continuous variables and linear constraints**.
  Mixed-integer QP, nonconvex objectives and quadratic constraints are unsupported.
- Coupled Hessians are supplied as **PSD factors**, not arbitrary unverified matrix
  entries. Separable QP rejects curved free variables. Coupled QP supports them
  through factor lifting and uses regularized CG; explicit Cholesky is rejected
  when a coupled model contains free variables.
- Cholesky stores its normal matrix on the driver; CG avoids that matrix but still
  keeps constraint metadata and iteration vectors there. Factor lifting adds rows
  and columns. `Auto` normally uses Cholesky through 10,000 equality-form rows,
  subject to `maxLocalConstraints`; this is a heuristic, not a universal crossover.
- Integer search uses a driver coordinator with optional bounded concurrent LP nodes.
  It can grow exponentially; cuts, probing and parallelism can increase time and memory.
  Numerical tolerances and iteration/node limits do not guarantee a solution to every model.
- SOS groups require finite declared member bounds. Integer domains that remain unbounded
  after supported inference are rejected when the built-in search cannot handle them.
  Dual prices and reduced costs are optional and unavailable for unsupported result paths.
- External adapters declare their own capabilities. The optional HiGHS bridge runs a
  bounded local Python process; it does not make HiGHS a distributed solver.
- **Production GPU acceleration is not included.** The optional OpenCL prototype
  has no hardware FP64 support on the documented Apple GPU configuration; FP32
  results exceed the accuracy target. The [GPU report](benchmarks/gpu/README.md) describes the validation
  status and a proposed multi-vendor approach with optional backends and CPU
  fallback. This local result does not rule out other GPUs; ND4J, CUDA and OpenCL
  are not core dependencies.

## Documentation and validation

- [Usage, installation and API vocabulary](docs/usage.adoc)
- [Algorithm, backend selection, scaling limits and certificate verification](docs/algorithm.adoc)
- [Runnable examples](examples/README.md)
- [CPU benchmarks](benchmarks/README.md), [QP comparisons](benchmarks/quadratic/README.md)
  and [GPU investigation](benchmarks/gpu/README.md)
- [Presolve](benchmarks/presolve/README.md), [warm starts](benchmarks/starts/README.md)
  and [MIP search](benchmarks/mip-search/README.md) benchmarks

Run the supported Spark matrix with `sbt +test`, or Spark 3.5 only:

```sh
sbt 'spark-lpSpark_3_52_12/test' 'examplesSpark_3_5/compile'
```

Tests cover LP/MIP behavior, both Newton backends, independently checked QP
solutions and KKT witnesses, transformed variables, certificate verification,
progress/stopping, model edits and interchange, custom/native adapters, starts,
presolve, cut validity, parallel search and resource cleanup. Benchmarks retain failed
attempts as well as successful results; timing alone is not evidence of equal numerical accuracy.

## Research references

- **Predictor-corrector method:** Mehrotra (1992), [On the Implementation of a Primal-Dual Interior Point Method][mehrotra].
- **LP Newton equations:** Cui, Morikuni, Tsuchiya and Hayami (2019), [Interior-point methods for LP based on Krylov subspace iterative solvers][cui], §2.1.
- **Matrix-free regularization, preconditioning and QP equations:** Gondzio (2010, revised technical report), [Matrix-Free Interior Point Method][gondzio], §§2–6.
- **Integer optimization and cover cuts:** Nemhauser and Wolsey, [Integer and Combinatorial Optimization][integer], and [Knapsack Cover Inequalities][covers].
- **Presolve:** [PaPILO: A Parallel Presolving Library for Integer and Linear Programming with Multiprecision Support][papilo].
- **Strong branching:** [SCIP full strong branching documentation][strong].

The [algorithm guide](docs/algorithm.adoc) describes how these methods are adapted
in spark-lp. Project attribution: [Ehsan M. Kermani's spark-lp](https://github.com/ehsanmok/spark-lp)
and his thesis, [Distributed linear programming with Apache Spark](https://open.library.ubc.ca/cIRcle/collections/ubctheses/24/items/1.0340337).

[mehrotra]: https://epubs.siam.org/doi/10.1137/0802028
[cui]: https://link.springer.com/article/10.1007/s10589-019-00103-y
[gondzio]: https://webhomes.maths.ed.ac.uk/~jgondzio/reports/mtxFree.pdf
[integer]: https://onlinelibrary.wiley.com/doi/book/10.1002/9781118627372
[covers]: https://onlinelibrary.wiley.com/doi/abs/10.1002/9780470400531.eorms0204
[papilo]: https://arxiv.org/abs/2206.10709
[strong]: https://scipopt.org/doc/html/branch__fullstrong_8c.php
