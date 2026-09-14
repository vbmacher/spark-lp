# GPU investigation (#71): defer this backend

On this machine, neither driver nor executor GPU acceleration is justified by the
measurements. Mesa identifies the GPU as **Apple M2 Max (G14C B1)**, although the
machine was described as an M2 Pro. The OS is Fedora Asahi Remix 44, kernel
7.1.13-402.asahi.fc44.aarch64+16k, Mesa/Rusticl 26.1.8, OpenCL 3.0.
The device reports no FP64 support; compiling a `double` kernel fails with
`use of type 'double' requires cl_khr_fp64 support`. Vulkan independently reports
`shaderFloat64=false` for the hardware device. Its FP64-capable llvmpipe device is
a CPU software renderer and was excluded.

The optional PyOpenCL prototype executes the same weighted normal operator
`A^T * (w * (A*x))` as the matrix-free solver, using persistent CSR matrices and
weights on the device. It transfers each new vector, launches two kernels,
returns the result and synchronizes on every application. No core dependency,
solver dispatch or accuracy setting was changed. CPU reference arithmetic is FP64.

## Measured results

Twenty repeated operator applications per case, seed 7100. Times are arithmetic
means; setup is separate. FP32 results are accuracy failures at the `1e-8` target,
even when the kernels execute successfully.

| n × m | Density | CPU FP64, ms | GPU FP32 including vector copies/sync, ms | Relative error |
|---|---:|---:|---:|---:|
| 4096 × 64 | 5% | 0.0336 | 0.682 | 8.82e-8 |
| 16384 × 256 | 2% | 0.226 | 1.229 | 9.92e-8 |
| 4096 × 256 | 100% | 2.244 | 4.376 | 3.35e-7 |
| 4096 × 64, nearly cancelling columns | 5% | 0.0322 | 0.463 | 0.951 |

Context/kernel startup took 36.3 ms; matrix upload/allocation took 0.18–2.86 ms.
Device buffer payloads were 0.26–16.83 MB. Complete timings, kernel-only timings,
errors, library versions and the failed FP64 compilation are in
[results/m2-opencl.jsonl](results/m2-opencl.jsonl). The runtime also warned about an
unpatched Mesa libclc; its exact message is preserved in
[results/opencl-run.txt](results/opencl-run.txt). This is a measured capability and
accuracy failure, not an equal-accuracy speed comparison. No tested size benefits;
we have not established a crossover on other hardware or larger workloads.

The existing Spark benchmark independently profiled sparse/dense LPs with 32 rows,
512 variables, four partitions, `local[4]`, one warmup and two measured runs per
backend. All twelve attempts succeeded at `1e-8`; both backends matched the known
primal-dual witnesses. Recorded non-warmup solve times were:

| Case | Cholesky, seconds | CG, seconds |
|---|---|---|
| Sparse (256 nonzeros) | 1.360, 1.344 | 2.531, 2.531 |
| Dense (16,384 nonzeros) | 0.755, 0.715 | 1.123, 1.114 |

The progress phases put system setup at approximately 0.09–0.24 s and inner-solve
work at 0.55–2.13 s. These phases include Spark actions, not just factorization or
arithmetic. Event logs show 54–200 jobs per measured solve and only 8–34 ms of
summed executor run time, with hundreds of milliseconds of task time outside
executor computation. Millisecond task metrics are coarse; summed parallel task
times cannot be subtracted from wall time. These observations support scheduling,
serialization and coordination as substantial costs in these small local cases.
They do not isolate a driver factorization bottleneck or model multi-host network
cost. JVM peak heap/RSS and exact software configuration are in each result folder.

Other Scala validation was active during this investigation, so timings are
exploratory and are not an isolated performance claim. An end-to-end FP64 GPU
solver comparison was **not possible** after the capability check failed. Do not
present operator timing as end-to-end Spark speedup.

## Optional library decision

| Candidate | Accuracy/platform/operations | Packaging and decision |
|---|---|---|
| ND4J CUDA backend | JVM array backend using NVIDIA CUDA; cannot target this Apple GPU | Native platform artifacts and ServiceLoader packaging; adds no capability here. Keep optional. |
| cuSPARSE | CUDA sparse matrix/vector operations and configurable data types; NVIDIA hardware required | Native CUDA API and an additional JVM binding/runtime needed. Candidate for a future FP64 NVIDIA experiment. |
| PyOpenCL + Mesa Rusticl | Available ARM64 wheels and working sparse prototype on this host; hardware FP64 absent | Installed only in `.venv-gpu`; useful to establish the negative result, not a production JVM backend. |

Primary references: [ND4J backends](https://deeplearning4j.konduit.ai/multi-project/explanation/configuration/backends),
[cuSPARSE](https://docs.nvidia.com/cuda/cusparse/index.html),
[PyOpenCL memory ownership](https://documen.tician.de/pyopencl/runtime_memory.html).
No mandatory ND4J, CUDA, OpenCL or Python dependency enters the core library.

## Reproduce

From this worktree:

```sh
python3 -m venv .venv-gpu
.venv-gpu/bin/pip install -r benchmarks/gpu/requirements.txt
.venv-gpu/bin/python benchmarks/gpu/prototype.py --output /tmp/new-gpu-run.jsonl
```

A working OpenCL ICD is required. Fedora already had Mesa OpenCL installed here.
The prototype selects only a hardware GPU, records the device, tests FP64
compilation, and retains every attempted case, including exceptions. It releases
buffers after synchronizing even when a case fails. Use a new output path to
preserve the supplied records. The original command and CPU run output are in
[results/cpu-run.txt](results/cpu-run.txt). CPU cases use `cpu-cases.csv` and the
existing `BenchmarkRunner` (see the parent benchmark README). For each backend
`cholesky`/`cg` and case `sparse`/`dense`, create a fresh absolute output directory:

```text
sbt 'benchmarksSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.BenchmarkRunner BACKEND ABS_OUTPUT ABS_WORKTREE/benchmarks/gpu/cpu-cases.csv CASE 4 2 1'
```

Every output folder contains `records.jsonl`, `environment.json` and the complete
Spark event stream compressed as `events.jsonl.gz`. Decompressed SHA-256 hashes
are recorded in `results/cpu-profile.json`. Uncompressed originals remain locally
ignored. The CPU source was unchanged from repository commit `9208f8e`; raw
`implementation_sha=unrecorded` fields are retained as emitted, without rewriting
provenance. The supplied raw records include warmups and all failures, without
filtering failed runs from the evidence.

## Conditions for reopening

First require a supported FP64 device and independent original-LP residual checks,
including ill-conditioned cases. Then benchmark complete solver runs, with
initialization, transfers, synchronization, reductions, retries and memory included.
A future executor backend must use Spark-assigned device addresses, reconstruct
per-attempt device state on retry, retain a CPU fallback, and release native buffers
on task completion/cancellation. Persistent matrices need bounded executor-local
ownership, not serialized device handles. Driver acceleration needs separate
resource assignment and should target measured factorization costs.
[Spark resource configuration](https://spark.apache.org/docs/3.5.7/configuration.html)
defines driver, executor and task resource requests plus discovery requirements.
These are follow-up design constraints; no production GPU backend is proposed
from the current evidence. FP32 emulation or mixed precision needs a separate
accuracy and refinement investigation, not a silent reduction of solver tolerance.
