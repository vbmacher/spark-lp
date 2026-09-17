# Benchmarks

This module measures Cholesky and CG on reproducible linear programs generated with Spark DataFrames. Campaigns are CSV case inventories; all algorithm suites share the same generation, execution and validation code.

- [Measured results](src/results/REPORT.md)
- [CG partition performance and recommended settings](src/results/REPORT.md#cg-partition-tuning)
- [AArch64 native netlib package and factorization comparison](native/README.md)
- [Bencher data format](#bencher-data-format)
- [Case inventories](src/main/resources/)

## Structure

| Location | Responsibility |
|---|---|
| [spark_lp/Benchmark.scala](src/main/scala/com/github/vbmacher/spark_lp/Benchmark.scala) | Algorithm contract, registry and the Cholesky/CG instances |
| [spark_lp/BenchmarkRunner.scala](src/main/scala/com/github/vbmacher/spark_lp/BenchmarkRunner.scala) | CLI parsing and `run(benchmark, config)` API |
| [support/BenchmarkCase.scala](src/main/scala/com/github/vbmacher/spark_lp/support/BenchmarkCase.scala) | Shared CSV schema, parsing and case validation |
| [support/DataGenerator.scala](src/main/scala/com/github/vbmacher/spark_lp/support/DataGenerator.scala) | Distributed coefficients, witnesses, input adapters and accuracy checks |
| [support/JvmSampler.scala](src/main/scala/com/github/vbmacher/spark_lp/support/JvmSampler.scala), [SolveMeasurements.scala](src/main/scala/com/github/vbmacher/spark_lp/support/SolveMeasurements.scala), [RuntimeEnvironment.scala](src/main/scala/com/github/vbmacher/spark_lp/support/RuntimeEnvironment.scala) | JVM sampling, timing, progress phases, environment capture and watchdog |
| `src/main/resources/*.csv` | Inputs for local and EMR runs, all one schema |
| `src/results/data/*.bmf.json` | Bencher metrics per campaign and testbed, with failure and exclusion counts |
| `scripts/` | Campaign launch, artifact analysis, normalization and report generation |

Benchmarks explicitly select Cholesky or CG (not the `Auto` policy) so comparisons identify the algorithm used. Adding an algorithm needs a `Benchmark` implementation and a registry entry; the generator and campaigns stay shared.

## Case design

Every input CSV has this header:

```csv
id,m,n,nonzeros_per_row,family,seed,tolerance,heap_gib
example,1000,10000,32,well,11,1e-8,4
```

| Column | Meaning |
|---|---|
| `id` | Unique case identifier, passed to the runner |
| `m`, `n` | Equality rows and nonnegative variables; `0 < m < n` |
| `nonzeros_per_row` | Target support before dependent-row expansion; includes the diagonal basis entry |
| `family` | `well`, `wide`, `dependent`, `degenerate`, or `dense` |
| `seed` | Seed for deterministic SQL hash expressions generating coefficients/witnesses |
| `tolerance` | Maximum accepted normalized primal/dual/gap/objective error |
| `heap_gib` | Planned driver heap, passed to the JVM |

The generator builds a diagonal basis in the first `m` columns; each sparse row adds up to `nonzeros_per_row-1` distinct nonbasis entries at a seed-dependent cyclic offset, so `nnz = m * min(nonzeros_per_row, n-m+1)` for ordinary sparse families. Structural zero columns stay as variables.

| Family | Modification and purpose |
|---|---|
| `well` | Diagonal basis plus moderate positive coefficients; reference sparse case |
| `wide` | Multiplies row `i` by `10^(-6*i/(m-1))`; tests uneven row scaling |
| `dependent` | Replaces each odd row by its predecessor plus `1e-4` times itself; tests near dependence (count actual nonzeros) |
| `degenerate` | Zeroes every fifth basis variable at the optimum; tests degenerate optima |
| `dense` | Generates all `m*n` coefficients; ignores the sparse target |

Known witnesses satisfy `x >= 0`, `s >= 0` and `x[j]*s[j]=0`. The generator sets `b=A*x` and `c=Aᵀ*y+s`, giving a feasible primal-dual pair with known optimum `bᵀ*y`; validation re-evaluates the returned solution against these equations.

Generation uses `spark.range`, SQL expressions, joins and aggregations. Coefficients and `n`-length vectors stay distributed and cached with spill support; only scalar statistics and the `m`-length RHS/dual vectors reach the driver. Choose enough partitions for the widest cases. A deterministic fingerprint over coefficients and witnesses gives a reproducibility check, not a cryptographic digest — floating-point reduction order can affect the last bits of `b` and `c`.

| Inventory | Question |
|---|---|
| `solver-scaling.csv` | How do solve time and memory change as rows, variables and support grow? |
| `sparsity-and-conditioning.csv` | How do density, scaling, near dependence and degeneracy affect convergence and cost? |
| `emr-scaling.csv` | How do the algorithms behave on larger distributed problems? See the distributed results and widest-run telemetry in the measured report. |

## Build and run

Run from the repository root with Java 11 and sbt 1.10.7. The module uses Spark 3.5.3 / Scala 2.12.20 and the matching `spark-lp` project. Spark is `Provided`, so local execution uses `Test/runMain`. Timed suites are plain objects and do not run under `sbt test`. Fixture and backend regression tests run with `sbt 'benchmarksSpark_3_52_12/test'`; published data is checked with `python3 benchmarks/scripts/report.py check` and `python3 benchmarks/scripts/bencher_export.py --check`.

```sh
sbt 'benchmarksSpark_3_52_12/Test/compile'
mkdir -p /tmp/lp-scaling-cholesky
sbt 'benchmarksSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.BenchmarkRunner cholesky /tmp/lp-scaling-cholesky /absolute/path/to/spark-lp/benchmarks/src/main/resources/solver-scaling.csv rows-100-vars-1000-width-8 8 5 1'
mkdir -p /tmp/lp-scaling-cg
sbt 'benchmarksSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.BenchmarkRunner cg /tmp/lp-scaling-cg /absolute/path/to/spark-lp/benchmarks/src/main/resources/solver-scaling.csv rows-100-vars-1000-width-8 8 5 1'
```

Replace the absolute repository path. Arguments are `benchmark output-directory inventory.csv case-id partitions [repetitions=5] [warmups=1]`; the output directory must be fresh, existing and absolute, and warmups is 0 or 1. These commands run the first scaling case with one warmup and five measured attempts per algorithm.

To run both algorithms sequentially in fresh JVMs:

```sh
python3 benchmarks/scripts/run.py --campaign solver-scaling --output /absolute/artifacts/scaling-run
python3 benchmarks/scripts/run.py --campaign sparsity-and-conditioning --output /absolute/artifacts/conditioning-run
```

The launcher refuses to overwrite a run, pins BLAS to one thread, alternates backend order and preserves source snapshots, commands, environments, exits, event logs and JSONL measurements. It excludes an oversized local Cholesky workspace before launch and limits each solve to 30 minutes; missing repetitions stay `Unrun`. Keep bulky artifacts outside this module.

### EMR through AWS CLI

Use AWS CLI credentials with access to the cluster and S3 prefix. The primary node needs AWS CLI, Bash, Python 3, `timeout`, `sha256sum` and `spark-submit`, and its instance role needs read/write on the artifact prefix. The local launcher needs `jq` and sbt, or a prebuilt assembly via `--jar`.

Create a dedicated cluster with `aws emr create-cluster` or reuse an idle one. This example uses existing IAM roles, a chosen subnet and EMR 7.6.0; replace the parameters and size instances for the intended campaign. See [AWS CLI cluster creation](https://docs.aws.amazon.com/cli/latest/reference/emr/create-cluster.html).

```sh
export AWS_DEFAULT_REGION=us-east-1
export BENCHMARK_S3_PREFIX=s3://your-bucket/spark-lp-benchmarks
export BENCHMARK_SUBNET=subnet-REPLACE
export BENCHMARK_SERVICE_ROLE=EMR_DefaultRole
export BENCHMARK_INSTANCE_PROFILE=EMR_EC2_DefaultRole
BENCHMARK_CLUSTER_ID=$(aws emr create-cluster \
  --name spark-lp-benchmarks --release-label emr-7.6.0 --applications Name=Spark \
  --service-role "$BENCHMARK_SERVICE_ROLE" \
  --ec2-attributes "InstanceProfile=$BENCHMARK_INSTANCE_PROFILE,SubnetId=$BENCHMARK_SUBNET" \
  --instance-groups InstanceGroupType=MASTER,InstanceType=m5.2xlarge,InstanceCount=1 \
    InstanceGroupType=CORE,InstanceType=m5.2xlarge,InstanceCount=4 \
  --log-uri "$BENCHMARK_S3_PREFIX/emr-logs/" --no-auto-terminate \
  --query ClusterId --output text)
aws emr wait cluster-running --cluster-id "$BENCHMARK_CLUSTER_ID"

bash benchmarks/scripts/cluster.sh \
  --region "$AWS_DEFAULT_REGION" --cluster-id "$BENCHMARK_CLUSTER_ID" \
  --s3-prefix "$BENCHMARK_S3_PREFIX/runs" --benchmark cg \
  --case emr-rows-1000-vars-100000-width-32
```

The launcher builds the assembly, uploads the inventory and source archive to a unique run prefix, captures cluster/instance metadata, and submits one `command-runner.jar` step via `aws emr add-steps`. The step stages local inputs on the primary node and runs `BenchmarkRunner` in YARN client mode with driver heap from the CSV row. Use `--help` for executor, partition and repetition arguments; run algorithms sequentially on one idle cluster. Cholesky is excluded if its estimated payload exceeds half either heap. See [AWS CLI step submission](https://docs.aws.amazon.com/cli/latest/reference/emr/add-steps.html) and [EMR command runner](https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-commandrunner.html).

Add `--dry-run --jar /absolute/path/benchmarks-assembly.jar` to inspect the step JSON without AWS calls or a build. A prebuilt jar is hashed, but its source digest and commit stay unrecorded. A launcher build records the checkout commit, a source archive (including uncommitted changes) and its SHA-256; the commit reaches driver measurements as `implementation_sha`.

Submission prints the step ID and S3 run URI. Monitor and download with AWS CLI:

```sh
aws emr describe-step --cluster-id "$BENCHMARK_CLUSTER_ID" --step-id s-REPLACE
aws s3 cp s3://your-bucket/spark-lp-benchmarks/runs/RUN-ID/ /absolute/artifacts/emr-run/ --recursive
# After completing the planned batch and checking uploaded artifacts:
aws emr terminate-clusters --cluster-ids "$BENCHMARK_CLUSTER_ID"
```

Reliable solver checkpoints use `checkpoints/` under the local output directory or the EMR run prefix. For a custom cluster, set `--conf spark.checkpoint.dir=<distributed-uri>`; the runner passes it to `SparkContext.setCheckpointDir`. Retain checkpoints until the run finishes.

Raw measurements, environment, command, application log and exit status upload under `results/`; Spark event logs under `events/`, submission inputs under `input/`. The step fills missing repetitions with the process failure followed by `Unrun` slots, preserves an existing watchdog failure and a nonzero exit, and attempts the upload on failure too. A whole-application timeout bounds work to `(warmups + repetitions) × 30 minutes + 10 minutes`. Node loss or forced termination can prevent uploads — reconcile missing attempts against step/container logs before importing. The measured report includes per-executor memory observations derived from Spark event logs.

## Results and environments

For the measured seed-11, width-32 CG fixtures (1,000 × 100,000 well-conditioned
and near-dependent; 5,000 × 1,000,000 well-conditioned), use `--partitions 16`
with four four-core executors. Both comparison orders showed 1.44–2.16× median
paired speedups against 64 partitions at the same `1e-8` accuracy. See the
[CG partition tuning](src/results/REPORT.md#cg-partition-tuning) section for the
recommendation, method, paired speedups and limits; other workloads and executor counts need their own measurements.

```sh
python3 benchmarks/scripts/report.py import-jsonl /absolute/artifacts/scaling-run --campaign solver-scaling-new-run --output benchmarks/src/results
python3 benchmarks/scripts/report.py render
python3 benchmarks/scripts/report.py check
```

The importer writes self-contained Bencher Metric Format files under `src/results/data/` and refuses an existing campaign ID. Embedded evidence keeps every attempt's dimensions, algorithm/suite, configuration, environment, repetition, status, timing, accuracy, memory observations and source path/hash/line, so the report can preserve partial batches and validate aggregate BMF metrics without a second CSV. Published results omit infrastructure identifiers, provider metadata and storage locations. `check` verifies BMF metrics against captured attempts and the Markdown report against the same bundle.

The report groups only homogeneous cases/configurations, excludes warmups, reports successful solve-time median/range, and shows failure duration where nothing converged; no failure becomes a successful timing. Constant columns move above each table; ID and Env stay explicit. The environment table describes the recorded machine, not the one rendering Markdown. Case inventories in `src/main/resources/` define the inputs; planned cases and missing repetitions are not successful measurements. Separate [QP validation](quadratic/README.md) and [GPU status](gpu/README.md) reports contain their own evidence.

## Bencher data format

The [Bencher JSON adapter](https://bencher.dev/docs/explanation/adapters/#-json) fits this custom Spark harness: it accepts [Bencher Metric Format (BMF)](https://bencher.dev/docs/reference/bencher-metric-format/) with multiple numeric measures per benchmark, unlike the JMH adapter which would require JMH output and drop this harness's timing protocol. BMF is the public result format; embedded evidence preserves per-attempt detail that aggregate measures cannot express, and the Markdown report is generated from this bundle.

The single [data directory](src/results/data/) holds self-contained BMF files. Each benchmark's `measured-records-count` metric carries an `_evidence` extension with its campaign, case, configuration, captured attempts and provenance; executor observations use the same extension on `executor-observations-count`. The extension is allowed by the BMF schema and ignored by Bencher's [numeric metric parser](https://github.com/bencherdev/bencher/blob/v0.6.12/lib/bencher_json/src/project/metric/mod.rs); our tools use it to verify the measures. Keep these files for audit — Bencher stores only the numeric metrics. No separate manifest or CSV dataset is required.

Check or regenerate BMF files from the retained observations — no Spark, extra Python deps, Bencher account or uploads:

```sh
python3 benchmarks/scripts/bencher_export.py --check
python3 benchmarks/scripts/bencher_export.py
```

Use `--data DIRECTORY` to read another bundle, or `--output DIRECTORY` to write a copy containing `data/`. `--check` detects missing/unexpected files and verifies every BMF value against the evidence. File links are in [REPORT.md](src/results/REPORT.md).

| Exported measures | Meaning |
|---|---|
| `latency` | Validated measured solve time in **nanoseconds**, median with observed minimum/maximum bounds; these are not confidence intervals |
| `outer-iterations`, `cg-steps`, `cg-restarts`, `rank-escalations`, `maximum-rank` | Median and min/max over validated measured runs, when recorded for every such run |
| `primal-max`, `dual-max`, `gap-max`, `objective-error-max` | Maximum normalized errors over validated measured runs |
| `measured-*-count`, `warmup-*-count` | Captured records, attempted, converged, failed, unrun, resource-excluded and unknown counts; pending/missing evidence is unknown |
| `unsuccessful-duration-ns` | Observed unsuccessful measured durations, separated from latency |
| `driver-*-bytes-max`, `combined-process-*-bytes-max` | Maximum captured heap/RSS bytes, including validation; local Spark uses combined driver/executor process samples |
| `executor-*-bytes-max` | Each executor's whole-application heap/RSS/JVM non-heap peaks, in a separate observation file |

Each BMF file belongs to one campaign and one testbed derived from the recorded environment, topology, partition count and driver heap. Benchmark names include the exact configuration ID; different configurations and retry campaigns are never pooled. Missing observations are omitted, never zero-filled. Warmups and unsuccessful/unrun/excluded records never contribute latency. Executor observations lack an exact configuration/testbed reference, so they stay explicitly unmapped rather than joined by case name.

After [installing the optional Bencher CLI](https://bencher.dev/docs/how-to/install-cli/), preview a file using the testbed suffix after `--` in its filename (`executor-memory-unmapped` for the executor observation file):

```sh
bencher run --adapter json --dry-run --project spark-lp-preview \
  --branch captured-snapshot --testbed TESTBED --file FILE.bmf.json
```

The dry run builds a request and may contact the API for a version check; it does not upload results or validate the server-side adapter. Validate BMF independently against the [official schema](https://bencher.dev/v0/bmf.json). Interactive plots need a Bencher Cloud or self-hosted project. For future regression tracking, first establish stable benchmark/testbed identities and build provenance: configuration hashes may change with source/runtime settings, and the export checkout's HEAD is not the measured solver revision — supply the actual producing revision and observation date rather than the CLI's HEAD/time defaults.

## Memory

For `m` rows, `n` variables, `z=nnz`, `E` executors, `q` active tasks/executor and actual preconditioner rank `r`, the report uses these payload allowances:

```text
C = (12*z + 104*n)/E
P = 4*m*(m+1)
Cholesky executor = C + 2*q*P + 8*q*m     O((z+n)/E + q*m²)
Cholesky driver   = 16*m*m + 64*m        O(m²)
CG executor       = C + 32*q*m          O((z+n)/E + q*m)
CG driver         = 64*m + 32*m*r       O(m + m*r)
```

`C` assumes sparse values/indices, column references and twelve `n`-double vectors. `P` is one packed triangle; reductions can hold two per task. CG fetches one Gramian column at a time, so executor scratch scales with `m` and partial factors stay on the driver. At fixed local topology the executor bounds simplify to `O(z+n+m²)` and `O(z+n+m)`. These are payload estimates, not measured peaks or rigorous bounds — extra caches, DataFrame/JVM objects, shuffle buffers, native memory and retained garbage are additional. Missing topology/rank prevents an estimate. Values are MiB (`2^20` bytes).

Local Spark shares one JVM for driver and executor, so RSS samples cover both; distributed runs measure each executor and the driver separately. The generator adds distributed DataFrame/cache/shuffle costs and `O(m)` driver vectors but does not collect the full matrix or `n`-length solutions. Both driver and executor allowances must fit with headroom before a large direct run.

The EMR launcher enables Spark executor metrics polling every 1,000 ms, process-tree RSS metrics and per-stage executor peak logging, retained in Spark event logs separately from the runner's driver samples. The [report](src/results/REPORT.md#executor-memory-observations) includes derived executor observations and documents their timing scope.
