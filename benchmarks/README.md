# Benchmarks

From the repository root, with JDK 11 and sbt 1.10.7:

```sh
./benchmarks/bench list
./benchmarks/bench validate
./benchmarks/bench run --suite smoke --output benchmarks/output/smoke.bmf.json
./benchmarks/bench validate --bmf benchmarks/output/smoke.bmf.json
```

Use a **new output path** for each run. `run --suite scaling`, `parallelism`,
`accuracy`, `capabilities` or `kernels` uses the same interface. `list --suite NAME`
shows expanded scenario names; `--case ID` and `--backend cg|cholesky` select a subset.
[Methodology, metrics, CI setup and Bencher](../docs/benchmarks.md) explain interpretation.

## Local Bencher configuration

Copy `.bencher.env.example` to the ignored repository-root `.bencher.env` and fill
in the project and API key. With Bencher 0.6.12 installed:

```sh
./benchmarks/bench bencher run --adapter json --file benchmarks/output/smoke.bmf.json
```

This explicitly submits an existing result; use `--dry-run` to avoid uploading.
The wrapper loads optional `.bencher.env` defaults before starting Bencher; exported
variables win, including empty values. Testbeds are always generated from the current
environment; manual testbed settings are unsupported. Preview with `./benchmarks/bench testbed`.
Use `KEY=VALUE`, optional `export`, matching
single/double quotes, and full-line `#` comments. Values are literal: no shell
expansion, escapes or inline comments. Ordinary `list`/`validate`/`run` commands
do not load credentials. Never commit `.bencher.env`.

## Distributed runs

Run on the EMR primary node (SSH or a `command-runner.jar` step), from the checkout
root, with JDK 11, sbt, Spark 3.5, AWS CLI and Bencher 0.6.12 available. Configure
`BENCHER_PROJECT` and `BENCHER_API_KEY` in `.bencher.env` or the environment:

```sh
sbt 'benchmarksSpark_3_52_12/assembly'
./benchmarks/bench bencher run --adapter json --file benchmarks/output/distributed.bmf.json \
  './benchmarks/bench run --suite distributed --jar benchmarks/target/spark_3.5-jvm-2.12/benchmarks-assembly-2.0.0.jar --output benchmarks/output/distributed.bmf.json'
```

On EMR, the wrapper detects the cluster from `/mnt/var/lib/info/job-flow.json`,
reads its configuration and actual nodes through AWS CLI, and selects a stable
testbed automatically; Bencher creates it on submission. Failed detection stops
publication; it never falls back
to a workstation label. Region comes from AWS environment variables or IMDSv2.
The instance role needs `elasticmapreduce:DescribeCluster`, `ListInstanceGroups`,
`ListInstanceFleets`, `ListBootstrapActions` and `ListInstances` read permissions.

Checkpoints default to `hdfs:///spark-lp-benchmarks/checkpoints`; the driver and
executors need write access. Use `--checkpoint-uri URI` only to override this path.
`full` also requires the assembly JAR and runs the complete matrix. Size and
isolate the cluster first; the runner uses Spark-submit in YARN client mode and
never creates or terminates cloud infrastructure. Cases can require 16 GiB driver
heaps and many hours. Failed runs exit nonzero; complete outcome files retain
`converged=0` without partial solve timings.

## Layout and extension

| Path | Purpose |
|---|---|
| `bench` | Thin sbt launcher supplying Spark's runtime classpath |
| `scenarios/*.json` | Eight public suites; `full` composes the others except smoke |
| `scenarios/cases/*.csv` | Local, scaling, accuracy and distributed LP fixtures |
| `src/main/scala/.../BenchmarkRunner.scala` | CLI, fresh JVMs, Spark configuration and failure handling |
| `src/main/scala/.../support/` | Workloads, generation, validation, statistics, names and atomic BMF |
| `src/test/` | Framework, generator and workload regression tests |
| `schema/bencher-output.schema.json` | Checked BMF schema |
| `output/` | Ignored BMF and per-run diagnostic directories |

Add an LP row under `scenarios/cases/`, select it in a suite JSON and run `validate`.
`inventory` paths are relative to `scenarios/`. JSON arrays expand
cases, backends, modes, cores and partitions; `include` composes suites. Counts and
sampling policy live there, not in scripts. Unknown fields, invalid dimensions,
missing baselines and duplicate identities fail validation. Changing a workload's
meaning requires a new case ID; commits and machines never enter benchmark names.
A new workload family needs an adapter in `Workloads` and a correctness test.

```sh
sbt 'benchmarksSpark_3_52_12/test'
```

The runner writes complete BMF outcomes directly and atomically.
`FILE.bmf.json.raw/` retains scenario definitions, classpath snapshots and hashes,
per-attempt diagnostics, commands and logs, including failures. These local artifacts
may contain environment details; publish only BMF to Bencher. Existing outputs are
never overwritten. After a forcibly killed supervisor, retain its diagnostics and
choose a new path. Exit zero means complete measurements or explicit resource
exclusions. Use `validate --bmf FILE --require-converged true` to require successful
solutions for every case, including resource-excluded cases. Bencher's
`--allow-failure` uploads failure outcomes; CI still checks the runner's exit status.
