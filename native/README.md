# Native packages

This directory contains reproducible native packages used by `spark-lp`.

- [`netlib-aarch64`](netlib-aarch64/) builds the Linux AArch64 netlib-java JNI
  wrapper used by the bundled native LAPACK backend.
- [`blas-aarch64`](blas-aarch64/) builds a self-contained Linux AArch64
  netlib-java BLAS JNI wrapper with OpenBLAS linked into the resource.

Generated `dist/` directories are local build outputs. The versioned runtime
resources shipped in `spark-lp` live under `spark-lp/src/main/resources`.
