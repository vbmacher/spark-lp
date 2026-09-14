# Remaining benchmark runs

## First: establish a new baseline

- [ ] Run `solver-scaling.csv`: one warmup and five measured repetitions per case/algorithm in fresh JVMs. Keep source/environment/configuration fixed and alternate algorithm order.
- [ ] Run `sparsity-and-conditioning.csv` with the same protocol.
- [ ] Repeat promising or unstable shapes with seeds 29 and 47 before drawing conclusions about crossover or defaults.
- [ ] Import the artifacts into flat campaign result CSVs and regenerate the report. Group measurements by configuration and case identity.

Commands and case construction are in [README](../../README.md). No campaign results are currently recorded. All full local campaigns and cluster runs remain to be executed.

## EMR campaign

Use [emr-scaling.csv](../main/resources/emr-scaling.csv). Both algorithms use the same DataFrame generator and independent distributed validator. Start with the first case, inspect memory, and then advance by size. Record `ResourceExcluded` when Cholesky's projected driver or executor workspace exceeds the agreed limit; an excluded configuration is not an attempted solve.

| Stage | Rows m | Variables n | Target nonzeros/row | Expected nnz | Algorithms | Executors × cores | Executor heap / driver heap | Partitions |
|---|---:|---:|---:|---:|---|---|---|---:|
| Pipeline | 1,000 | 100,000 | 32 | 32,000 | Cholesky, CG | 4 × 4 | 16 / 16 GiB | 64 |
| Moderate | 5,000 | 1,000,000 | 32 | 160,000 | Cholesky, CG | 4 × 4 | 16 / 16 GiB | 128 |
| Row scaling | 10,000 | 1,000,000 | 32 | 320,000 | Cholesky, CG, subject to memory gate | 4 × 4 | 16 / 16 GiB | 128 |
| Wide | 10,000 | 10,000,000 | 64 | 640,000 | Cholesky, CG, subject to memory gate | 8 × 4 | 16 / 16 GiB | 256 |
| Large rows | 50,000 | 10,000,000 | 64 | 3,200,000 | CG; direct planning exclusion expected | 8 × 4 | 16 / 16 GiB | 512 |
| Largest | 100,000 | 100,000,000 | 128 | 12,800,000 | CG; direct planning exclusion expected | 16 × 4 | 16 / 16 GiB | 1,024 |

All sizes are plans. Executor counts are Spark resources, not assumed EC2 node counts. Select sufficient EC2 instances to fit heap, at least 4 GiB executor overhead, YARN/OS services and disk spill. Use one architecture/instance family throughout a comparison; record actual instance types and cluster name in the environment table. Keep dynamic allocation, speculation and autoscaling off during measured batches. Pin BLAS threads to one and avoid overlapping applications.

- [ ] Confirm an available EMR release and JDK combination matching Spark 3.5.3 / Scala 2.12. EMR 7.6.0 provides Spark 3.5.3; verify its availability and support at execution time. [AWS release documentation](https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-760-release.html).
- [ ] Choose region, cluster/instance types, S3 artifact/event-log location and an explicit runtime/cost budget before provisioning. No cluster has been provisioned or selected by this task.
- [ ] Create or select an idle cluster using AWS CLI. Submit the first case with `bash benchmarks/scripts/cluster.sh --cluster-id ... --region ... --s3-prefix ... --benchmark cg --case emr-rows-1000-vars-100000-width-32`; follow the [AWS CLI instructions](../../README.md#emr-through-aws-cli). The launcher builds/uploads inputs and submits an EMR step. Validate its `--dry-run` output before the first execution.
- [ ] Record exact SparkConf, JDK, BLAS, release label, cluster ID/name, EC2 types, executor topology, heap/overhead, partitions, source archive/digest and case fingerprint.
- [ ] Add per-executor JVM heap/RSS/native-memory sampling, keyed by application/container/executor ID, alongside driver sampling. Keep task concurrency and sampling intervals recorded. Current runner samples the driver only.
- [ ] Verify that generation, materialization and validation remain distributed at the widest size; track spill, shuffle, skew and executor peak input load. Test counts and hashes before timing.
- [ ] Use one warmup and five measured attempts per eligible algorithm/case. Cap each solve at 30 minutes and retain timeouts/OOM/accuracy failures and subsequent unrun slots.
- [ ] Repeat the wide case with 4/8/16 executors at fixed input size for strong scaling. Repeat proportionally larger `n` and support for weak scaling; add these cases using the shared CSV schema and unique IDs.
- [ ] Run selected `wide`, `dependent` and `degenerate` cases on EMR after the well-conditioned reference cases finish. Change one parameter at a time and retain matched seeds.

### Memory gate and collection

Use the [memory formulas](../../README.md#memory) for both driver and executor, with actual `E`, `q`, nonzeros and rank. Initially allow at most half the configured heap for calculated numeric payload; reserve the remainder for generation, Spark/JVM overhead and transient allocations. Raise a limit only in a separately recorded configuration after measurement.

For example, at `m=50,000`, Cholesky needs approximately 37.3 GiB of driver allowance and 74.5 GiB of packed executor reduction buffers at four active tasks, before distributed input. It is excluded from the planned 16-GiB configuration. At `m=100,000,n=100,000,000,z=12,800,000,E=16,q=4`, the CG executor payload model is approximately 641 MiB, plus DataFrame/object/cache/shuffle overhead; total measured memory can be substantially larger. Record actual rank before calculating the CG driver figure.

## Acceptance and publication

- [ ] Require independent normalized primal, dual, gap and objective error below the case tolerance, and `min(x), min(s) >= -tolerance` for every claimed success.
- [ ] Distinguish generated-data time, preparation, core solve, validation and release. Timing statistics include successful measured attempts only; report failure duration separately.
- [ ] Reconcile each inventory/configuration/repetition with records and process exits. Unknown evidence, explicit unrun slots, resource exclusions and attempted failures are different states.
- [ ] Preserve immutable raw JSONL/event logs/manifests/source archives outside the documentation tree. Import one flat CSV per executed campaign with provenance; do not add fake observations for planned runs.
- [ ] Add each actual environment to the generated Environment table and reference it from every result row. Report executor and driver memory separately; never relabel combined local RSS as isolated executor memory.
- [ ] Run `python3 benchmarks/scripts/report.py check` after normalization. Update the report only from checked data.
