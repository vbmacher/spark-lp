# spark-lp

![Build Status](https://github.com/vbmacher/spark-lp/actions/workflows/scala.yml/badge.svg)
![Maven Central Version](https://img.shields.io/maven-central/v/com.github.vbmacher/spark-lp_2.12)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

Linear, mixed-integer linear, and continuous convex quadratic programming over
Apache Spark data. Version **1.5.0** combines a sparse modeling compiler, a
predictor-corrector interior-point solver, and branch-and-bound for integer variables.

## Features

| Area | Implemented capabilities |
|---|---|
| Modeling | Scalar variables, keyed DataFrame variable sets, typed Datasets, composite/custom keys, and named constraints. Build expressions lazily from Spark columns or typed coefficient functions. |
| Objectives and constraints | Minimize or maximize; weighted sums, grouped sums, relational coefficient tables, equality and inequality constraints, and scalar or keyed right-hand sides. Replace objectives and solve a model repeatedly. |
| Variable domains | Continuous, bounded integer and binary variables; lower/upper bounds, fixed-variable substitution, bound shifts and free-variable splits. |
| Sparse compilation | Deterministic column/row ordering, sparse distributed coefficients, validation of keys and finite values, aggregation of repeated terms, trivial-row presolve and consistent duplicate-equality elimination. |
| Continuous LP | Predictor-corrector steps with independently recomputed primal, dual and gap residuals; explicit convergence, iteration-limit, infeasibility and unboundedness statuses. The low-level `LP.solve` API also accepts distributed matrix/vector inputs. |
| Newton backends | Driver-local Cholesky with independent-block factorization, or matrix-free regularized conjugate gradient with adaptive partial-Cholesky preconditioning, incremental rank growth, warm starts and true-residual checks. `Auto` selects the backend. |
| Mixed-integer LP | Branch-and-bound with LP relaxations, incumbent retention, feasibility-checked rounding, node/gap limits and explicit unresolved outcomes. |
| Separable convex QP | Scalar/keyed diagonal curvature, weighted squared deviations, linear and constant terms, bound/fixed-variable transformations, and concave maximization. QP stationarity, gap and recession checks extend both Newton backends. |
| Coupled convex QP | Sparse sum-of-squares factors with cross-variable terms and a structural positive-semidefinite guarantee, including rank-deficient factors. Exact lifting uses the separable solver without collecting or inverting a dense Hessian. |
| Verifiable evidence | Optional typed infeasibility certificates and unbounded directions, named rows and keyed bound multipliers, feasible points when available, and an independent verifier. Quadratic rays must have zero curvature along their direction. |
| Progress and stopping | Driver callbacks for initialization, setup, inner solves and completed outer iterations; cooperative deadlines/cancellation, optional stagnation detection and best-feasible-candidate retention. Candidate availability and stop reasons are explicit. |
| Results and resources | Scalar values or values joined to the original domain, constraint activity/slack/presolve diagnostics, residuals and objective values in the user's sense. `close()` releases materialized results and evidence; temporary caches are cleaned up automatically. |
| Examples and benchmarks | Blending, transportation, two-stage production, set partitioning, Sudoku and MPS import examples; reproducible CPU backend campaigns, QP comparisons, and an optional GPU investigation with complete success/failure records. |

## Build and use 1.5.0

Use **JDK 11** and **Scala 2.12**. Spark is provided by the application. This release
configuration builds seven Spark variants: **2.4.8, 3.0.2, 3.1.3, 3.2.4, 3.3.2,
3.4.2 and 3.5.3**. Artifact versions are `<spark-version>_<library-version>`.

Build the Spark 3.5.3 artifact locally:

```sh
sbt 'spark-lpSpark_3_52_12/publishLocal'
```

Then use it in an application with Spark 3.5.3 on its classpath:

```scala
libraryDependencies += "com.github.vbmacher" %% "spark-lp" % "3.5.3_1.5.0"
```

These coordinates describe the 1.5.0 build; `publishLocal` makes it available on
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
`weightedBy`; `sumBy` and `lpSumBy` create grouped constraints. See the
[usage guide](docs/usage.adoc) for complete examples.

## Quadratic objectives

With a Spark session in scope, separable weighted deviations use the same model:

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

`QpObjective.separable(diagonal, linear)` expresses
`0.5 * sum(q_i*x_i^2) + c^T*x + k`, including keyed curvature from Spark columns.
For sparse cross-variable terms, use factors:

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
[progress and result handling](docs/usage.adoc).

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
- Integer search is sequential on the driver and can grow exponentially. Numerical
  tolerances and iteration/node limits do not guarantee a solution to every model.
- **Production GPU acceleration is not included.** The optional OpenCL experiment
  found no hardware FP64 support on the tested Apple GPU, and FP32 failed the
  accuracy target. The [GPU report](benchmarks/gpu/README.md) records the measured
  deferral and a proposed multi-vendor approach with optional backends and CPU
  fallback. This local result does not rule out other GPUs; ND4J, CUDA and OpenCL
  are not core dependencies.

## Documentation and validation

- [Usage, installation and API vocabulary](docs/usage.adoc)
- [Algorithm, backend selection and scaling limits](docs/algorithm.adoc)
- [Quadratic programming](docs/quadratic-programming.md)
- [Certificate and ray verification](docs/certificates.md)
- [Runnable examples](examples/README.md)
- [CPU benchmarks](benchmarks/README.md), [QP comparisons](benchmarks/quadratic/README.md)
  and [GPU investigation](benchmarks/gpu/README.md)

Run the supported Spark matrix with `sbt +test`. For Spark 3.5 only:

```sh
sbt 'spark-lpSpark_3_52_12/test' 'examplesSpark_3_5/compile'
```

Tests cover LP/MIP behavior, both Newton backends, independently checked QP
solutions and KKT witnesses, transformed variables, certificate verification,
progress/stopping and resource cleanup. Benchmarks retain failed attempts as well
as successful results; timing alone is not evidence of equal numerical accuracy.

Originally forked from [Ehsan M. Kermani's spark-lp](https://github.com/ehsanmok/spark-lp),
which accompanies his thesis,
[Distributed linear programming with Apache Spark](https://open.library.ubc.ca/cIRcle/collections/ubctheses/24/items/1.0340337).
