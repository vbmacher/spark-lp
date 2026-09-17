# Bundled AArch64 BLAS

This directory builds the optional Linux AArch64 native BLAS resource shipped
inside every `spark-lp` JAR. It combines the netlib-java 1.1 JNI wrapper with a
BLAS-only OpenBLAS 0.3.29 static build, so the resulting shared object does not
need a system `libopenblas` or `libgfortran`.

Native BLAS remains opt-in. The retained issue #72 comparison found the JNI
OpenBLAS path slower than Java BLAS for the vector-heavy CG workload, so this
package must not silently replace the default without workload-specific
measurements.

## Build

Requirements are Linux AArch64, JDK 11, Maven, Git, binutils, and Podman. The
build pins OpenBLAS and netlib-java by commit and pins a manylinux2014 container
by digest so the native resource keeps a glibc 2.17 compatibility floor. It uses
a temporary source tree, links OpenBLAS into the JNI shared object, runs a BLAS
correctness probe, and writes the raw resource plus a standalone resource JAR
to `dist/`.

```sh
cd native/blas-aarch64
./build-blas-aarch64.sh
```

The versioned resource is
`spark-lp/src/main/resources/netlib-native_blas-linux-aarch64.so`. Rebuild it
and compare its SHA-256 before replacing that file. The retained build has
SHA-256 `162371e55c6aecb2deffcf2791b185a06282503142a4ed83a7a67f96cee5499c`.

## Opt-in use

Set the netlib-java native resource before BLAS is first initialized. For
Spark, set it independently in the driver and executor JVMs:

```sh
spark-submit \
  --driver-java-options '-Dcom.github.fommil.netlib.NativeSystemBLAS.natives=netlib-native_blas-linux-aarch64.so' \
  --conf 'spark.executor.extraJavaOptions=-Dcom.github.fommil.netlib.NativeSystemBLAS.natives=netlib-native_blas-linux-aarch64.so' \
  # remaining application arguments
```

Pin `OPENBLAS_NUM_THREADS` and `OMP_NUM_THREADS` for repeatable measurements.
Confirm `com.github.fommil.netlib.NativeSystemBLAS` in both driver and executor
runtime observations before attributing results to this package.

```sh
spark-submit --class com.github.vbmacher.spark_lp.NativeNetlibProbe \
  benchmarks-assembly.jar 2 --require-native-blas
```
