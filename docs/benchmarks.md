# Benchmark methodology

The Bencher project is `spark-lp`. Its [public dashboard](https://bencher.dev/perf/spark-lp)
is the primary view for historical measurements, testbeds and regression alerts.

See [benchmark operations](../benchmarks/README.md) for commands and scenario edits.

| Suite | Comparison |
|---|---|
| `smoke` | Tiny deterministic LP, both backends; wiring/correctness only |
| `scaling` | Existing row, column and support-width sweep |
| `parallelism` | Fixed LP and partitions; 1, 2 and 4 local Spark threads |
| `accuracy` | Existing density, conditioning, seed and tolerance matrix |
| `capabilities` | Presolve, starts, all eight MIP policies, QP representations, sparse/dense CPU profiles |
| `kernels` | Packed/full LAPACK factorization; isolated JVM, no Spark |
| `distributed` | Large LPs, partition tuning and fixed/proportional executor sweeps |
| `full` | Union of the above except smoke; includes expensive YARN workloads |

## Measurements

Each scenario runs in a fresh JVM. Warmups are excluded; the default is one warmup
and five measurements. Suite overrides retain the prior DSL/QP sampling counts.
LP timing excludes generation, input preparation, independent validation and cleanup.
DSL timing includes compilation, presolve/search and start preparation, but excludes
final validation. Kernel timing measures factorization only; its solution is checked afterward.
Compare only matching timing scopes, tolerances and testbeds.

| Measure | Unit / interpretation |
|---|---|
| `latency` | Nanoseconds; median, observed minimum and maximum |
| `outer-iterations`, `cg-steps`, `cg-restarts`, `mip-nodes` | Counts; median and observed range |
| `primal-max`, `dual-max`, `gap-max` | Maximum normalized residual across measured runs |
| `objective-error-max` | Maximum absolute objective error divided by `1 + abs(optimum)` |
| `solution-error-max` | Maximum absolute coordinate error for QP/kernel fixtures |
| `speedup` | One-thread median latency / candidate median latency |
| `parallel-efficiency` | Speedup / local thread count; dimensionless, not percent |
| `measured-*-count` | Converged, failed or resource-excluded repetitions |

`lower_value`/`upper_value` are observed ranges, **not confidence intervals**.
Derived ratios have no uncertainty bounds. Partitions describe data layout, not CPUs.
Distributed executor topology is recorded separately; its partition sweeps do not
produce a parallel-efficiency claim. Native threads are pinned to one except for
the explicitly named 1/2/4/8-thread kernel sweep.

LP fixtures retain the seeded SQL generator and planted primal/dual optimum.
Independent checks enforce primal, dual, gap, objective and nonnegativity tolerances.
`wide`, `dependent`, `degenerate` and `dense` change row scaling, dependence, optimal
support and density respectively. DSL fixtures check original-model feasibility and
known optima; MIP optima come from exhaustive enumeration. QP compares separable
and factor forms at the analytic optimum `x_i = 1 + i/n - 1/4`.

A failed warmup or measurement prevents BMF publication; diagnostics retain failures
and unrun slots. Direct-solver memory exclusions retain a count but no timing.
Memory gates are payload estimates, not total-process bounds. Raw JVM memory and
progress observations remain diagnostic artifacts, outside Bencher's main measures.

## Testbeds and CI

Hosted `ubuntu-24.04` runs validate smoke output; their timings are never uploaded.
Publication requires an idle dedicated Linux runner labeled `spark-lp-bench`,
JDK 11 and Spark 3.5. Testbeds are always generated automatically. Local names
fingerprint CPU model/count, memory, JVM, OS/kernel, architecture and native backend
identities, for example `local-linux-aarch64-8cpu-<hash>`.
On EMR, the testbed is automatic: release/AMI, application versions, actual node
roles/types/counts/markets, and driver JVM/OS/architecture/native backend identities
form a fingerprint. Names resemble `emr-7-3-0-r7g-4xlarge-2w-<hash>`; cluster IDs,
hostnames, timestamps and source revisions are excluded so equivalent clusters
share history. The 7-character hash is deterministic: identical detected environments
produce identical names. Keep bootstrap configuration fixed; arbitrary per-node software
changes are not detected, so avoid submitting such experiments as comparable history.
YARN runs require an idle, fixed-size cluster; executor counts/cores and partitions
remain explicit in each scenario. Native BLAS stays opt-in; see [native packages](../native/README.md).

The single workflow runs smoke on PRs and primary-branch changes.
Trusted pushes, weekly runs and manual dispatch publish from the dedicated testbed.
Bencher 0.6.12 invokes the public runner and reads its BMF file directly.
Manual feature branches inherit the `master` start point and thresholds. Latency
alerts use Bencher's upper-tail t-test boundary `0.99`, after at least five historical
reports, using at most 64. Alerts are initially nonblocking; correctness failures
always fail the job. Enable `--error-on-alert` only after assessing baseline stability.

Setup: use the public `spark-lp` Bencher project, set repository variable `BENCHER_PROJECT=spark-lp`,
add secret `BENCHER_API_KEY`, and provision the dedicated runner.
Distributed/full CI additionally needs `spark-submit` and writable HDFS as described
in [distributed runs](../benchmarks/README.md#distributed-runs). No account, token,
testbed or hosted history is created by this refactor.
Local commands also support an ignored `.bencher.env`; see
[local configuration](../benchmarks/README.md#local-bencher-configuration).
GitHub Actions continues to use repository variables and secrets, not that local file.

## Interpretation limits

Bencher is optimized for historical performance-regression plots. Problem-size and
core-count comparisons appear as stable scenario series and measures; it may not
provide every specialized scientific plot.

[Archived evidence](../benchmarks/archive/) retains prior measurements and their
provenance byte-for-byte; it is not automatically uploaded or treated as a current
baseline. Source-dependent historical configuration hashes are not reused for new
identities. Small DSL fixtures do not establish distributed scalability. The retired
GPU experiment lacked FP64 on its tested hardware and failed the FP32 accuracy gate;
there is no supported GPU solver benchmark. Exploratory GPU/QP strategy records
remain archived, without introducing their Python dependencies into the runner.
