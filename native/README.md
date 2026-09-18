# Native packages

Reproducible native packages used by `spark-lp`:

- [`netlib-aarch64`](netlib-aarch64/) — Linux AArch64 netlib-java JNI wrapper for
  the bundled native LAPACK backend (wraps the system OpenBLAS).
- [`blas-aarch64`](blas-aarch64/) — self-contained Linux AArch64 netlib-java BLAS
  JNI wrapper with OpenBLAS linked into the resource.

Generated `dist/` directories are local build outputs; the versioned runtime
resources shipped in `spark-lp` live under `spark-lp/src/main/resources`.
