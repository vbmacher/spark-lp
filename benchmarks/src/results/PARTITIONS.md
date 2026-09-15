# CG partition performance

**Recommendation:** Use explicit `--partitions 16` for these three seed-11 CG fixtures on the tested four-executor environment. Across both comparison orders, median paired speedups versus 64 partitions were 1.44–2.16x; all 90 measured solves and 18 warmups passed independent accuracy checks at 1e-8. This is a workload-specific recommendation; no global partition default, numerical setting or solver algorithm changed.

CG only; seed 11; target width 32. One warmup and five measured attempts in each fresh JVM. Round 1 uses 64/32/16 partitions; round 2 uses 16/32/64 on the same cluster. The larger historical baseline used 128 partitions; every comparison below uses a fresh 64-partition reference.

Fixed environment: four executors with four cores each, 16 GiB driver and executor heaps, 4 GiB executor overhead, one math thread, dynamic allocation and speculation disabled. The primary is r7gd.4xlarge; one r7gd.8xlarge worker hosts all four executors. Actual executor registration, task use and solver input partitions are checked from the captured evidence.

Spark 3.5.3-amzn-0, Java 11.0.32+9-LTS; the driver loaded Java F2jBLAS/F2jLAPACK. Executor BLAS/LAPACK loading was not verified. Solver source is identical to baseline `9208f8e`; the frozen measurement assembly adds benchmark observations, retained in the private source archive. No solver default or numerical algorithm changed.

## Solve results

Times are seconds. Ranges contain all five measured runs; paired speedup divides the matching 64-partition repetition by the candidate repetition within the same fixture and round. Warmups never enter these statistics. Rounds remain separate because timing variability is visible.

| Fixture | Round | Partitions | Validated / measured | Median [min, max] | Paired speedup median [min, max] |
|---|---:|---:|---:|---|---|
| Well-conditioned 1,000 × 100,000 | 1 | 64 | 5 / 5 | 25.089 [23.847, 26.482] | reference |
| Well-conditioned 1,000 × 100,000 | 1 | 32 | 5 / 5 | 22.178 [21.788, 22.642] | 1.129 [1.075, 1.174] |
| Well-conditioned 1,000 × 100,000 | 1 | 16 | 5 / 5 | 15.321 [15.032, 16.435] | 1.589 [1.566, 1.702] |
| Well-conditioned 1,000 × 100,000 | 2 | 16 | 5 / 5 | 14.352 [14.142, 15.851] | 2.164 [1.972, 2.196] |
| Well-conditioned 1,000 × 100,000 | 2 | 32 | 5 / 5 | 21.430 [21.071, 22.400] | 1.449 [1.395, 1.473] |
| Well-conditioned 1,000 × 100,000 | 2 | 64 | 5 / 5 | 31.054 [31.041, 31.254] | reference |
| Well-conditioned 5,000 × 1,000,000 | 1 | 64 | 5 / 5 | 25.519 [25.388, 26.431] | reference |
| Well-conditioned 5,000 × 1,000,000 | 1 | 32 | 5 / 5 | 20.418 [20.075, 22.054] | 1.259 [1.198, 1.270] |
| Well-conditioned 5,000 × 1,000,000 | 1 | 16 | 5 / 5 | 17.834 [17.285, 18.740] | 1.440 [1.383, 1.476] |
| Well-conditioned 5,000 × 1,000,000 | 2 | 16 | 5 / 5 | 17.950 [17.379, 18.695] | 1.522 [1.479, 1.566] |
| Well-conditioned 5,000 × 1,000,000 | 2 | 32 | 5 / 5 | 20.281 [20.036, 21.167] | 1.346 [1.334, 1.369] |
| Well-conditioned 5,000 × 1,000,000 | 2 | 64 | 5 / 5 | 27.561 [27.212, 28.282] | reference |
| Near-dependent 1,000 × 100,000 | 1 | 64 | 5 / 5 | 367.356 [359.904, 376.741] | reference |
| Near-dependent 1,000 × 100,000 | 1 | 32 | 5 / 5 | 267.667 [263.910, 272.415] | 1.358 [1.345, 1.428] |
| Near-dependent 1,000 × 100,000 | 1 | 16 | 5 / 5 | 236.953 [227.615, 242.437] | 1.534 [1.485, 1.625] |
| Near-dependent 1,000 × 100,000 | 2 | 16 | 5 / 5 | 219.101 [214.774, 223.578] | 1.648 [1.626, 1.666] |
| Near-dependent 1,000 × 100,000 | 2 | 32 | 5 / 5 | 254.816 [252.048, 269.249] | 1.410 [1.360, 1.448] |
| Near-dependent 1,000 × 100,000 | 2 | 64 | 5 / 5 | 360.214 [356.165, 368.869] | reference |

## Core solve overhead

All values are measured-run medians. CPU and GC seconds are cumulative observations, not wall time or utilization percentages. Task GC observations can overlap between concurrent tasks. Validation and generation have separate job groups. Task attempts include retries and failures.

| Fixture | Round | P | Jobs | Task attempts | Task p95 ms | Driver CPU s | Executor CPU s | Driver GC s | Task GC s | Shuffle read MiB | Disk spill MiB |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Well-conditioned 1,000 × 100,000 | 1 | 64 | 393.000 | 26894.000 | 10.000 | 32.100 | 27.941 | 0.047 | 1.165 | 80.617 | 0.000 |
| Well-conditioned 1,000 × 100,000 | 1 | 32 | 393.000 | 13782.000 | 10.000 | 25.190 | 18.857 | 0.055 | 0.668 | 56.710 | 0.000 |
| Well-conditioned 1,000 × 100,000 | 1 | 16 | 393.000 | 7390.000 | 13.000 | 21.870 | 13.577 | 0.075 | 0.687 | 34.794 | 0.000 |
| Well-conditioned 1,000 × 100,000 | 2 | 16 | 393.000 | 7390.000 | 12.000 | 21.670 | 12.006 | 0.075 | 0.438 | 34.795 | 0.000 |
| Well-conditioned 1,000 × 100,000 | 2 | 32 | 393.000 | 13782.000 | 10.000 | 24.120 | 18.246 | 0.055 | 0.611 | 56.710 | 0.000 |
| Well-conditioned 1,000 × 100,000 | 2 | 64 | 393.000 | 26894.000 | 10.000 | 32.810 | 28.744 | 0.049 | 0.365 | 80.615 | 0.000 |
| Well-conditioned 5,000 × 1,000,000 | 1 | 64 | 413.000 | 28334.000 | 13.000 | 34.640 | 66.638 | 0.050 | 1.121 | 406.633 | 0.000 |
| Well-conditioned 5,000 × 1,000,000 | 1 | 32 | 413.000 | 14522.000 | 18.000 | 25.670 | 56.916 | 0.055 | 0.979 | 291.087 | 0.000 |
| Well-conditioned 5,000 × 1,000,000 | 1 | 16 | 413.000 | 7790.000 | 27.000 | 22.430 | 52.585 | 0.084 | 0.916 | 180.986 | 0.000 |
| Well-conditioned 5,000 × 1,000,000 | 2 | 16 | 413.000 | 7790.000 | 27.000 | 22.660 | 53.385 | 0.079 | 0.396 | 180.990 | 0.000 |
| Well-conditioned 5,000 × 1,000,000 | 2 | 32 | 413.000 | 14522.000 | 18.000 | 26.140 | 56.910 | 0.052 | 0.765 | 291.083 | 0.000 |
| Well-conditioned 5,000 × 1,000,000 | 2 | 64 | 413.000 | 28334.000 | 13.000 | 35.920 | 69.744 | 0.051 | 1.442 | 406.631 | 0.000 |
| Near-dependent 1,000 × 100,000 | 1 | 64 | 6342.000 | 455246.000 | 9.000 | 485.920 | 619.375 | 0.554 | 11.975 | 993.972 | 0.000 |
| Near-dependent 1,000 × 100,000 | 1 | 32 | 6319.000 | 233059.000 | 10.000 | 340.460 | 351.332 | 0.459 | 14.467 | 671.655 | 0.000 |
| Near-dependent 1,000 × 100,000 | 1 | 16 | 6305.000 | 125642.000 | 10.000 | 284.310 | 215.139 | 0.464 | 3.530 | 401.339 | 0.000 |
| Near-dependent 1,000 × 100,000 | 2 | 16 | 6200.000 | 123542.000 | 10.000 | 256.620 | 210.008 | 0.469 | 2.556 | 389.173 | 0.000 |
| Near-dependent 1,000 × 100,000 | 2 | 32 | 6278.000 | 231542.000 | 10.000 | 334.320 | 339.070 | 0.419 | 2.909 | 663.746 | 0.000 |
| Near-dependent 1,000 × 100,000 | 2 | 64 | 6213.000 | 445958.000 | 9.000 | 469.230 | 563.023 | 0.541 | 7.154 | 957.359 | 0.000 |

## Iterations and phases

Measured-run medians; CG work and attained rank may change with floating-point reduction order. This matters especially for the near-dependent fixture, so its speedup is not attributed only to scheduling. Full ranges and per-attempt phase timings remain in the embedded evidence.

| Fixture | Round | P | Outer iterations | CG steps | Maximum rank | System setup s | Inner solve s |
|---|---:|---:|---:|---:|---:|---:|---:|
| Well-conditioned 1,000 × 100,000 | 1 | 64 | 9.000 | 221.000 | 0.000 | 1.500 | 22.546 |
| Well-conditioned 1,000 × 100,000 | 1 | 32 | 9.000 | 221.000 | 0.000 | 1.371 | 19.876 |
| Well-conditioned 1,000 × 100,000 | 1 | 16 | 9.000 | 221.000 | 0.000 | 0.970 | 13.663 |
| Well-conditioned 1,000 × 100,000 | 2 | 16 | 9.000 | 221.000 | 0.000 | 0.885 | 12.822 |
| Well-conditioned 1,000 × 100,000 | 2 | 32 | 9.000 | 221.000 | 0.000 | 1.330 | 19.206 |
| Well-conditioned 1,000 × 100,000 | 2 | 64 | 9.000 | 221.000 | 0.000 | 1.877 | 27.914 |
| Well-conditioned 5,000 × 1,000,000 | 1 | 64 | 9.000 | 241.000 | 0.000 | 1.593 | 22.899 |
| Well-conditioned 5,000 × 1,000,000 | 1 | 32 | 9.000 | 241.000 | 0.000 | 1.302 | 18.378 |
| Well-conditioned 5,000 × 1,000,000 | 1 | 16 | 9.000 | 241.000 | 0.000 | 1.176 | 15.996 |
| Well-conditioned 5,000 × 1,000,000 | 2 | 16 | 9.000 | 241.000 | 0.000 | 1.172 | 16.129 |
| Well-conditioned 5,000 × 1,000,000 | 2 | 32 | 9.000 | 241.000 | 0.000 | 1.282 | 18.248 |
| Well-conditioned 5,000 × 1,000,000 | 2 | 64 | 9.000 | 241.000 | 0.000 | 1.721 | 24.810 |
| Near-dependent 1,000 × 100,000 | 1 | 64 | 9.000 | 3362.000 | 400.000 | 171.100 | 196.002 |
| Near-dependent 1,000 × 100,000 | 1 | 32 | 9.000 | 3338.000 | 400.000 | 120.245 | 147.117 |
| Near-dependent 1,000 × 100,000 | 1 | 16 | 9.000 | 3327.000 | 400.000 | 108.211 | 128.319 |
| Near-dependent 1,000 × 100,000 | 2 | 16 | 9.000 | 3222.000 | 400.000 | 98.423 | 120.346 |
| Near-dependent 1,000 × 100,000 | 2 | 32 | 9.000 | 3297.000 | 400.000 | 115.645 | 139.413 |
| Near-dependent 1,000 × 100,000 | 2 | 64 | 9.000 | 3236.000 | 400.000 | 169.750 | 191.261 |

## Preparation, validation and cost

Estimated Linux on-demand EC2 plus EMR compute rate: **$4.082375/hour** for the two nodes. Primary EC2/EMR rates are $1.088600/$0.272150 per hour; worker rates are $2.177300/$0.544325. The private pricing manifest retains regional price-list rows, effective dates and checksums. Rates exclude EBS, S3, transfer, tax, discounts and credits. [EC2 pricing](https://aws.amazon.com/ec2/pricing/on-demand/) and [EMR pricing](https://aws.amazon.com/emr/pricing/).

Generation is observed once per JVM batch; preparation and validation are medians per measured attempt. Step duration includes application startup, warmup, all measured attempts, generation, validation, cleanup and artifact handling. Estimated step cost divides the entire step duration at the fixed node rate by the number of independently validated measured solves. It excludes cluster startup and inter-step time. **These are cost estimates; invoice-level billed runtime was not available.**

| Fixture | Round | P | Generation s/batch | Preparation s | Validation s | Step seconds | Estimated step USD/success |
|---|---:|---:|---:|---:|---:|---:|---:|
| Well-conditioned 1,000 × 100,000 | 1 | 64 | 11.577 | 0.783 | 1.361 | 226.200 | 0.051 |
| Well-conditioned 1,000 × 100,000 | 1 | 32 | 10.361 | 0.576 | 0.949 | 186.085 | 0.042 |
| Well-conditioned 1,000 × 100,000 | 1 | 16 | 10.786 | 0.300 | 0.727 | 144.080 | 0.033 |
| Well-conditioned 1,000 × 100,000 | 2 | 16 | 10.403 | 0.270 | 0.727 | 138.073 | 0.031 |
| Well-conditioned 1,000 × 100,000 | 2 | 32 | 10.786 | 0.532 | 0.879 | 184.076 | 0.042 |
| Well-conditioned 1,000 × 100,000 | 2 | 64 | 10.941 | 0.891 | 1.326 | 244.081 | 0.055 |
| Well-conditioned 5,000 × 1,000,000 | 1 | 64 | 12.243 | 1.109 | 1.788 | 216.084 | 0.049 |
| Well-conditioned 5,000 × 1,000,000 | 1 | 32 | 10.696 | 0.682 | 1.182 | 180.076 | 0.041 |
| Well-conditioned 5,000 × 1,000,000 | 1 | 16 | 10.193 | 0.593 | 0.964 | 162.068 | 0.037 |
| Well-conditioned 5,000 × 1,000,000 | 2 | 16 | 9.868 | 0.406 | 0.947 | 160.068 | 0.036 |
| Well-conditioned 5,000 × 1,000,000 | 2 | 32 | 9.914 | 0.644 | 1.186 | 174.068 | 0.039 |
| Well-conditioned 5,000 × 1,000,000 | 2 | 64 | 11.598 | 0.995 | 1.871 | 228.076 | 0.052 |
| Near-dependent 1,000 × 100,000 | 1 | 64 | 12.463 | 0.670 | 1.293 | 2278.169 | 0.517 |
| Near-dependent 1,000 × 100,000 | 1 | 32 | 10.146 | 0.342 | 0.847 | 1666.149 | 0.378 |
| Near-dependent 1,000 × 100,000 | 1 | 16 | 10.468 | 0.488 | 0.829 | 1476.147 | 0.335 |
| Near-dependent 1,000 × 100,000 | 2 | 16 | 10.631 | 0.368 | 0.685 | 1398.130 | 0.317 |
| Near-dependent 1,000 × 100,000 | 2 | 32 | 10.537 | 0.368 | 0.813 | 1626.132 | 0.369 |
| Near-dependent 1,000 × 100,000 | 2 | 64 | 11.338 | 0.685 | 1.302 | 2268.158 | 0.514 |

Cluster creation-to-ready time: **225.290 seconds**, reported separately from solves.
Cluster creation-to-termination time: **13415.896 seconds**. This includes provisioning and is not an invoice billing measurement.

## Validation and evidence

Validation of the producing snapshot: 1,204 solver tests across seven supported Spark versions, 11 benchmark tests and 8 Python analysis tests passed. The producing instrumentation and analysis code are retained in the private source/evidence archive. The original baseline bundle remains unchanged.

Comparison validation issues: **0**. Every success requires independent normalized primal/dual residuals, gap and objective error below `1e-8`, finite residuals, and primal/slack nonnegativity within tolerance. Missing and unsuccessful attempts remain explicit.

[Benchmark report](REPORT.md) and [Bencher data](data/) contain all 18 partition campaigns alongside the other measurements. Each `cg-partitions-*.bmf.json` file retains batch/build/layout/executor observations under `_evidence.campaign.partition_comparison` and per-attempt CPU, GC, phase and task metrics under `_evidence.records[].raw`. Raw logs, source archives, build/launch manifests and private deployment details are retained in durable storage; published references contain relative filenames and checksums.

Seeds 29 and 47, different executor counts, other input shapes, native libraries and backend selection remain untested by these measurements. The observed gains do not establish a universal partition count or backend threshold.

Maximum driver observations across measured attempts: heap **6.993 GiB**, RSS **8.032 GiB**. These sampled attempt peaks include independent validation; they are observations, not exact allocation peaks or memory bounds.

Event analysis for batches 17 uses an S3 Select projection of every field consumed by the analyzer. The projection was checked for exact agreement with full-log analysis on batch 0. For these batches, event SHA-256 values identify the retained projection; original object sizes and S3 ETags identify the durable raw logs. An S3 multipart ETag is not a SHA-256 digest. Queries, projection fields, checksums and the extraction script are retained with the private evidence.

Executor memory observations: 72 per-batch process observations; maximum observed heap **5.986 GiB**, RSS **7.144 GiB**. These are whole-application stage peaks sampled every 1,000 ms, including generation, warmup, preparation and validation. They are not exact core-solve peaks or memory bounds. Per-executor values remain in each campaign's embedded evidence.
