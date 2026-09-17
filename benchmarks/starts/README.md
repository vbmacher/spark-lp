# Warm-start benchmark

All 16 samples pass independent original-model validation. The LP start reduces outer iterations from four to three. Its validation and conversion overhead increases total time on this local fixture. The MIP start seeds a feasible incumbent, but does not reduce the 17 searched nodes and adds one relaxation iteration in this knapsack case.

The campaign uses Spark 3.5.3, Scala 2.12.20, Java 11, `local[4]`, four partitions, solver tolerance `1e-8`, and independent validation tolerance `1e-6`. Each case/mode has one excluded warmup and three measured repetitions, with alternating mode order. Runs are isolated from other validation jobs. Total time includes assignment snapshot creation, start validation/conversion, compilation and solving; independent final candidate validation is excluded. [Raw samples](results/records.csv) include warmups; [environment.txt](results/environment.txt) records the runtime.

| Case | Mode | Median seconds | Range seconds | Outer iterations | Nodes | Median start validation seconds |
|---|---|---:|---:|---:|---:|---:|
| LP coordinates | cold | 5.123 | 4.858–5.576 | 4 | — | — |
| LP coordinates | started | 16.550 | 16.235–16.781 | 3 | — | 11.415 |
| MIP knapsack | cold | 20.400 | 20.281–21.252 | 83 | 17 | — |
| MIP knapsack | started | 22.921 | 22.321–23.624 | 84 | 17 | 2.142 |

The LP has 12 blocks, each containing a bounded variable, an upper-only variable and a free variable. Its known optimum is 43, including the objective constant. Regularized CG handles the free-variable formulation. The complete feasible start uses original coordinates and an interior floor of `0.001`. The MIP has six binary items and capacity 12; exhaustive enumeration establishes optimum 27. Both modes use identical tolerances, search limits and objective definitions.

Median snapshot creation time is 0.009 seconds for the LP and 0.010 seconds for the MIP. Start validation remains distributed and checks the original model; its many small Spark stages dominate the LP fixture. These results demonstrate an iteration-count benefit and an elapsed-time regression, without establishing a universal speedup. Separately, the warm-start suite verifies that an accepted MIP start remains available when search stops before any node is processed; a cold stop has no incumbent.

Run from the worktree with a new absolute output directory:

```bash
sbt 'benchmarksSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.StartBenchmark /absolute/new/output'
```

The [runner](../src/main/scala/com/github/vbmacher/spark_lp/StartBenchmark.scala) defines the fixtures and independent checks. No external solver is required.
