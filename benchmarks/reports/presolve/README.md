# Presolve benchmark

All 24 samples pass independent original-model validation. Full presolve removes all solver rows and columns in the fixed-row and singleton-column fixtures. Its preparation overhead exceeds the solve-time savings on these small local models, so Basic remains the default.

The campaign uses Spark 3.5.3, Scala 2.12.20, Java 11, `local[4]`, four partitions, tolerance `1e-8`, and independent validation tolerance `1e-6`. Each case/mode has one excluded warmup and three measured repetitions; mode order alternates. Timings include compilation and presolve, and exclude independent candidate validation. The measured campaign runs without concurrent validation jobs. Runtime details are in [environment.txt](results/environment.txt); all raw samples, including warmups, are in [records.csv](results/records.csv).

| Case | Mode | Median seconds | Range seconds | Solver columns | Solver rows |
|---|---|---:|---:|---:|---:|
| fixed_rows | off | 1.840 | 1.727–1.925 | 24 | 24 |
| fixed_rows | full | 4.138 | 3.975–4.335 | 0 | 0 |
| singleton_columns | off | 3.030 | 2.976–3.032 | 48 | 24 |
| singleton_columns | full | 6.367 | 6.003–6.460 | 0 | 0 |
| irreducible | off | 1.950 | 1.925–1.958 | 24 | 13 |
| irreducible | full | 4.082 | 3.908–4.450 | 24 | 13 |

The fixed-row fixture has 12 bounded variables fixed by singleton equalities. The singleton-column fixture has 12 bounded variables and 12 free, zero-cost variables defined by separate equalities. The irreducible fixture has 12 bounded variables, distinct costs and one sum equality; full presolve retains its solver dimensions. Known objective values are 19, 7 and 28 respectively. These cases quantify reduction behavior and local overhead; they do not establish cluster-scale performance or a universal speedup.

Run from the repository worktree, supplying a new absolute output directory:

```bash
sbt 'benchmarksSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.PresolveBenchmark /absolute/new/output'
```

The [runner](../../src/main/scala/com/github/vbmacher/spark_lp/PresolveBenchmark.scala) defines every fixture and verifies the retained candidate against the original model. No external solver package is required.
