# AArch64 netlib package and factorization probe

Reproducible Linux AArch64 JNI package used to test native BLAS/LAPACK for issue
#72. It wraps the system pthread OpenBLAS; it does not bundle OpenBLAS itself.

## Build

Requires AArch64 Linux, JDK 11, Maven, GCC, Git, and OpenBLAS development headers
plus `libopenblasp.so.0`. The script checks out the archived upstream
`netlib-java` commit `52b3a8beb23ee98d26eb66fa67d904c704e01e45`, applies only
JDK-11 build-compatibility substitutions, generates the JNI wrappers, and emits a
JAR containing `netlib-native_system-linux-aarch64.so`.

```sh
cd native/netlib-aarch64
./build-netlib-aarch64.sh
```

The retained library was built with OpenBLAS 0.3.29; its SHA-256 is
`455ccc4d6455554f86aeb53fb54ab02d60821ba27c4c78d6fdf3afb23a9d7f36`. The runtime
host must provide the same `libopenblasp.so.0` ABI. The standalone JAR is a local
build output; the `spark-lp` resource is the versioned artifact.

## Bundled use with netlib-java 1.1

`jniloader` 1.1 does not recognize `aarch64`, so name the packaged resource
explicitly when using the standalone package. The same library is embedded in
every `spark-lp` JAR, where `NativeNetlib` selects native LAPACK automatically on
Linux AArch64. The architecture warning is expected; the `successfully loaded
...netlib-native_system-linux-aarch64.so` message and `NativeSystemLAPACK` class
are the positive checks. Without `libopenblasp.so.0`, netlib-java logs the failed
load and keeps its Java fallback. Native BLAS stays opt-in (see the CG recheck).

```sh
export OPENBLAS_NUM_THREADS=4
export OMP_NUM_THREADS=4
spark-submit \
  --jars native/netlib-aarch64/dist/netlib-native_system-linux-aarch64-1.1-openblas-pthreads.jar \
  --driver-java-options '-Dcom.github.fommil.netlib.NativeSystemBLAS.natives=netlib-native_system-linux-aarch64.so -Dcom.github.fommil.netlib.NativeSystemLAPACK.natives=netlib-native_system-linux-aarch64.so' \
  --conf 'spark.executor.extraJavaOptions=-Dcom.github.fommil.netlib.NativeSystemBLAS.natives=netlib-native_system-linux-aarch64.so -Dcom.github.fommil.netlib.NativeSystemLAPACK.natives=netlib-native_system-linux-aarch64.so' \
  # remaining application arguments
```

Native BLAS/LAPACK must be verified in both driver and executor processes, whose
classpaths and JVM options differ. The benchmark module includes a probe; set one
task per executor and pass the executor count as the first argument:

```sh
spark-submit --class com.github.vbmacher.spark_lp.NativeNetlibProbe \
  --conf spark.task.cpus=4 benchmarks-assembly.jar 4 --require-native
```

## Retained comparisons

Local host: Linux AArch64, JDK 11, OpenBLAS 0.3.29. Raw rows are under
[benchmarks/reports/native-aarch64/results](../../benchmarks/reports/native-aarch64/results).

**Factorization** — deterministic SPD 5,000 × 5,000 matrix, one warmup and five
measured repetitions, all runs `info=0`:

| Backend / storage | Threads | Factor median (s) | Speedup vs Java full |
|---|---:|---:|---:|
| Java fallback / full | 1 | 18.348 | 1.0× |
| Native / packed | 1 | 3.871 | 4.7× |
| Native / full | 1 | 0.874 | 21.0× |
| Native / full | 4 | 0.231 | 79.4× |
| Native / full | 8 | 0.127 | 144.4× |

Packed `dpptrf` stayed 3.87–3.92 s across 1–8 threads, so full `dpotrf` replaces
it in the solver (packed-to-full gain grows 4.4×→30.9× from 1 to 8 threads).
Expanding the packed input adds ~95 MiB; max absolute error was `3.30e-14` (full)
and `8.73e-14` (packed).

**End-to-end solver** — `shape-5000-10-20-well-11-1e-08-4` fixture (5,000 rows,
50,000 variables, 16 partitions), 4 GiB heap, one BLAS thread:

| Backend | Median (s) | Speedup |
|---|---:|---:|
| Java fallback | 198.110 | 1.00× |
| Native LAPACK, one thread | 23.094 | 8.58× |

Every attempt converged in nine Newton iterations and passed independent primal,
dual, gap and objective validation.

**CG recheck** — the same protocol gave a 22.363 s native-BLAS median versus
14.614 s for Java BLAS (JNI OpenBLAS 1.53× slower for this vector-heavy CG path;
both 5/5, same nine outer iterations and 307 CG steps). `spark-lp` therefore
auto-selects only native LAPACK; native BLAS stays available via the documented
property. Compile and run the probe directly with:

```sh
cd native/netlib-aarch64
javac -cp "$NETLIB_CLASSPATH" FactorizationProbe.java
OPENBLAS_NUM_THREADS=4 OMP_NUM_THREADS=4 java \
  -Dcom.github.fommil.netlib.NativeSystemBLAS.natives=netlib-native_system-linux-aarch64.so \
  -Dcom.github.fommil.netlib.NativeSystemLAPACK.natives=netlib-native_system-linux-aarch64.so \
  -cp "dist/netlib-native_system-linux-aarch64-1.1-openblas-pthreads.jar:$NETLIB_CLASSPATH:." \
  FactorizationProbe full 5000 5 1
```
