# Daily allocation workload

The original issue #25 benchmark does not establish an EMR crossover for allocation
LPs. Its CG runs needed only 10–56 inner iterations and never escalated beyond Jacobi.
The allocation fixture here has overlapping conserved scopes, zero-cost allocation
factors, and positive/negative error variables for soft share targets. Constraints
and variables belong to individual days within one global LP.

This is synthetic input, not captured production data. Each day has 30 allocation
cells, 12 independent conservation equations and 24 soft targets. Cell masses vary
from 1 to 1000, factors have a 1e-5 floor, and objective weights sum to one over all
target-days. Error variables make every target attainable as a soft penalty; the
original factors provide a feasible allocation. Targets compete within conserved
scopes, and some categories have no supporting cells.

## Observations

All runs used `tolerance=1e-8`, `maxIter=50`, CG relative tolerance `1e-10`, default
CG step/rank budgets, and `Rp=Rd=1e-8`. Spark 3.5.3, Scala 2.12.20, sbt 1.10.7,
Corretto 11.0.26, Java fallback BLAS/LAPACK, `local[4]`, four cached input partitions,
4 GiB forked heap, on the host described in [environment.txt](environment.txt).
Each row is one fresh forked JVM run, without a separate warmup. Other local
build/test processes overlapped portions of the measurements, particularly the
large baseline. These are diagnostic observations, not controlled EMR speedups.

| Days | Equality rows | Variables | Implementation | Status | Seconds | Outer iterations | Spark jobs |
|---:|---:|---:|---|---|---:|---:|---:|
| 8 | 288 | 624 | #25 Cholesky | Converged | 5.02 | 18 | 264 |
| 8 | 288 | 624 | #25 CG | IterationLimit | 140.49 | 50 | 16,521 |
| 152 | 5,472 | 11,856 | #25 Cholesky | Converged | 637.61 | 24 | 348 |
| 152 | 5,472 | 11,856 | Block Cholesky + executor allocation | Converged | 18.04 | 24 | 348 |

The CG pilot reached rank 288 and 559 inner iterations, but its final gap was
3.74e-8, above the requested tolerance. Its timing is **not** an equal-accuracy
comparison. The two large Cholesky runs converged at the same tolerance, with
objectives differing by 5.2e-13. Final primal, dual and gap residuals for the new
path were 1.87e-11, 1.58e-15 and 2.62e-9. Full measurements are in
[allocation.csv](allocation.csv).

## Changes and limits

Cholesky now detects exact contiguous independent blocks in each weighted Gramian.
Every nonzero off-diagonal entry, however small, prevents a split across its row/column
interval. Each block is factored with the existing LAPACK routine. Predictor and
corrector use those same factors. General connected matrices remain a single block.
The LP, regularization policy, convergence checks and publication criteria do not change.

For daily blocks of at most 36 rows, factorization costs at most
`152 * O(36^3)` instead of `O(5472^3)`. Detection still scans the packed Gramian in
`O(m^2)`. Interleaved independent blocks are conservatively kept together; this
optimization does not reorder rows or infer application-specific groups.

The Gramian accumulator is allocated on executors instead of serialized as a dense
zero in the task closure. The baseline logged a 228.5 MiB task-binary broadcast on
every Gramian aggregation; the new run emitted no large-task-binary warnings.
Dense Gramian aggregation, network reduction and `O(m^2)` memory requirements remain.

Auto now selects Cholesky through 10,000 equality-form rows, and the DSL's default
`maxLocalConstraints` is also 10,000. A lower explicit resource cap still switches
Auto to CG earlier. A 10,000-row cap has an approximate 1.49 GiB
related driver-allocation estimate (`16*m*m`), plus Spark, cached data, and other
runtime memory. Executors also need their packed aggregation buffers. Raising
the resource cap above 10,000 does not increase Auto's selection cutoff; select
`NewtonSolver.Cholesky` explicitly to use it above that cutoff.

On 2026-09-11, the driver log for EMR cluster `j-09990035TAK9SJTA0YO`, step
`s-10479491UWZLM0KYIN2`, reported 2,899 model cells and 2,154 targets. Between
15:08:16 and 15:13:30 UTC, CG escalated through ranks 50, 100, 200, 400 and 800.
At the last escalation it had completed 894 steps, with residual `2.88e-3` against
a target of `3.54e-9`. These counts are application inputs, not the compiled equality
row count. The new solver-selection INFO log reports that count and matrix partitions.

This evidence motivates the raised cutoff, but does not prove a production speedup
or establish a universal Auto crossover. CG's matrix products already run on Spark;
its iteration vectors and partial factors stay on the driver. Raising the cutoff
avoids repeated CG jobs, while Cholesky factorization remains driver-local. The
change requires rebuilding the consuming application and starting a new run.

## Reproduce

```sh
sbt -java-home /path/to/jdk11 "spark-lpSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.AllocationBenchmark $PWD/benchmarks/allocation-152.csv 152 cholesky"
sbt -java-home /path/to/jdk11 "spark-lpSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.AllocationBenchmark $PWD/benchmarks/allocation-8-cg.csv 8 cg"
```

To reproduce the baseline, use commit `51f7498` with only `AllocationBenchmark.scala`
copied from this change. Use separate output paths and run comparisons sequentially
on otherwise idle hardware. The harness records original-LP residuals and preserves
iteration-limit and numerical-failure outcomes separately from convergence.

Validation passed all 952 tests (136 on each of the seven supported Spark variants).
Focused regressions compare weighted block solves with explicit matrix solutions,
preserve multiple right-hand sides, prevent splits across tiny/nonadjacent couplings,
and check aggregation with empty partitions and entirely empty inputs.
