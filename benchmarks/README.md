# Newton backend benchmarks (issue #25)

For the subsequent allocation workload investigation and exact block-Cholesky
optimization, see [allocation.md](allocation.md). Those measurements expose a
CG escalation case absent from the synthetic crossover grid below; this grid
does not establish the preferred backend for that EMR workload.
The timings below are historical measurements of commit `51f7498`, before block
Cholesky and executor-side accumulator allocation. Reproduce that grid on that
revision; the subsequent optimization changes direct-backend costs. Auto's cutoff
is unchanged, so the allocation recommendation uses an explicit backend choice.

Both backends solve the same LPs with `tolerance=1e-8`, `maxIter=50`, `eta=0.999`;
CG uses `cgTolerance=1e-10`, `cgMaxIterations=1000`, `Rp=Rd=1e-8` and the default
256 MiB adaptive preconditioner budget. Only runs whose original-LP primal, dual
and objective-gap residuals are all below `1e-8` qualify for timing comparisons.

The selected shared `Auto` policy uses Cholesky through **1000 equality-form rows**
and CG above that. The DSL additionally honors a lower `maxLocalConstraints` resource
cap; its default resource cap remains 5000. Explicit Cholesky remains available above
the performance cutoff. Increasing that resource cap alone does not change `Auto`.

At 1000 rows, the well-scaled and narrower scaled cases favored CG, but the wider
scaled case favored Cholesky (1.60 vs 1.94 seconds median). At 1500 rows that same
scaled family favored CG (3.55 vs 1.66 seconds). Choosing 1000 as the last direct size
keeps the reference backend in the measured mixed region. It is a conservative default
based on these workloads; the exact crossing between sampled row counts is unmeasured,
and native BLAS, sparsity, conditioning, variable count and cluster latency can move it.

Run from the repository root with JDK 11:

```sh
sbt "spark-lpSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.NewtonBenchmark $PWD/benchmarks/results.csv"
python3 benchmarks/summarize.py benchmarks/results.csv
```

The harness uses Spark's existing test dependencies. Its arguments are the output
CSV (absolute path because sbt project-matrix uses a synthetic working directory), optional comma-separated `m:variable_multiplier:nonzeros_per_column:row_scale_ratio`
cases, and repeat count (default 2). For example:

```sh
sbt "spark-lpSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.NewtonBenchmark $PWD/benchmarks/crossover.csv 512:2:1:1,512:4:8:0.001,768:2:4:1,768:4:8:0.001,1000:2:4:1,256:4:256:0.001 3"
sbt "spark-lpSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.NewtonBenchmark $PWD/benchmarks/conditioning.csv 1000:4:8:0.001,1500:4:8:0.001 3"
python3 benchmarks/summarize.py benchmarks/results.csv benchmarks/crossover.csv benchmarks/conditioning.csv
```

## Workload and measurement contract

There are `n = multiplier*m` nonnegative variables. Column `j` of `A` has a unit
entry at `j mod m` and `width-1` cyclic entries of magnitude `0.05/width`.
Row `i` is scaled by `ratio^(i/(m-1))`. Thus width controls sparsity and the row
scale ratio introduces up to six orders of magnitude of spread in the initial
normal-equations diagonal. The first `m` columns have a strictly dominant diagonal.

A known solution has the first `m` variables equal to 1 and the rest zero.
Its dual is the all-ones vector. Define `b=A*x`, and `c=A^T*y+s`, with zero slack
on the first `m` variables and unit slack on the others. This provides a feasible
primal/dual optimum with objective `sum(b)` independently of either backend.
The CSV records the absolute difference from that known objective.

These are synthetic, structured LPs, including symmetric easy cases. Row scaling
tests conditioning but does not cover every difficult spectrum, real application,
or cluster. The focused Newton tests separately cover near-dependent rows, multiple
optima, zero RHS rows, dependent feasible rows, and certificate behavior.

Each backend first receives a 16-row warmup. Timed repeats alternate backend order. The main grid uses two repeats; the crossover and conditioning grids use three.
The input RDDs are cached and materialized before timing; timing includes solver
initialization, all Newton iterations, and release of the returned solution.
Spark job counts cover that same interval. Outer iterations, total CG iterations
(including restarts/escalations), and maximum actual factor rank come from solver
instrumentation. A zero rank means Jacobi was sufficient, not an omitted measurement.

Heap and resident process memory are sampled every 20 ms during each run. In
`local[4]`, they include the driver, local Spark executors, cached input and runtime
objects. There is no forced GC between cases, so retained heap/RSS can carry over;
these are sampled total process peaks, not isolated per-backend allocation costs.
For isolated memory comparisons, run one case/backend in a fresh Spark deployment.
The preconditioner budget covers factor storage/workspace, not the whole JVM.

`NumericalFailure`, `IterationLimit`, certificates, and stopped runs remain separate
CSV statuses and are excluded from successful timing pairs. On exceptions, unavailable
residuals are `NaN`, and inner/rank counters are `NA`; completed outer iterations and
the exception are retained. Fatal process failures cannot produce a solver summary
and must be reported separately if they occur. `summarize.py` rejects any claimed
successful row with invalid final residuals.

See [environment.txt](environment.txt) for the fixed recorded environment. Native
BLAS/LAPACK was unavailable, so the direct backend used its Java fallback. A cluster
with native linear algebra and higher Spark scheduling latency can cross over later.

## Recorded results

All **76 timed runs converged** at the same original-LP tolerance; there were no
numerical failures, certificates, or iteration limits in this benchmark grid.
Runs took four or five outer iterations. CG took 10–56 total inner iterations and required
only Jacobi (maximum partial-Cholesky rank 0). The focused tests separately force
rank escalation and validate pivoted factors against explicit matrices.

The largest final primal, dual and gap residuals across all timed runs were
`2.7e-10`, `5.96e-15`, `7.33e-09`, respectively.
The CSV files retain every residual, known-objective error, iteration count, Spark-job
count, sampled heap/RSS peak, status and wall time. The table uses median seconds;
ratios above 1 favor CG. It is generated by the supplied summarizer.

| m | n | nnz/column | min/max row scale | Cholesky seconds | CG seconds | Cholesky/CG |
|---:|---:|---:|---:|---:|---:|---:|
| 32 | 64 | 1 | 1.0 | 0.726 | 1.186 | 0.61 |
| 256 | 1024 | 8 | 1.0 | 0.717 | 0.998 | 0.72 |
| 256 | 1024 | 256 | 0.001 | 0.997 | 1.398 | 0.71 |
| 512 | 1024 | 1 | 1.0 | 0.983 | 0.995 | 0.99 |
| 512 | 2048 | 8 | 0.001 | 0.767 | 1.749 | 0.44 |
| 768 | 1536 | 4 | 1.0 | 1.127 | 0.892 | 1.26 |
| 768 | 3072 | 8 | 0.001 | 1.061 | 1.826 | 0.58 |
| 1000 | 2000 | 4 | 0.001 | 1.648 | 1.409 | 1.17 |
| 1000 | 2000 | 4 | 1.0 | 1.539 | 0.986 | 1.56 |
| 1000 | 4000 | 8 | 0.001 | 1.599 | 1.937 | 0.83 |
| 1500 | 6000 | 8 | 0.001 | 3.552 | 1.660 | 2.14 |
| 4500 | 9000 | 1 | 1.0 | 71.003 | 1.228 | 57.83 |
| 5000 | 10000 | 4 | 1.0 | 96.771 | 1.146 | 84.44 |
| 5500 | 11000 | 1 | 0.001 | 128.551 | 1.132 | 113.60 |
| 6500 | 26000 | 8 | 1.0 | 211.889 | 1.263 | 167.80 |

Nonconverged runs: 0

## Validation

The implementation passed all 924 tests: 132 per Spark version across 2.4.8 and
3.0.2–3.5.3. The examples compiled in the same build. Run the matrix with:

```sh
sbt 'set Global / concurrentRestrictions := Seq(Tags.limitAll(2))' '+test'
```

Focused tests check explicit regularized products, initialization and recovered KKT
directions, updated pivot order, preconditioner application, rank escalation, memory
caps, a 65,536-row matrix-free system, original-LP convergence, and certificates.
