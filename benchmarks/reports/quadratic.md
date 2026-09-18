# Coupled QP validation

[All benchmark reports](README.md) · [Study source](quadratic/README.md)

Evidence coverage: **16 Spark attempts and 3 lifted-system cases**.


## Lifted system validation

`strategy.py` uses seed 7000 to validate a matrix-free lifted normal solve against a dense reference formed only for verification.
For `C=[[A,0,0],[F,-I,I]]`, `W=diag(I,0.5I,0.5I)`, the Schur complement of
`C W C^T` is `A (I+F^T F)^(-1) A^T`. Eliminating auxiliary dual rows therefore
matches the coupled Newton reduction without forming/inverting the Hessian in
production. The validation records show relative dual errors below `8e-12` in 23–63
diagonally preconditioned CG iterations. [`strategy-results.json`](quadratic/strategy-results.json) contains all three cases, including sparse payload/reference dense memory and timings.
Validation covers the algebra and small-case feasibility; Spark performance requires
end-to-end measurements.

## Spark validation

`QpComparison` evaluates separable and factor objectives on Spark `local[2]`,
Java 11.0.26, Spark 3.5.3, Scala 2.12.20, Fedora Asahi ARM64, with a 4 GiB forked
heap. The deterministic targets are `t_i=1+i/n`, with `sum(x)=sum(t)-n/4`.
Both representations minimize `sum((x_i-t_i)^2)`, with exact optimum
`x_i=t_i-1/4`, objective `n/16` and identical gradients `-1/2`.

**Status: 16/16 attempts Optimal, zero failures.** Maximum original-value error is
`6.27e-10`, objective error `6.53e-10`; all normalized primal/stationarity/gap
residuals are below `1e-8`. Complete attempt records are in
[`comparison.csv`](quadratic/comparison.csv). Repetition zero is retained and includes warmup effects. Repetition-one end-to-end timings:

| Variables | Backend | Separable, s | Factors, s |
|---:|---|---:|---:|
| 4 | Cholesky | 1.547 | 2.002 |
| 4 | CG | 1.411 | 2.245 |
| 12 | Cholesky | 2.340 | 5.201 |
| 12 | CG | 1.928 | 5.579 |

The factor path adds `2n` columns and `n` equality rows in this comparison.
The specialized separable path is preferable for diagonal curvature. Reported
`peak_heap_pool_bytes` sums reset per-pool JVM heap peaks; it includes shared-JVM
retention and is not a per-model memory allocation or simultaneous live-heap peak.
Values range from about 1.0 to 3.1 GB. The sparse strategy records include
representation payload bytes. Timing evidence includes concurrent validation workloads and supports exploratory profiling only.
Large Spark workload crossover is unmeasured.

## Conditioning and backend restrictions

Coupled models with free variables require regularized CG; Auto selects it and
explicit Cholesky is rejected. The [conditioning record](quadratic/failed-cholesky-free.txt)
documents a Cholesky positive-definiteness failure after 61 completed iterations
for a transformed free-variable model.
`CoupledQpSuite` covers off-diagonal PSD, rank-deficient PSD, free/shifted/fixed
variables, objective sense, keyed factors, repeated solves, Q=0 and invalid models.

## Run validation

Run from the repository root. The standalone strategy environment uses NumPy 2.3.5
and SciPy 1.16.3:

```sh
python3 benchmarks/reports/quadratic/strategy.py
sbt 'spark-lpSpark_3_52_12/testOnly *CoupledQpSuite *QpSuite'
sbt 'spark-lpSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.dsl.QpComparison /tmp/new-qp-comparison.csv'
```

The Scala runner refuses an existing output file and records every attempt,
including exceptions. Solve time includes compilation and reconstruction; independent
original-value checks run after timing. The small dense reference lives solely in
the standalone experiment. The core solver has no dependency on this experiment
or an external optimization solver.
