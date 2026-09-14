# Coupled QP strategy and measured comparison

`strategy.py` was run before implementing factor lifting. On seed 7000 it compares
a matrix-free lifted normal solve with a dense reference formed only for verification.
For `C=[[A,0,0],[F,-I,I]]`, `W=diag(I,0.5I,0.5I)`, the Schur complement of
`C W C^T` is `A (I+F^T F)^(-1) A^T`. Eliminating auxiliary dual rows therefore
matches the coupled Newton reduction without forming/inverting the Hessian in
production. The local experiment used diagonally preconditioned CG and reached
relative dual errors below `8e-12` in 23–63 iterations. `strategy-results.json`
contains all three cases, including sparse payload/reference dense memory and timings.
This establishes the algebra and a small-case feasibility check, not Spark speedup.

`QpComparison` then compares separable and factor objectives on Spark `local[2]`,
Java 11.0.26, Spark 3.5.3, Scala 2.12.20, Fedora Asahi ARM64, with a 4 GiB forked
heap. The deterministic targets are `t_i=1+i/n`, with `sum(x)=sum(t)-n/4`.
Both representations minimize `sum((x_i-t_i)^2)`, with exact optimum
`x_i=t_i-1/4`, objective `n/16` and identical gradients `-1/2`.

All 16 recorded attempts reached Optimal. Maximum original-value error was
`6.27e-10`, objective error `6.53e-10`; all normalized primal/stationarity/gap
residuals were below `1e-8`. Complete successful and failed-attempt slots are
in `comparison.csv` (no failures occurred in that comparison). Repetition zero is
retained and includes warmup effects. Repetition-one end-to-end timings:

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
Values range from about 1.0 to 3.1 GB. The sparse strategy experiment separately
records representation payload bytes. Other validation was active on the host,
so timings are exploratory rather than isolated scaling results. Large Spark
workload crossover remains unmeasured.

`failed-cholesky-free.txt` retains the failed pre-restriction experiment: free-variable
splits lost positive definiteness after 61 completed iterations. The final API
rejects explicit Cholesky for that configuration; Auto selects regularized CG.
`CoupledQpSuite` covers off-diagonal PSD, rank-deficient PSD, free/shifted/fixed
variables, objective sense, keyed factors, repeated solves, Q=0 and invalid models.

Reproduce from this worktree (NumPy 2.3.5 / SciPy 1.16.3 for the local experiment):

```sh
python3 benchmarks/quadratic/strategy.py
sbt 'spark-lpSpark_3_52_12/testOnly *CoupledQpSuite *QpSuite'
sbt 'spark-lpSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.dsl.QpComparison /tmp/new-qp-comparison.csv'
```

The Scala runner refuses an existing output file and records every attempt,
including exceptions. Solve time includes compilation and reconstruction; independent
original-value checks run after timing. The small dense reference lives solely in
the standalone experiment. No external optimization solver or core dependency was added.
