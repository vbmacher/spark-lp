# GPU acceleration status (#71)

[All benchmark reports](README.md) · [Study source](gpu/README.md)

Evidence coverage: **4 GPU operator cases and 12 CPU solver attempts**.


**Status: experimental prototype; production integration deferred.** The core
solver uses CPU backends. GPU acceleration requires validated numerical accuracy
and an end-to-end performance benefit before driver or executor integration.

## Validation environment

| Component | Configuration |
|---|---|
| GPU | Apple M2 Max (G14C B1) |
| OS | Fedora Asahi Remix 44 |
| Kernel | 7.1.13-402.asahi.fc44.aarch64+16k |
| Runtime | Mesa/Rusticl 26.1.8, OpenCL 3.0 |
| Hardware FP64 | Unavailable; OpenCL `double` compilation fails and Vulkan reports `shaderFloat64=false` |

The FP64-capable llvmpipe device is a CPU software renderer and is outside the
hardware GPU validation scope. Results apply to this device/runtime configuration.

## Validation status

- **FP64 capability: blocked.** OpenCL reports
  `use of type 'double' requires cl_khr_fp64 support`.
- **FP32 accuracy: failed.** All four operator cases exceed the `1e-8` relative-error
  target against the FP64 CPU reference, including the nearly cancelling-column
  case. Records include twenty applications per case with seed 7100.
- **CPU solver validation: passed.** All twelve sparse/dense LP profiling attempts
  satisfy `1e-8` and match known primal-dual witnesses. Coverage includes Cholesky
  and CG, 32 rows, 512 variables, four partitions, `local[4]`, one warmup and two
  measured runs per configuration.
- **Production GPU readiness: unverified.** The FP64 capability requirement blocks
  end-to-end GPU solver validation. No workload crossover or driver/executor
  acceleration benefit is established.

Spark profiling identifies substantial scheduling and coordination costs in the
small local cases; it does not isolate a driver factorization bottleneck or cover
multi-host network costs. Timing records include concurrent Scala workload activity
and support exploratory profiling only.

## Prototype and evidence

The optional PyOpenCL prototype applies the weighted normal operator
`A^T * (w * (A*x))` with persistent CSR matrices and weights on the device. Each
application transfers a vector, launches two kernels, reads the result and
synchronizes. Measurements include startup, allocation, transfers, kernel execution,
synchronization, buffer sizes and numerical errors.

- [GPU records](gpu/results/m2-opencl.jsonl): all operator cases and the FP64 compilation failure.
- [OpenCL runtime output](gpu/results/opencl-run.txt): device diagnostics, including the Mesa libclc warning.
- [CPU profile](gpu/results/cpu-profile.json): Spark phase/task metrics and event-stream hashes.
- [CPU run output](gpu/results/cpu-run.txt): commands and solver output.

Bundled CPU result folders contain `records.jsonl`, `environment.json` and complete
Spark event streams compressed as `events.jsonl.gz`, including warmups and failures.
Environment records include JVM peak heap/RSS and software configuration. The CPU
benchmark source baseline is commit `9208f8e`; raw `implementation_sha=unrecorded`
fields remain intact. Uncompressed event streams are locally ignored.

## Multi-vendor backend direction

The proposed integration uses a small optional interface for preparing, applying,
updating weights and releasing the sparse normal operator. Matrices remain resident
between applications, with bounded native-memory ownership and a CPU fallback.
Device selection checks FP64, required operations and available memory.

[OpenCL](https://www.khronos.org/opencl/) provides a cross-platform kernel API for
extending the prototype to other runtimes. [SYCL/oneMath](https://github.com/uxlfoundation/oneMath#supported-configurations)
is a production candidate: its Linux sparse BLAS interface has NVIDIA cuSPARSE,
AMD rocSPARSE and Intel oneMKL backends. JVM bindings, platform-specific native
packaging and validation of each device/runtime combination remain integration
requirements. Its documented support matrix does not provide an Apple GPU backend.

[ND4J CUDA](https://deeplearning4j.konduit.ai/multi-project/explanation/configuration/backends)
and direct [cuSPARSE](https://docs.nvidia.com/cuda/cusparse/index.html) bindings are
NVIDIA-specific options. PyOpenCL serves the standalone prototype; it is not a JVM
solver backend. The core has no mandatory ND4J, CUDA, OpenCL or Python dependency.

These backend options are proposals. A portability layer cannot supply missing
native FP64 capability. Apple GPU acceleration requires a separately validated
numerical approach, such as mixed precision with refinement, that meets the
original solver tolerances. The CPU path remains available across supported Spark
platforms; NVIDIA, AMD and Intel GPU suitability requires device-specific evidence.

## Production acceptance criteria

- Validate FP64 execution and independent original-model residuals, including
  ill-conditioned cases. Any mixed-precision approach must meet the same tolerances.
- Establish an end-to-end benefit at equal accuracy, accounting for initialization,
  transfers, synchronization, Spark reductions, retries and memory use.
- Use Spark-assigned device addresses and reconstruct executor device state on
  each task attempt. Release native buffers on completion and cancellation;
  device handles must remain local to their owning process.
- Retain CPU fallback and bounded executor-local matrix caches. Driver acceleration
  requires separate resource assignment and a measured factorization bottleneck.

[Spark resource configuration](https://spark.apache.org/docs/3.5.7/configuration.html)
defines driver, executor and task resource requests and discovery requirements.
[PyOpenCL memory ownership](https://documen.tician.de/pyopencl/runtime_memory.html)
describes buffer management for the prototype.

## Run validation

From the repository root, with a working OpenCL ICD:

```sh
python3 -m venv .venv-gpu
.venv-gpu/bin/pip install -r benchmarks/reports/gpu/requirements.txt
.venv-gpu/bin/python benchmarks/reports/gpu/prototype.py --output /tmp/new-gpu-run.jsonl
```

The prototype selects a hardware GPU, records its capabilities, tests FP64
compilation and retains every attempted case, including exceptions. It releases
buffers after synchronization even when a case fails. Use a new output path to
preserve existing records.

CPU profiling uses `cpu-cases.csv` and `BenchmarkRunner` (see the
[parent benchmark README](../README.md)). For each backend `cholesky`/`cg` and
case `sparse`/`dense`, use a fresh absolute output directory:

```text
sbt 'benchmarksSpark_3_52_12/Test/runMain com.github.vbmacher.spark_lp.BenchmarkRunner BACKEND ABS_OUTPUT ABS_WORKTREE/benchmarks/reports/gpu/cpu-cases.csv CASE 4 2 1'
```
