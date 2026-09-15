# Benchmarks

This module measures Cholesky and CG on reproducible linear programs generated with Spark DataFrames. Campaigns are CSV case inventories; algorithm suites share the same generation, execution, and validation code.

- [Measured results](src/results/REPORT.md)
- [Bencher data format](#bencher-data-format)
- [Remaining runs and EMR plan](src/results/TODO.md)
- [Case inventories](src/main/resources/)

## Structure

| Location | Responsibility |
|---|---|
| [spark_lp/Benchmark.scala](src/main/scala/com/github/vbmacher/spark_lp/Benchmark.scala) | Algorithm contract and registry |
| [CholeskyBenchmark.scala](src/main/scala/com/github/vbmacher/spark_lp/CholeskyBenchmark.scala), [CGBenchmark.scala](src/main/scala/com/github/vbmacher/spark_lp/CGBenchmark.scala) | Algorithm instances implementing `Benchmark` |
| [spark_lp/BenchmarkRunner.scala](src/main/scala/com/github/vbmacher/spark_lp/BenchmarkRunner.scala) | CLI parsing and `run(benchmark, config)` API |
| [support/BenchmarkCase.scala](src/main/scala/com/github/vbmacher/spark_lp/support/BenchmarkCase.scala) | Shared CSV schema, parsing and case validation |
| [support/DataGenerator.scala](src/main/scala/com/github/vbmacher/spark_lp/support/DataGenerator.scala) | Distributed coefficients, witnesses, input adapters and accuracy checks |
| [support/JvmSampler.scala](src/main/scala/com/github/vbmacher/spark_lp/support/JvmSampler.scala), [Stopwatch.scala](src/main/scala/com/github/vbmacher/spark_lp/support/Stopwatch.scala), [SolveMeasurements.scala](src/main/scala/com/github/vbmacher/spark_lp/support/SolveMeasurements.scala), [RuntimeEnvironment.scala](src/main/scala/com/github/vbmacher/spark_lp/support/RuntimeEnvironment.scala) | JVM sampling, timing, progress phases, environment capture and watchdog |
| `src/main/resources/*.csv` | Inputs for local and EMR runs, all with the same schema |
| `src/results/data/*.bmf.json` | Bencher metrics, split by campaign and testbed, including failure and exclusion counts |
| `scripts/` | Campaign launch, artifact analysis, normalization and report generation |

`Auto` is an algorithm-selection policy. Benchmarks explicitly select Cholesky or CG so comparisons identify the algorithm used. Adding an algorithm requires a `Benchmark` implementation and registry entry; campaigns and the generator stay shared.

## Case design

Every input CSV has this header:

```csv
id,m,n,nonzeros_per_row,family,seed,tolerance,heap_gib
example,1000,10000,32,well,11,1e-8,4
```

| Column | Meaning |
|---|---|
| `id` | Unique case identifier, supplied to the runner |
| `m`, `n` | Equality rows and nonnegative variables; `0 < m < n` |
| `nonzeros_per_row` | Target support before dependent-row expansion; includes the diagonal basis entry |
| `family` | `well`, `wide`, `dependent`, `degenerate`, or `dense` |
| `seed` | Seed for deterministic SQL hash expressions generating coefficients/witnesses |
| `tolerance` | Maximum accepted normalized primal/dual/gap/objective error |
| `heap_gib` | Planned driver heap; the launcher passes this to the JVM |

The generator builds a diagonal basis in the first `m` columns. Each sparse row also receives up to `nonzeros_per_row-1` distinct nonbasis entries at a seed-dependent cyclic offset. Thus `nnz = m * min(nonzeros_per_row, n-m+1)` for ordinary sparse families. Structural zero columns remain present as variables.

| Family | Modification and purpose |
|---|---|
| `well` | Diagonal basis plus moderate positive coefficients; reference sparse case |
| `wide` | Multiplies row `i` by `10^(-6*i/(m-1))`; tests uneven row scaling |
| `dependent` | Replaces each odd row by its preceding row plus `1e-4` times itself; tests near dependence. Support can grow, so count actual nonzeros. |
| `degenerate` | Sets every fifth basis variable to zero at the optimum; tests degenerate optima |
| `dense` | Generates all `m*n` coefficients; ignores the sparse support target |

Known witnesses satisfy `x >= 0`, `s >= 0` and `x[j]*s[j]=0`. The generator sets `b=A*x` and `c=Aᵀ*y+s`, giving a feasible primal-dual pair and known optimum `bᵀ*y`. Validation independently evaluates the returned solution against these original equations.

Generation uses `spark.range`, SQL expressions, joins and aggregations. Coefficients and `n`-length vectors stay distributed and cached with spill support. Only scalar statistics and the solver-required `m`-length RHS/dual vectors reach the driver. Sparse-vector conversion allocates one column at a time on executors; cost vectors are bounded by partition size. Choose enough partitions for the widest cases. The deterministic fingerprint covers coefficients and witnesses using distributed integer summaries; it is a reproducibility fingerprint, not an exact cryptographic digest of every derived floating-point byte. Floating-point reduction order can affect the last bits of `b` and `c`.

| Inventory | Question |
|---|---|
| `solver-scaling.csv` | How do solve time and memory change as rows, variables and support grow? |
| `sparsity-and-conditioning.csv` | How do density, scaling, near dependence and degeneracy affect convergence and cost? |
| `emr-scaling.csv` | How do the algorithms behave on larger distributed problems? Validation status: pending; see the campaign plan. |

## Build and run

Run commands from the repository root, with Java 11 and sbt 1.10.7 available. The module uses Spark 3.5.3 / Scala 2.12.20 and depends on the matching `spark-lp` matrix project. Spark is `Provided`; local execution uses `Test/runMain` to include Spark on the classpath. Timed suites are ordinary objects and do not run during `sbt test`. Small fixture and backend regression tests run with `sbt 'benchmarksSpark_3_52_12/test'`; published benchmark data is checked with `python3 benchmarks/scripts/report.py check` and `python3 benchmarks/scripts/bencher_export.py --check`.

```sh
sbt 'benchmarksSpark_3_52_12/Test/compile'
mkdir -p /tmp/lp-scaling-cholesky
sbt 'benchmarksSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.BenchmarkRunner cholesky /tmp/lp-scaling-cholesky /absolute/path/to/spark-lp/benchmarks/src/main/resources/solver-scaling.csv rows-100-vars-1000-width-8 8 5 1'
mkdir -p /tmp/lp-scaling-cg
sbt 'benchmarksSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.BenchmarkRunner cg /tmp/lp-scaling-cg /absolute/path/to/spark-lp/benchmarks/src/main/resources/solver-scaling.csv rows-100-vars-1000-width-8 8 5 1'
```

Replace the absolute repository path. Arguments are `benchmark output-directory inventory.csv case-id partitions [repetitions=5] [warmups=1]`. Output must be a fresh, existing absolute directory. Warmups can be 0 or 1. These commands run the first scaling case with one warmup and five measured attempts per algorithm.

To run both algorithms sequentially, each in fresh JVMs:

```sh
python3 benchmarks/scripts/run.py --campaign solver-scaling --output /absolute/artifacts/scaling-run
python3 benchmarks/scripts/run.py --campaign sparsity-and-conditioning --output /absolute/artifacts/conditioning-run
```

The launcher refuses to overwrite a run, pins BLAS threads to one, alternates backend order and preserves source snapshots, commands, environments, exits, event logs and JSONL measurements. It applies a local Cholesky workspace exclusion before launch. The runner limits each solve to 30 minutes; subsequent missing repetitions remain `Unrun`. Keep bulky artifacts outside this module.

### EMR through AWS CLI

Use AWS CLI credentials with access to the chosen cluster and S3 prefix. The primary node needs AWS CLI, Bash, Python 3, `timeout`, `sha256sum` and `spark-submit`; its instance role needs read/write access to the artifact prefix. The local launcher requires `jq` and sbt, or a prebuilt assembly supplied with `--jar`.

Create a dedicated cluster with `aws emr create-cluster`, or use an existing idle cluster. The following example uses existing IAM roles, a chosen subnet and EMR 7.6.0; replace the parameters and size the instances for the [campaign plan](src/results/TODO.md). [AWS CLI cluster creation](https://docs.aws.amazon.com/cli/latest/reference/emr/create-cluster.html).

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

The launcher builds the assembly, uploads the inventory and source archive to a unique run prefix, captures cluster/instance metadata, and submits one `command-runner.jar` step with `aws emr add-steps`. The step stages local inputs on the primary node and invokes `BenchmarkRunner` in YARN client mode. Driver heap comes from the selected CSV row. Use `--help` for executor, partition and repetition arguments; run algorithms sequentially on the same idle cluster. Cholesky is excluded before submission if its estimated payload exceeds half either configured heap. [AWS CLI step submission](https://docs.aws.amazon.com/cli/latest/reference/emr/add-steps.html), [EMR command runner](https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-commandrunner.html).

Add `--dry-run --jar /absolute/path/benchmarks-assembly.jar` to inspect the generated step JSON without AWS calls or a build. A prebuilt jar is hashed, but its source digest and implementation commit are marked unrecorded because the launcher cannot establish which source produced it. A launcher build preserves the checkout commit, a source archive and its SHA-256 digest; the archive captures any uncommitted source changes. The commit is propagated to driver measurements as `implementation_sha`. Each run retains the submitted arguments, assembly/inventory hashes and actual runtime environment.

Submission prints the step ID and S3 run URI. Monitor and download using AWS CLI:

```sh
aws emr describe-step --cluster-id "$BENCHMARK_CLUSTER_ID" --step-id s-REPLACE
aws s3 cp s3://your-bucket/spark-lp-benchmarks/runs/RUN-ID/ /absolute/artifacts/emr-run/ --recursive
# After completing the planned batch and checking uploaded artifacts:
aws emr terminate-clusters --cluster-ids "$BENCHMARK_CLUSTER_ID"
```

Raw measurements, environment, command, application log and exit status upload under `results/`; Spark event logs go directly to `events/`, and submission inputs remain under `input/`. Before uploading, the step retains existing records and fills missing repetitions with the process failure followed by `Unrun` slots. An existing watchdog failure is preserved without adding another failure. The step attempts the result upload on failure as well as success, and preserves a nonzero process exit. A whole-application timeout also bounds generation/validation to `(warmups + repetitions) × 30 minutes + 10 minutes`. Node loss or forced termination can prevent uploads; reconcile missing attempts against the step/container logs before importing. Per-executor memory sampling is [pending](src/results/TODO.md).

## Results and environments

```sh
python3 benchmarks/scripts/report.py import-jsonl /absolute/artifacts/scaling-run --campaign solver-scaling-new-run --output benchmarks/src/results
python3 benchmarks/scripts/report.py render
python3 benchmarks/scripts/report.py check
```

The importer writes self-contained Bencher Metric Format files directly under `src/results/data/`. It refuses an existing campaign ID. Embedded evidence retains every captured attempt's dimensions, algorithm/suite, configuration, environment, repetition, status, timing, accuracy, memory observations and relative source path/hash/line. These details allow the report to preserve partial batches and validate aggregate BMF metrics without a second CSV dataset. Published results omit infrastructure identifiers, provider metadata and storage locations; deployment details remain in private run manifests. Source configuration IDs keep recorded settings separate even when a setting is not a report column. Missing numeric observations remain unavailable. `check` verifies both BMF metrics against captured attempts and the Markdown report against the same bundle.

The report groups only homogeneous cases/configurations, excludes warmups, reports successful solve-time median/range, and shows failure duration where no attempt converged. No failure becomes a successful timing. Constant columns move above each table; ID and Env remain explicit references. The environment table describes the recorded machine/OS/Spark/Java/EMR environment, not the machine generating the Markdown.

The report contains imported measurements, including partial batches, with links to their BMF files. Case inventories in `src/main/resources/` define the inputs; planned cases and missing repetitions are not successful measurements. Separate [QP validation](quadratic/README.md) and [GPU status](gpu/README.md) reports contain their own evidence.

## Bencher data format

The [Bencher JSON adapter](https://bencher.dev/docs/explanation/adapters/#-json) fits this custom Spark harness: it accepts [Bencher Metric Format (BMF)](https://bencher.dev/docs/reference/bencher-metric-format/) with multiple numeric measures per benchmark. The JMH adapter would require JMH output and would not preserve this harness's existing timing protocol. Bencher can improve exploration through selectable benchmarks, measures and testbeds, and later support historical comparisons. BMF is the public result format; embedded evidence preserves per-attempt details that aggregate numeric measures cannot express. The Markdown report is generated from this bundle.

The single [data directory](src/results/data/) contains self-contained BMF files. Each benchmark's `measured-records-count` metric includes an `_evidence` extension containing its campaign, case, configuration, captured attempts and source provenance. Executor observations use the same extension on `executor-observations-count`. The extension is allowed by the BMF schema and ignored by Bencher's [numeric metric parser](https://github.com/bencherdev/bencher/blob/v0.6.12/lib/bencher_json/src/project/metric/mod.rs). Our reporting tools use it to verify the numeric measures. Keep these original files for audit details: Bencher stores the numeric metrics, not this local extension. No separate manifest or CSV results dataset is required.

Check the bundle or regenerate its BMF files from the retained observations, without Spark, new Python dependencies, a Bencher account or uploads:

```sh
python3 benchmarks/scripts/bencher_export.py --check
python3 benchmarks/scripts/bencher_export.py
```

Use `--data DIRECTORY` to read another bundle's data directory, or `--output DIRECTORY` to write a bundle copy containing `data/`. `--check` detects missing/unexpected data files and verifies every BMF value against the captured evidence. File links are included in [REPORT.md](src/results/REPORT.md).

| Exported measures | Meaning |
|---|---|
| `latency` | Validated measured solve time in **nanoseconds**, median with observed minimum/maximum bounds; these are not confidence intervals |
| `outer-iterations`, `cg-steps`, `cg-restarts`, `rank-escalations`, `maximum-rank` | Median and min/max over validated measured runs, when recorded for every such run |
| `primal-max`, `dual-max`, `gap-max`, `objective-error-max` | Maximum normalized errors over validated measured runs |
| `measured-*-count`, `warmup-*-count` | Captured records, attempted, converged, failed, unrun, resource-excluded and unknown counts; pending/missing evidence is unknown |
| `unsuccessful-duration-ns` | Observed unsuccessful measured durations, separated from latency |
| `driver-*-bytes-max`, `combined-process-*-bytes-max` | Maximum captured heap/RSS bytes, including validation; local Spark uses combined driver/executor process samples |
| `executor-*-bytes-max` | Each executor's whole-application heap/RSS/JVM non-heap peaks, in a separate observation file |

Each BMF file belongs to one campaign and one testbed derived from the recorded environment, topology, partition count and driver heap. Benchmark names include the exact configuration ID; different configurations and retry campaigns are never pooled. Missing numeric observations are omitted, never zero-filled. Warmups and unsuccessful/unrun/excluded records never contribute latency. Partial successful batches retain their actual sample counts. Executor observations lack an exact configuration/testbed reference in the captured metadata, so they remain explicitly unmapped instead of being joined to solver measurements by case name.

After [installing the optional Bencher CLI](https://bencher.dev/docs/how-to/install-cli/), preview a file using the testbed suffix after `--` in its filename (`executor-memory-unmapped` for the executor observation file):

```sh
bencher run --adapter json --dry-run --project spark-lp-preview \
  --branch captured-snapshot --testbed TESTBED --file FILE.bmf.json
```

The CLI dry run constructs a request and may contact its API for a version check; it does not upload results or validate the server-side adapter. BMF can be validated independently against the [official schema](https://bencher.dev/v0/bmf.json). Interactive plots require a Bencher Cloud or self-hosted project, which these local commands do not create. For future regression tracking, establish stable benchmark/testbed identities and producing build provenance first: the historical configuration hashes may change with source/runtime settings, and the export checkout's HEAD is not the measured solver revision. For later publication, supply the actual producing revision and observation date rather than accepting the CLI's current HEAD/time defaults.

## Memory

For `m` rows, `n` variables, `z=nnz`, `E` executors, `q` active tasks/executor and actual preconditioner rank `r`, the report uses these numeric payload allowances:

```text
C = (12*z + 104*n)/E
P = 4*m*(m+1)
Cholesky executor = C + 2*q*P + 8*q*m     O((z+n)/E + q*m²)
Cholesky driver   = 16*m*m + 64*m        O(m²)
CG executor       = C + 32*q*m          O((z+n)/E + q*m)
CG driver         = 64*m + 32*m*r       O(m + m*r)
```

`C` assumes sparse values/indices, column references and twelve `n`-double vector equivalents. `P` is one packed triangle; reductions can hold two per task. CG fetches one Gramian column at a time, so executor scratch scales with `m`; partial factors stay on the driver. At fixed local topology the executor bounds simplify to `O(z+n+m²)` and `O(z+n+m)`. These are calculated payload estimates, not measured process peaks or rigorous upper bounds. Extra caches, DataFrame/JVM objects, shuffle buffers, native memory and retained garbage are additional. Missing topology/rank prevents a numeric estimate. Report values are MiB (`2^20` bytes).

Local Spark shares one JVM for driver and executor work; RSS samples therefore cover both. Distributed runs must measure each executor and the driver separately. The generator adds distributed DataFrame/cache/shuffle costs and `O(m)` driver vectors; it does not collect the complete matrix or `n`-length solutions. The driver and executor allowances must both fit with headroom before a large direct run is attempted.

The EMR launcher enables Spark executor metrics polling every 1,000 ms, process-tree RSS metrics and per-stage executor peak logging. Those observations are retained in Spark event logs, separately from the runner's driver samples. The [report](src/results/REPORT.md#executor-memory-observations) includes derived executor observations and documents their timing scope.
