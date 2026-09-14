# Milestone 1.5.0 implementation and validation

Implemented against committed base `9208f8e`; ticket branches remain separate:

| Ticket | Branch | Commit | Scope |
|---|---|---|---|
| #68 | feature-68 | ea761f1 | Original-model evidence, independent verification, QP ray safeguard |
| #69 | feature-69 | 68939ef | Separable continuous convex QP |
| #70 | feature-70 | 42bf787 | Sparse PSD factors; based on feature-69 |
| #71 | feature-71 | 3309fc0 | Optional GPU prototype and measured deferral recommendation |

This detached integration checkout combines all four branches. The #68/#69 compiler
metadata conflict is resolved by retaining both the solver diagonal and original
unshifted coefficients. Integration additionally passes original curvature into the
public evidence snapshot. `QpEvidenceSuite` checks shifted-QP coefficients, coupled
infeasibility proofs and curvature-aware recession verification. The original ticket
branches were not rebased or merged into one another, apart from #70's declared #69 base.

Validation on 2026-09-14:

- Combined Spark 3.5 suite: **191 tests, 24 suites, all passed**.
- #69 full Spark 3.5 suite: 179 tests passed; seven QP tests passed on each of seven Spark variants.
- #70 focused Spark 3.5 suite: 22 tests passed; five coupled tests passed on each of seven Spark variants.
- #68 certificate suite: seven tests passed on each Spark variant, then nine final tests passed on Spark 3.5 after adding verifier edge cases.
- Sixteen end-to-end separable/factor comparison attempts reached the analytic optimum.
- Twelve Spark CPU profiling attempts succeeded; four OpenCL FP32 operator runs failed the requested numerical accuracy and hardware FP64 compilation failed explicitly.
- Changed files passed `git diff --check`; Python prototype compiled; overwrite protection and every compressed event-stream hash were checked.

Re-run the combined regression suite from this checkout:

```sh
sbt 'spark-lpSpark_3_52_12/test'
```

Read [certificates](certificates.md), [quadratic objectives](quadratic-programming.md),
[QP comparisons](../benchmarks/quadratic/README.md), and the
[GPU report](../benchmarks/gpu/README.md) for APIs, restrictions, exact commands and records.
Coupled free-variable models require regularized CG; Auto selects it. Sparse PSD
factors are supported, not arbitrary unverified Hessian entries. GPU work concludes
with a local deferral, not a production backend or a claim of GPU speedup.

All commits are local; this is implementation/validation, not a published 1.5.0 release.
