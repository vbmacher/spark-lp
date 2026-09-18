# AArch64 netlib package

Builds the Linux AArch64 netlib-java JNI wrapper bundled for native LAPACK.
It uses system OpenBLAS (`libopenblasp.so.0`); it does not bundle OpenBLAS.

## Build

Requires AArch64 Linux, JDK 11, Maven, GCC, Git and OpenBLAS development headers.
The build pins netlib-java commit `52b3a8beb23ee98d26eb66fa67d904c704e01e45`:

```sh
cd native/netlib-aarch64
./build-netlib-aarch64.sh
```

The retained resource was built with OpenBLAS 0.3.29; SHA-256:
`455ccc4d6455554f86aeb53fb54ab02d60821ba27c4c78d6fdf3afb23a9d7f36`.
The host must provide the same OpenBLAS ABI. `dist/` is a local output;
the versioned runtime resource is in `spark-lp/src/main/resources`.

## Runtime check

`NativeNetlib` selects native LAPACK automatically on Linux AArch64, falling back
to Java when loading fails. Native BLAS stays opt-in. The jniloader architecture
warning is expected; check the actual implementation in driver and executor JVMs:

```sh
spark-submit --class com.github.vbmacher.spark_lp.NativeNetlibProbe \
  --conf spark.task.cpus=4 benchmarks-assembly.jar 4 --require-native
```

This is a packaging check, not a timed benchmark. Standalone netlib-java needs
`-Dcom.github.fommil.netlib.NativeSystemLAPACK.natives=netlib-native_system-linux-aarch64.so`
in both driver and executor Java options because jniloader 1.1 does not recognize
AArch64. See the [self-contained BLAS package](../blas-aarch64/) for BLAS opt-in.

## Performance checks

Use the shared runner's `kernels` suite for packed/full factorization and the LP
suites for end-to-end comparisons; see [benchmark operations](../../benchmarks/README.md).
The kernel suite declares the 1/2/4/8 native-thread sweep. For an explicit Java
fallback comparison, set `JAVA_TOOL_OPTIONS` to
`-Dcom.github.fommil.netlib.LAPACK=com.github.fommil.netlib.F2jLAPACK`
before invoking the runner; runtime diagnostics record the selected implementation.
Use separate Bencher testbeds for different native implementations.

Prior measurements remain in the [native evidence archive](../../benchmarks/archive/native-aarch64/).
