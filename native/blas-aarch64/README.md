# Bundled AArch64 BLAS

Builds the optional Linux AArch64 native BLAS resource shipped inside every
`spark-lp` JAR. It combines the netlib-java 1.1 JNI wrapper with a BLAS-only
OpenBLAS 0.3.29 static build, so the shared object needs no system `libopenblas`
or `libgfortran`.

Native BLAS is opt-in: the issue #72 comparison found the JNI OpenBLAS path
slower than Java BLAS for the vector-heavy CG workload, so it must not silently
replace the default without workload-specific measurements.

## Build

Requires Linux AArch64, JDK 11, Maven, Git, binutils, and Podman. The build pins
OpenBLAS and netlib-java by commit and a manylinux2014 container by digest
(glibc 2.17 floor), links OpenBLAS into the JNI shared object, runs a BLAS
correctness probe, and writes the resource plus a standalone JAR to `dist/`.

```sh
cd native/blas-aarch64
./build-blas-aarch64.sh
```

The versioned resource is
`spark-lp/src/main/resources/netlib-native_blas-linux-aarch64.so`. Rebuild and
compare its SHA-256 before replacing it; the retained build is
`162371e55c6aecb2deffcf2791b185a06282503142a4ed83a7a67f96cee5499c`.

## Opt-in use

Set the netlib-java native resource before BLAS is first initialized,
independently in the driver and executor JVMs:

```sh
spark-submit \
  --driver-java-options '-Dcom.github.fommil.netlib.NativeSystemBLAS.natives=netlib-native_blas-linux-aarch64.so' \
  --conf 'spark.executor.extraJavaOptions=-Dcom.github.fommil.netlib.NativeSystemBLAS.natives=netlib-native_blas-linux-aarch64.so' \
  # remaining application arguments
```

Pin `OPENBLAS_NUM_THREADS` and `OMP_NUM_THREADS` for repeatable measurements, and
confirm `com.github.fommil.netlib.NativeSystemBLAS` in both driver and executor
before attributing results to this package:

```sh
spark-submit --class com.github.vbmacher.spark_lp.NativeNetlibProbe \
  benchmarks-assembly.jar 2 --require-native-blas
```
