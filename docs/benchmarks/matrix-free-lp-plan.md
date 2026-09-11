# Matrix-free LP campaign protocol (issue #26), version 1

Frozen before measurements. Candidate base: `2a37d9d`; pre-regularization base:
`d0e4939`. Candidate runs use the working-tree source digest recorded by the launcher.
Historical CG runs cover every primary and numerical-difficulty case on `d0e4939`
with only a final-iterate inspection callback added. `prepare_baseline.py` saves
that exact patch and build manifest. Historical inner/rank/phase telemetry is null
or empty because those counters did not exist; do not interpret this as zero work.
Issue #25 already changed Auto to 1000/1001; retain that policy during this campaign.
The old 5000/5001 boundary is tested as a historical/resource-cap regression, not
silently restored. Historical CSVs under `benchmarks/` do not satisfy this protocol.

## Fixtures and final accuracy

`cases.csv` is the versioned, explicit case inventory. Seeds are 11, 29, 47.
For each case, construct sparse equality rows with an independent diagonal basis
and deterministic positive nonbasis coefficients. Each row has the prescribed
number of nonzeros (dense cases use all n columns). Planted x has positive basis
entries and zero nonbasis entries; y has seeded entries in [-1,1]; s is zero on
the basis and positive elsewhere. Set b=Ax, c=A^T y+s. This certifies the optimum
b^T y independently. Degenerate cases set every fifth basis x to zero without
removing rank. Wide-scale rows range from 1 to 1e-6. Nearly-dependent cases replace
each second row of a pair by the first plus 1e-4 times the second (full rank).
These are prescribed controls, not measured condition numbers. Row transformations
can increase actual row sparsity, which is recorded. SHA-256 covers dimensions,
sorted sparse column entries, b, c, and planted witnesses as binary doubles/integers.

All algorithms use outer limit 100, eta=.999, final tolerance 1e-8; explicitly
listed secondary cases use 1e-6. CG uses relative tolerance 1e-10, 1000 steps per
rank, adaptive rank, 256 MiB factor budget, Rp=Rd=1e-8. Cholesky is the existing
unregularized reference. Independently compute from the actual converged x,y,s:

```
primal = ||Ax-b||2 / (1+||b||2)
dual = ||A^T y+s-c||2 / (1+||c||2)
gap = |c^T x-b^T y| / (1+|b^T y|)
```

Require finite residuals below the requested tolerance, nonnegative x,s within
tolerance, and normalized error from the planted optimum below tolerance.
Residual recomputation uses original sparse data and no production vector helpers.
It runs through a package-private converged-iterate inspection hook; its time is
recorded separately and excluded from solve time. Failed/stopped runs never qualify.

## Isolation, resource budget, and measurements

Local: JDK 11, Spark 3.5.3, Scala 2.12.20, local[4], eight input partitions,
4 GiB heap, BLAS threads fixed to one. An additional 8 GiB sweep is explicitly
listed. One same-case warmup and five measured repetitions per backend, fresh
JVM/Spark application for every case/backend batch. Alternate which backend runs
first between case/seed batches. Recreate, cache, and materialize identical inputs
before every solve; unpersist all solve/input RDDs afterwards. Generation and
materialization are outside solve timing. No concurrent benchmark JVMs.

30 minutes per solve, enforced by a daemon watchdog that flushes a Timeout record
and halts the JVM (remaining repetitions are marked Unrun by the launcher). OOM,
process failure, numerical failure, iteration limit, accuracy failure, resource
exclusion and unrun remain separate. Launcher also bounds a complete batch.
Cholesky excludes cases with estimated 16*m*m bytes exceeding half the driver heap;
this is a declared resource exclusion, never a speedup. CG scale extensions run.

Store JSONL per repetition and uncompressed Spark event logs. Event logs provide
jobs/stages/tasks, shuffle read/write bytes, task JVM GC milliseconds and task peak
execution memory, associated with the solve's job group. Task peak execution memory
is not executor process RSS. Sample driver JVM heap and Linux VmRSS every 20 ms;
record JVM/OS/CPU/RAM/BLAS class, thread settings, Spark configuration, commit,
source digest, fixture digest, preparation/solve/validation time, outer and inner
steps, maximum rank, observed rank events, and progress phase intervals. Initialization
is elapsed time before the first iteration-1 phase. Phase timings include framework
overhead and are distinct from task CPU time. CG restart counts are instrumented at actual recurrence-cycle boundaries, including
rank escalation; rank events and phase transitions are recorded separately.

## Commands

From repository root (JDK 11):

```sh
sbt 'spark-lpSpark_3_52_12/testOnly *NewtonSystemSuite *NewtonSuite *InitializeSuite *LPSuite *LpDslSolverSuite *LpDslInfeasibilitySuite'
sbt 'set Global / concurrentRestrictions := Seq(Tags.limitAll(2))' '+test'
python3 scripts/benchmarks/run.py --output /absolute/artifact/directory --smoke
python3 scripts/benchmarks/run.py --output /absolute/artifact/directory
python3 scripts/benchmarks/analyze.py /absolute/artifact/directory
```

The launcher records an exact Java argument vector for every application. Equivalent
single-batch sbt command (output directory must already exist):

```sh
sbt 'spark-lpSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.benchmarks.MatrixFreeBenchmark /absolute/output /absolute/cases.csv primary-100-10-20-well-11-1e-08-4 cholesky 8'
```

For cluster packaging and launch see `scripts/benchmarks/cluster.sh`. Target profile:
4-core/8-GiB driver, four 4-core/8-GiB executors, 64 input partitions, dynamic
allocation and speculation disabled. Record actual topology and native BLAS before
execution. Distributed validation is pending cluster access; no production crossover
can be inferred from this local campaign.

## Analysis and completion

Analyze measured repetitions only; report success counts over all scheduled runs,
median/min/max times, and paired speedups only for matching fixture hash, tolerance,
heap, environment and repetition where both backends pass independent accuracy.
Every inventory/backend/repetition must be present, including exclusions and unrun.
Keep event logs and raw JSONL together with manifest and SHA256SUMS; commit compact
summaries and artifact references. Missing historical-backend or distributed runs
remain explicit completion gaps. No claim of a completed #26 until all required
evidence is available. Do not change Auto based on a single successful row-count case.

Bounded DSL smoke: minimize x+2y subject to x+y=3 and 0<=x,y<=2, known
optimum (2,1), objective 4. Record actual compiled dimensions (3 rows, 4 columns),
two bound rows and two slack columns. One warmup plus five repetitions per backend;
compilation and end-to-end timing are separate from the core crossover measurements.
The launcher runs it after the numerical campaign.

Secondary shape screening uses seed 11 as requested. Any case used to justify a new
policy requires follow-up seeds 29 and 47; otherwise retain the current policy.
