# Matrix-free LP results (issue #26)

**Full local campaign running; distributed validation pending. Auto is unchanged.**

This report supports [#25](https://github.com/vbmacher/spark-lp/issues/25) and
[#26](https://github.com/vbmacher/spark-lp/issues/26). See the [frozen protocol](matrix-free-lp-plan.md).

## Execution and provenance

- Candidate base: `2a37d9d82b493527794eb58415e937469834698a`.
- Candidate source SHA-256: `4b38be4ffe591b53f7f795b063143a5e699a1a4bcb579875de6689b740ec8c5c`.
- Started UTC: `2026-09-11T12:53:39Z`; finished UTC: `pending`.
- Recorded measured repetitions: **234/1440**; successful: **234**.
- Scheduled completion rate: **16.25%** (includes pending/excluded runs in denominator).
- [Retained local artifacts](../../benchmarks/artifacts/issue26-20260911/): source archive, historical diagnostic patch/build,
  environment manifests, exact Java commands, raw JSONL, event logs, and Spark task metrics.
- [Detailed timing table](../../benchmarks/artifacts/issue26-20260911/summary.md), [machine-readable summary](../../benchmarks/artifacts/issue26-20260911/summary.json).
- These are retained local artifacts, not externally hosted downloads.
- Historical CG: `d0e4939`, primary and difficulty families, diagnostic-only final-iterate callback.
  Its manifest declares inner/rank/phase telemetry unavailable. Historical `phase_seconds`
  is an uninstrumented whole-run placeholder and must not be interpreted as initialization time.
- `SHA256SUMS` is generated only after the launcher finishes.

```sh
python3 scripts/benchmarks/run.py --output /absolute/new/artifact/directory
python3 scripts/benchmarks/analyze.py /absolute/artifact/directory
python3 scripts/benchmarks/report.py /absolute/artifact/directory --output docs/benchmarks/matrix-free-lp-results.md
```

The launcher refuses to overwrite existing runs. The original launch used the verified
`target/benchmarks/issue26-smoke/classpath.txt` via `--classpath`; omitting that flag builds first.

## Correctness and harness validation

- Focused Spark 3.5.3 suites: **45 passed** before final telemetry additions.
- Complete matrix with telemetry: **1,134 passed** across 7 axes:
  Spark 2.4.8, 3.0.2, 3.1.3, 3.2.4, 3.3.2, 3.4.2, and 3.5.3.
- Python analysis tests: **3 passed** (accuracy gating, pair eligibility, event attribution).
- Core harness smoke: **10/10 measured solves passed independent original-LP checks**.
- Bounded DSL smoke: **10/10 measured solves passed**; 3 compiled rows, 4 columns,
  including 2 bound rows and 2 slack columns; known optimum (2,1), objective 4.
- Focused/matrix logs: [../../benchmarks/artifacts/issue26-20260911/validation/](../../benchmarks/artifacts/issue26-20260911/validation/).
- Compact smoke records are retained under `validation/smoke/`; smoke timings are not crossover evidence.

## Recorded states

| State | Measured repetitions |
|---|---:|
| Success | 234 |
| Pending records | 1206 |

## Equal-accuracy timing

Only paired repetitions passing every independent residual, nonnegativity, and known-objective
check enter ratios. Ratios greater than one favor candidate CG. Medians pool seeds and
shape/family variants within the displayed group; the detailed table preserves each case.
A pooled value is not evidence that every shape has the same crossover.

| Family | m | Heap GiB | Tolerance | Cholesky s | CG s | Historical CG s | Candidate pairs / median Cholesky÷CG | Historical pairs / median old÷new CG |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| primary | 100 | 4 | 1e-08 | 2.983 | 9.194 | 8.440 | 15 / 0.335 | 15 / 0.909 |
| primary | 500 | 4 | 1e-08 | 4.245 | 13.453 | 13.636 | 15 / 0.311 | 15 / 1.029 |
| primary | 1000 | 4 | 1e-08 | 5.776 | 16.476 | 17.650 | 15 / 0.334 | 15 / 1.061 |
| primary | 2500 | 4 | 1e-08 | 32.117 | 31.249 | 33.734 | 15 / 1.033 | 15 / 1.062 |
| primary | 4000 | 4 | 1e-08 | 106.848 | 43.814 | 47.316 | 15 / 2.438 | 15 / 1.062 |
| primary | 5000 | 4 | 1e-08 | 200.256 | 48.395 | — | 4 / 4.102 | 0 / — |

## Decision and limits

**Recommendation recorded for this campaign: retain its existing Auto policy:** Cholesky through
1000 equality-form rows, CG above, with the DSL allowed to lower the resource cap.
The observed small cases favor Cholesky, so these measurements do not support making
matrix-free the unconditional default. No new production crossover is selected here.

The current implementation subsequently raised Auto's cutoff to 10000 rows for the
EMR allocation workload; see [the allocation investigation](../../benchmarks/allocation.md).
That policy change is separate from this campaign's recorded results and protocol.

The candidate uses JDK 11 / Spark 3.5.3 / Scala 2.12.20, local[4], eight fixed input
partitions, and Java fallback BLAS/LAPACK. The 8 GiB sweep is separate from the 4 GiB
baseline. Task peak execution memory is not executor RSS; phase intervals include
framework overhead. The input fixture remains resident in each backend JVM.

Distributed validation is pending cluster access, as requested. Shape screening uses
seed 11; findings used to change policy require follow-up seeds 29 and 47. Resource
exclusions and unrun repetitions are never interpreted as measured speedups.
A single row threshold must hold across the tested shape, sparsity, and difficulty
envelope before it can be recommended. #26 is not declared complete while required
execution or policy evidence remains pending.
