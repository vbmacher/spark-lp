# AArch64 netlib package and factorization probe

This directory contains the reproducible Linux AArch64 JNI package used to test
native BLAS/LAPACK for issue #72. The package wraps the system pthread OpenBLAS;
it does not bundle OpenBLAS itself.

## Build

Requirements are AArch64 Linux, JDK 11, Maven, GCC, Git, and OpenBLAS development
headers plus `libopenblasp.so.0`. The script checks out the archived upstream
`netlib-java` commit `52b3a8beb23ee98d26eb66fa67d904c704e01e45`, applies only
JDK-11 build compatibility substitutions, generates the JNI wrappers, and emits
a JAR containing `netlib-native_system-linux-aarch64.so`.

```sh
cd native/netlib-aarch64
./build-netlib-aarch64.sh
```

The retained embedded library was built with OpenBLAS 0.3.29. Its SHA-256 is
`455ccc4d6455554f86aeb53fb54ab02d60821ba27c4c78d6fdf3afb23a9d7f36`.
The runtime host must provide the same `libopenblasp.so.0` ABI. The generated
standalone JAR is a local build output; the `spark-lp` resource is the versioned
artifact.

## Bundled use with netlib-java 1.1

`jniloader` 1.1 does not recognize `aarch64`, so explicitly name the packaged
resource when using the standalone package. The same native library is embedded
at the root of every `spark-lp` JAR, where `NativeNetlib` selects native LAPACK
automatically on Linux AArch64 before netlib-java initializes. Native BLAS stays
an explicit opt-in because the retained CG comparison below found a regression.
The architecture warning is expected; the subsequent `successfully loaded
...netlib-native_system-linux-aarch64.so` message and `NativeSystemLAPACK` class
are the positive checks. If the host lacks `libopenblasp.so.0`, netlib-java logs
the failed native load and retains its existing Java fallback.

```sh
export OPENBLAS_NUM_THREADS=4
export OMP_NUM_THREADS=4
spark-submit \
  --jars native/netlib-aarch64/dist/netlib-native_system-linux-aarch64-1.1-openblas-pthreads.jar \
  --driver-java-options '-Dcom.github.fommil.netlib.NativeSystemBLAS.natives=netlib-native_system-linux-aarch64.so -Dcom.github.fommil.netlib.NativeSystemLAPACK.natives=netlib-native_system-linux-aarch64.so' \
  --conf 'spark.executor.extraJavaOptions=-Dcom.github.fommil.netlib.NativeSystemBLAS.natives=netlib-native_system-linux-aarch64.so -Dcom.github.fommil.netlib.NativeSystemLAPACK.natives=netlib-native_system-linux-aarch64.so' \
  # remaining application arguments
```

Keep the thread count explicit. Native BLAS/LAPACK must be verified in both the
driver and executor processes because their classpaths and JVM options differ.
The benchmark module includes a probe for that purpose. Set one task per
executor (for example `spark.task.cpus` equal to executor cores), then request
the executor count as the first argument:

```sh
spark-submit --class com.github.vbmacher.spark_lp.NativeNetlibProbe \
  --conf spark.task.cpus=4 benchmarks-assembly.jar 4 --require-native
```

The retained AArch64 check used two separate one-core executor JVMs. The driver
and both executors reported the intended default: `F2jBLAS` and
`NativeSystemLAPACK`.

## Retained comparison

The probe uses a deterministic, strictly diagonally dominant 5,000 × 5,000 SPD
matrix, one warmup and five measured repetitions in a fresh JVM. The local host
was Linux AArch64 with JDK 11 and OpenBLAS 0.3.29. All runs returned `info=0`.
The raw aggregate rows are in [aarch64-factorization.csv](../../benchmarks/reports/native-aarch64/results/aarch64-factorization.csv).

| Backend / storage | Threads | Factor median (s) | Range (s) | Speedup vs Java full |
|---|---:|---:|---:|---:|
| Java fallback / full | 1 | 18.348 | 18.341–18.360 | 1.0× |
| Native / packed | 1 | 3.871 | 3.853–4.083 | 4.7× |
| Native / full | 1 | 0.874 | 0.872–0.875 | 21.0× |
| Native / full | 2 | 0.448 | 0.447–0.449 | 40.9× |
| Native / full | 4 | 0.231 | 0.230–0.231 | 79.4× |
| Native / full | 8 | 0.127 | 0.124–0.151 | 144.4× |

Packed `dpptrf` remained 3.87–3.92 seconds across 1–8 threads. Full `dpotrf`
therefore replaces it in the solver: the measured packed-to-full gain grows from
4.4× at one thread to 30.9× at eight. Expanding the packed 5,000-row input took
about 0.01–0.02 seconds and adds about 95 MiB for the full factor. The solution
maximum absolute error was `3.30e-14` for full storage and `8.73e-14` for packed.

## End-to-end solver comparison

The same AArch64 host ran the deterministic `shape-5000-10-20-well-11-1e-08-4`
fixture (5,000 rows, 50,000 variables, 16 partitions) in fresh JVMs. Both sides
used one warmup, five measured repetitions, a 4 GiB heap and one BLAS thread.
The fallback run preserved user properties that deliberately named missing JNI
resources; captured runtime classes confirmed `F2jBLAS`/`F2jLAPACK` versus
`F2jBLAS`/`NativeSystemLAPACK`.

| Backend | Successful measured | Median (s) | Range (s) | Speedup |
|---|---:|---:|---:|---:|
| Java fallback | 5/5 | 198.110 | 197.029–200.434 | 1.00× |
| Native LAPACK, one thread | 5/5 | 23.094 | 22.688–23.406 | 8.58× |

Every attempt converged in nine Newton iterations and passed independent
primal, dual, gap and objective validation. The retained attempt rows are in
[aarch64-solver.csv](../../benchmarks/reports/native-aarch64/results/aarch64-solver.csv).

## CG recheck

The same one-thread protocol found a 22.363 s native-BLAS median versus
14.614 s for Java BLAS, so JNI OpenBLAS was 1.53× slower for this vector-heavy
CG path. Both variants passed 5/5 attempts with the same nine outer iterations,
307 CG steps and residuals. `spark-lp` consequently auto-selects only native
LAPACK; native BLAS remains available through the documented property when a
different workload supports it. Retained rows are in
[aarch64-cg.csv](../../benchmarks/reports/native-aarch64/results/aarch64-cg.csv).

Compile the probe against the same netlib-java 1.1 dependencies used by the
project, then run it with the properties above:

```sh
cd native/netlib-aarch64
javac -cp "$NETLIB_CLASSPATH" FactorizationProbe.java
OPENBLAS_NUM_THREADS=4 OMP_NUM_THREADS=4 java \
  -Dcom.github.fommil.netlib.NativeSystemBLAS.natives=netlib-native_system-linux-aarch64.so \
  -Dcom.github.fommil.netlib.NativeSystemLAPACK.natives=netlib-native_system-linux-aarch64.so \
  -cp "dist/netlib-native_system-linux-aarch64-1.1-openblas-pthreads.jar:$NETLIB_CLASSPATH:." \
  FactorizationProbe full 5000 5 1
```
