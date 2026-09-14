#!/usr/bin/env python3
"""Optional OpenCL weighted-normal-operator spike; emits every attempted case as JSONL.
No Spark or core dependency changes. CPU reference is FP64 scipy CSR.
"""
import argparse
import json
import platform
import time
import traceback
from pathlib import Path

import numpy as np
import scipy
from scipy import sparse
import pyopencl as cl

KERNEL = r"""
__kernel void spmv(__global const int *ptr, __global const int *idx,
                   __global const float *a, __global const float *x,
                   __global const float *w, __global float *out, int n) {
    int row = get_global_id(0);
    if (row >= n) return;
    float s = 0.0f;
    for (int j = ptr[row]; j < ptr[row+1]; ++j) s += a[j] * x[idx[j]];
    out[row] = w[row] * s;
}
"""

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--repeats", type=int, default=20)
    args = ap.parse_args()
    if args.repeats < 1:
        ap.error("repeats must be positive")
    if args.output.exists():
        ap.error("output already exists; use a new path to preserve benchmark records")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x") as output:
        def record(value):
            output.write(json.dumps(value, allow_nan=False) + "\n")
            output.flush()
            print(json.dumps(value, allow_nan=False), flush=True)
        devices = [d for p in cl.get_platforms() for d in p.get_devices()
                   if d.type & cl.device_type.GPU]
        if not devices:
            record(dict(status="unavailable", reason="No OpenCL GPU; CPU devices are not GPU results"))
            return
        device = devices[0]
        start = time.perf_counter()
        context = cl.Context([device])
        queue = cl.CommandQueue(context, properties=cl.command_queue_properties.PROFILING_ENABLE)
        program = cl.Program(context, KERNEL).build()
        kernel = cl.Kernel(program, "spmv")
        startup = time.perf_counter() - start
        record(dict(kind="environment", host=platform.platform(), python=platform.python_version(),
                    numpy=np.__version__, scipy=scipy.__version__, pyopencl=cl.VERSION_TEXT,
                    device=device.name, driver=device.driver_version, opencl=device.version,
                    fp64=bool(device.double_fp_config), global_memory_bytes=device.global_mem_size,
                    startup_seconds=startup, repeats=args.repeats, seed=7100,
                    scope="single-process weighted normal operator; Spark communication not measured"))
        try:
            cl.Program(context, "__kernel void fp64(__global double *x) { x[0] = x[0] / 3.0; }").build()
            record(dict(kind="fp64_build", status="compiled", advertised_support=bool(device.double_fp_config)))
        except cl.Error as error:
            record(dict(kind="fp64_build", status="failed", error=str(error)))
        for n, m, density, ill in [(4096, 64, .05, False), (16384, 256, .02, False),
                                   (4096, 256, 1.0, False), (4096, 64, .05, True)]:
            buffers = []
            try:
                rng = np.random.default_rng(7100)
                a = sparse.random(n, m, density=density, random_state=rng,
                                  data_rvs=lambda size: rng.standard_normal(size), format="csr", dtype=np.float64)
                if ill:
                    # Nearly cancelling columns; FP32 erases their 1e-9 difference.
                    a = sparse.hstack([a[:, :1], a[:, :1] * (1 + 1e-9), a[:, 2:]], format="csr")
                at = a.T.tocsr()
                w = np.geomspace(1e-3, 1e3, n)
                x = rng.standard_normal(m)
                if ill:
                    x[:] = 0; x[0] = 1; x[1] = -1
                reference = at @ (w * (a @ x))
                cpu_start = time.perf_counter()
                for _ in range(args.repeats):
                    cpu = at @ (w * (a @ x))
                cpu_seconds = (time.perf_counter() - cpu_start) / args.repeats
                allocation_start = time.perf_counter()
                def upload(values, dtype):
                    values = np.ascontiguousarray(values, dtype=dtype)
                    buffer = cl.Buffer(context, cl.mem_flags.READ_ONLY | cl.mem_flags.COPY_HOST_PTR, hostbuf=values)
                    buffers.append(buffer)
                    return buffer
                def csr(matrix):
                    return (upload(matrix.indptr, np.int32), upload(matrix.indices, np.int32),
                            upload(matrix.data, np.float32))
                ga, gat = csr(a), csr(at)
                gx, gw, ones = upload(x, np.float32), upload(w, np.float32), upload(np.ones(m), np.float32)
                tmp = cl.Buffer(context, cl.mem_flags.READ_WRITE, n * 4); buffers.append(tmp)
                out = cl.Buffer(context, cl.mem_flags.READ_WRITE, m * 4); buffers.append(out)
                queue.finish()
                allocation_seconds = time.perf_counter() - allocation_start
                result = np.empty(m, dtype=np.float32)
                transfer_x = x.astype(np.float32)
                kernel_ns = 0
                start = time.perf_counter()
                for _ in range(args.repeats):
                    cl.enqueue_copy(queue, gx, transfer_x)
                    e1 = kernel(queue, (n,), None, *ga, gx, gw, tmp, np.int32(n))
                    e2 = kernel(queue, (m,), None, *gat, tmp, ones, out, np.int32(m))
                    cl.enqueue_copy(queue, result, out).wait()
                    kernel_ns += e1.profile.end - e1.profile.start + e2.profile.end - e2.profile.start
                seconds = (time.perf_counter() - start) / args.repeats
                relative_error = np.linalg.norm(result.astype(np.float64) - reference) / max(np.linalg.norm(reference), 1e-300)
                record(dict(kind="normal_operator", status="success", n=n, m=m, density=density,
                            ill_conditioned=ill, nnz=a.nnz, cpu_fp64_seconds=cpu_seconds,
                            gpu_fp32_seconds_including_vector_transfers_and_sync=seconds,
                            gpu_kernel_seconds=kernel_ns / args.repeats / 1e9,
                            matrix_upload_and_allocation_seconds=allocation_seconds,
                            cold_seconds=startup + allocation_seconds + seconds,
                            buffer_bytes=sum(b.size for b in buffers), relative_error=float(relative_error),
                            passes_1e_8=bool(relative_error <= 1e-8)))
            except Exception as error:
                record(dict(kind="normal_operator", status="failed", n=n, m=m, density=density,
                            ill_conditioned=ill, error=str(error), traceback=traceback.format_exc()))
            finally:
                queue.finish()
                for buffer in reversed(buffers):
                    buffer.release()
        queue.finish()

if __name__ == "__main__":
    main()
