# MIP search benchmark

[All benchmark reports](README.md) · [Study source](mip-search/README.md)

Evidence coverage: **64 samples and 562 progress events**.


All 64 samples reach the independently enumerated original-model optima and pass independent candidate validation. All recorded global bounds bound those optima, and incumbent progress is monotonic. The campaign covers the serial baseline, binary cover cuts, bounded strong branching, two concurrent nodes, and all combinations.


- **Cover cuts:** six-item nodes fall from 17 to 7, but median time rises from 18.448 to 28.167 seconds. The runs generate both globally valid root covers and node-local covers.
- **Strong branching:** four-item nodes fall from 5 to 3 and median time from 5.875 to 5.159 seconds. On six items, bounded probes reduce only one node and increase time to 25.122 seconds.
- **Parallel search:** two active LPs reduce median time to 2.717 seconds on four items and 8.866 seconds on six items, with the same baseline node counts. Combining every option is slower than the baseline on both fixtures; on four items its measured node count varies from 3 to 4.

Spark 3.5.3, Scala 2.12.20, Java 11 and `local[4]` use four partitions. Each case/mode has one excluded warmup and three measured repetitions, with reversed mode order on alternate repetitions. Solver tolerance is `1e-8`; independent original-model validation uses `1e-6`. Each mode uses the same objective, constraints and tolerances. Timing includes compilation and search, including cut separation, probes, callback output and memory sampling; final independent validation is excluded.

Cuts allow one round per searched node, four inherited/new cuts per node and eight additions per search. Strong branching considers two candidates with eight iterations per probe and eight probes total. Parallel modes allow two concurrent LP relaxations; all modes allow 256 searched nodes. The baseline uses most-fractional branching without cuts or probes.

| Case | Mode | Median seconds (range) | Nodes (range) | Median relaxations | Median seconds to first incumbent |
|---|---|---:|---:|---:|---:|
| knapsack4 | baseline | 5.875 (3.826–6.579) | 5–5 | 5 | 3.172 |
| knapsack4 | cuts | 6.674 (6.024–9.557) | 3–3 | 5 | 3.485 |
| knapsack4 | strong | 5.159 (5.100–8.780) | 3–3 | 7 | 4.618 |
| knapsack4 | cuts+strong | 5.826 (5.772–9.914) | 2–2 | 5 | 5.378 |
| knapsack4 | parallel | 2.717 (2.520–4.313) | 5–5 | 5 | 1.396 |
| knapsack4 | cuts+parallel | 6.657 (6.561–7.857) | 3–4 | 6 | 4.345 |
| knapsack4 | strong+parallel | 4.963 (4.875–8.679) | 3–4 | 7 | 2.949 |
| knapsack4 | cuts+strong+parallel | 13.004 (11.926–13.193) | 3–4 | 11 | 9.913 |
| knapsack6 | baseline | 18.448 (16.624–19.700) | 17–17 | 17 | 9.860 |
| knapsack6 | cuts | 28.167 (25.394–28.713) | 7–7 | 12 | 12.342 |
| knapsack6 | strong | 25.122 (25.055–26.452) | 16–16 | 24 | 15.493 |
| knapsack6 | cuts+strong | 25.711 (24.608–27.155) | 3–3 | 12 | 25.094 |
| knapsack6 | parallel | 8.866 (8.111–9.105) | 17–17 | 17 | 4.866 |
| knapsack6 | cuts+parallel | 16.503 (16.056–17.173) | 7–7 | 12 | 8.523 |
| knapsack6 | strong+parallel | 15.956 (14.812–16.844) | 15–15 | 23 | 12.306 |
| knapsack6 | cuts+strong+parallel | 23.245 (22.688–23.741) | 4–4 | 13 | 22.037 |

The four-item case has weights `[2,3,4,5]`, profits `[3,4,5,8]`, capacity 8 and objective constant 7, giving optimum 19. The six-item case has weights `[2,3,4,5,7,9]`, profits `[4,5,7,8,11,13]`, capacity 12 and the same constant, giving optimum 27. Exhaustive enumeration checks all 16 and 64 binary assignments. These are small control/search-overhead fixtures, not evidence of large-MIP scalability.

Time to first incumbent uses the search clock, which starts after compilation. Raw progress records include processed/open nodes, incumbent, global bound and absolute gap. Node counts exclude cut re-solves and strong probes; relaxation and iteration counts include that work. Parallel completion order can change node counts and timing.

| Case | Mode | Peak heap MiB (range) | Peak process RSS MiB (range) | Peak concurrent LPs | Maximum Newton estimate MiB |
|---|---|---:|---:|---:|---:|
| knapsack4 | baseline | 2648.7–3086.7 | 4017.9–4024.4 | 1 | 0.001 |
| knapsack4 | cuts | 2807.0–2850.5 | 4017.5–4024.2 | 1 | 256.001 |
| knapsack4 | strong | 2820.8–3062.6 | 4017.2–4023.9 | 1 | 0.001 |
| knapsack4 | cuts+strong | 2775.3–2908.0 | 4016.9–4023.8 | 1 | 256.001 |
| knapsack4 | parallel | 2678.4–2850.8 | 4016.5–4023.5 | 2 | 0.002 |
| knapsack4 | cuts+parallel | 2776.1–2902.2 | 4015.8–4022.8 | 2 | 512.002 |
| knapsack4 | strong+parallel | 2691.5–2934.6 | 4014.2–4022.2 | 2 | 0.002 |
| knapsack4 | cuts+strong+parallel | 2724.4–2794.4 | 4012.4–4021.8 | 2 | 512.002 |
| knapsack6 | baseline | 3067.8–3131.3 | 4065.4–4072.6 | 1 | 0.002 |
| knapsack6 | cuts | 2842.5–2932.9 | 4065.2–4072.4 | 1 | 256.001 |
| knapsack6 | strong | 3108.1–3133.9 | 4064.8–4072.3 | 1 | 0.002 |
| knapsack6 | cuts+strong | 2799.5–2934.2 | 4064.2–4074.0 | 1 | 256.001 |
| knapsack6 | parallel | 3063.8–3130.2 | 4064.0–4072.0 | 2 | 0.003 |
| knapsack6 | cuts+parallel | 2750.5–2886.1 | 4035.6–4071.9 | 2 | 512.002 |
| knapsack6 | strong+parallel | 3046.9–3096.4 | 4030.9–4071.7 | 2 | 0.003 |
| knapsack6 | cuts+strong+parallel | 2815.1–2894.0 | 4030.4–4071.4 | 2 | 512.002 |

Memory is sampled every 50 ms and can miss shorter peaks. Heap and RSS are absolute process values, including Spark, its local executor, a 4 GiB JVM heap, caches and preceding samples in the same JVM; they are not allocation deltas or remote-executor measurements. RSS comes from Linux `/proc/self/status`; the runner reports zero when it is unavailable on another OS. The Newton estimate is a separate conservative working-storage budget, including the configured 256 MiB CG preconditioner allowance per active relaxation. It excludes baseline process, distributed matrix, shuffle and other metadata memory.

Cut generation, strong branching and concurrency each have distinct unit/integration coverage in `MipCoverCutsSuite` and `MipSearchSuite`: exhaustive cut validity, local-domain rejection, numerical rejection, incomplete probes, coordinated bounds/incumbents, node/memory caps, cancellation with a retained start, callback failures and worker/cache cleanup. The benchmark demonstrates all eight combinations; the raw measurements include regressions and do not establish a universal speedup.

[Records](mip-search/results/records.csv), [bound/incumbent progress](mip-search/results/progress.csv), [environment](mip-search/results/environment.txt) and [source hashes](mip-search/results/source-provenance.json) preserve the evidence. The campaign runs alone, with unchanged compiled classes and no simultaneous tests or builds. Source hashes identify the complete library implementation and runner used for measurement.

Reproduce from this worktree using a new absolute output directory:

```bash
sbt 'benchmarksSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.MipSearchBenchmark /absolute/new/output'
```

The [runner](../src/main/scala/com/github/vbmacher/spark_lp/MipSearchBenchmark.scala) defines the fixtures, controls, memory sampler and independent checks. The [algorithm guide](../../docs/algorithm.adoc) documents the supported cover family, bound acceptance and references.
