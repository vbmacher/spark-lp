# Progress and stopping benchmark

Measured locally on 2026-09-11, Spark 3.5.3, Java 11, `local[4]`, on the synthetic allocation
fixture in `AllocationBenchmark`. The baseline is merged PR #28 (`2826e41`, solver changes
through `c2dad25`). The baseline harness was extended with the same DSL model construction
used in the current harness; its solver was unchanged. Tests were not running during timing.
Each row is one fresh forked JVM run, with inputs materialized before the timer. DSL timing
also includes compilation and result reconstruction. These are diagnostic measurements,
not production input replay or an EMR performance claim. Raw results: [CSV](progress-stopping.csv).

| Scenario | Rows / variables | Seconds | Iterations | Spark jobs | Result |
|---|---:|---:|---:|---:|---|
| #28, core Cholesky | 5,472 / 11,856 | 17.44 | 24 | 348 | Converged |
| Current, controls disabled | 5,472 / 11,856 | 17.46 | 24 | 348 | Converged |
| Current, progress callback | 5,472 / 11,856 | 17.55 | 24 | 348 | Converged; 3,850 events |
| #28, DSL Cholesky | 288 / 624 | 8.29 | 18 | 295 | Optimal |
| Initial implementation, DSL Cholesky | 288 / 624 | 10.45 | 18 | 313 | Optimal |
| Final implementation, DSL Cholesky | 288 / 624 | 8.84 | 18 | 313 | Optimal |
| Current, stop on first feasible outer event | 5,472 / 11,856 | 15.97 | 22 | 321 | Stopped, UserRequested; feasible candidate |
| Current, CG with default opt-in stagnation policy | 288 / 624 | 7.63 | 0 | 551 | Stopped, NoProgress; no candidate |
| Current, CG with a five-second budget | 288 / 624 | 5.01 | 0 | 342 | Stopped, TimeLimit; no candidate |

Core progress reporting added no Spark jobs. With controls disabled or reporting enabled,
Cholesky reached the same objective within `4e-13` of the baseline (`0.17090955332568328`).
The small runtime differences need repeated measurements before attributing them to overhead.

The first feasible Cholesky candidate had objective `0.17090972306623756`, about `1.70e-7`
above the completed solve. Its returned gap was `1.90e-7`, so it was correctly reported as
stopped rather than optimal. This demonstrates intentional stopping, not stagnation detection.

The CG stagnation stop occurred during initialization, after 375 CG steps across right-hand
sides and a maximum completed preconditioner rank of 100. The time-limited run used 223 steps
and rank 50. Neither had a completed outer iterate, so neither returned values or claimed
feasibility. A heuristic stop does not prove that more recovery work could never succeed;
stagnation detection remains opt-in. A time budget is the predictable operational limit.

The initial DSL implementation spent extra time reconstructing original values through joins
for each feasibility check. The final implementation uses aligned compiled columns for plain
bounded variables, including lower-bound shifts and finite upper bounds. It keeps the complete
reconstruction for models with free/fixed variables or merged rows. Validation still adds one
Spark action per outer iteration; reporting itself does not. The additional retained vector is
bounded to one candidate, with replacement and ownership covered by lifecycle tests.
The optimized DSL run took 8.84 seconds versus the baseline's 8.29 seconds, with the same
18 iterations and objective within `4e-13`. Validation accounted for the 18 additional Spark
jobs. This single measurement suggests a smaller cost after eliminating the joins; repeated
EMR measurements with a pinned artifact remain necessary before choosing production settings.

Run from the repository root (output paths must be absolute):

```sh
sbt 'spark-lpSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.AllocationBenchmark /tmp/core.csv 152 cholesky 1e-8 none'
sbt 'spark-lpSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.AllocationBenchmark /tmp/report.csv 152 cholesky 1e-8 report'
sbt 'spark-lpSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.AllocationBenchmark /tmp/dsl.csv 8 cholesky 1e-8 none dsl'
sbt 'spark-lpSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.AllocationBenchmark /tmp/candidate.csv 152 cholesky 1e-8 candidate'
sbt 'spark-lpSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.AllocationBenchmark /tmp/stagnation.csv 8 cg 1e-8 stagnation'
sbt 'spark-lpSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.AllocationBenchmark /tmp/deadline.csv 8 cg 1e-8 time'
```
