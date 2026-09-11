# Matrix-free LP results (issue #26)

**Full local campaign running; distributed validation pending. Auto is unchanged.**
This report supports [#25](https://github.com/vbmacher/spark-lp/issues/25) and
[#26](https://github.com/vbmacher/spark-lp/issues/26). It does not declare #26 complete.

## Reproduction and provenance

See [the frozen protocol](matrix-free-lp-plan.md). Candidate base:
`2a37d9d82b493527794eb58415e937469834698a`, with the accompanying diagnostics/tests/harness.
The exact working-tree source fingerprint is
`4b38be4ffe591b53f7f795b063143a5e699a1a4bcb579875de6689b740ec8c5c`.
An immutable candidate source archive, historical source tree and diagnostic patch,
per-application command vectors, environment manifests, raw JSONL and Spark event logs
are retained in [the local artifact directory](../../benchmarks/artifacts/issue26-20260911/).
These are local retained artifacts, not externally published downloads.

```sh
python3 scripts/benchmarks/run.py \
  --output "$PWD/benchmarks/artifacts/issue26-20260911" \
  --classpath "$PWD/target/benchmarks/issue26-smoke/classpath.txt"
```

That directory is immutable to the launcher; choose a new output path to reproduce.
Omit `--classpath` to compile and export the candidate classpath automatically.
The command log for this execution is `/tmp/issue26-campaign.log`.

## Executed validation

- Focused suites: **45 tests passed** on Spark 3.5.3 before the final telemetry additions.
- Complete matrix including those additions: **1,134 passed**, 162 tests on each of
  Spark 2.4.8, 3.0.2, 3.1.3, 3.2.4, 3.3.2, 3.4.2 and 3.5.3; 20 suites per axis.
  Exact versions are also defined in `project/SparkLibs.scala`.
- Python analysis: **3 tests passed** (accuracy gating, pair eligibility, event attribution).
- Harness smoke: **10/10 measured core solves passed independent original-LP checks**.
- Bounded DSL smoke: **10/10 measured solves passed**, compiled dimensions 3 rows / 4
  columns, with 2 generated bound rows and 2 slack columns; known solution (2,1), value 4.
- Smoke timings are harness validation only and do not establish a crossover.

Full matrix and focused logs are retained under the artifact directory's `validation/`.
The smoke run is in `target/benchmarks/issue26-smoke/` and is disposable build output.

## Local campaign status and decision

The versioned inventory has **120 cases**, with **1,440 planned measured repetitions**:
Cholesky and candidate CG for every case; historical CG for the 24 primary and 24
numerical-difficulty cases. Each backend batch also has one excluded warmup. Two
additional bounded DSL batches each have one warmup and five measured solves.

[Live machine-readable summary](../../benchmarks/artifacts/issue26-20260911/summary.json)
and [timing table](../../benchmarks/artifacts/issue26-20260911/summary.md) update after
each application completes. Missing future batches are pending, not successful.
The final `SHA256SUMS` is generated after all applications finish.

The historical baseline uses the unregularized implementation at `d0e4939`. Its
final primal, dual and gap are independently verified using a recorded diagnostic-only
callback patch. Its unavailable inner/rank/phase counters remain null or empty.

Recommendation recorded for this campaign: **retain its existing Auto policy**
(Cholesky through 1000 rows; CG above, subject to the DSL's lower resource cap).
A new crossover is not established by the smoke or a partially completed campaign.
Shape screening uses seed 11; any policy-changing shape finding needs seeds 29/47.
Distributed validation remains pending cluster access, as requested. Local measurements
alone cannot establish a production cluster crossover.

The current implementation subsequently raised Auto's cutoff to 10000 rows for the
EMR allocation workload; see [the allocation investigation](../../benchmarks/allocation.md).
That policy change is separate from this campaign's recorded results and protocol.
