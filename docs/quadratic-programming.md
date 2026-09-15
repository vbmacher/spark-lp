# Separable convex quadratic objectives

Continuous models support `0.5 * sum(q_i * x_i^2) + c^T x + k`. For minimization each
aggregated `q_i` must be nonnegative; maximization requires nonpositive curvature (a
concave objective). QP uses only the core runtime dependencies.

```scala
import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.dsl.implicits._
val model = LpProblem("fit")
val x = model.variable("x", lowerBound = -3.0, upperBound = Some(4.0))
model += QpObjective.squaredDeviation(x, target = 2.0, weight = 3.0)
val result = model.solve()
try println(result.value(x)) finally result.close()
```

## Building a separable objective

`QpObjective.separable(diagonal, linear)` accepts scalar and keyed expressions. The
`diagonal` coefficients specify curvature, not linear cost:
`QpObjective.separable(vars.sum(col("q")), vars.sum(col("c")))`. Use `weightedBy` for
typed keyed coefficients. Repeated terms sum, missing keys contribute zero, and
invalid or duplicate source keys are rejected. A curvature expression cannot carry a
constant. Objectives can be added, combined with linear expressions, and scaled;
negate a convex objective for maximization. `setObjective(linearExpression)` restores
the linear path.

Bound shifts preserve `c' = c + Q*l` and `k' = k + c^T*l + 0.5*l^T Q*l`, and fixed
variables fold into the constant; all-fixed objectives are evaluated directly. Curved
free variables are rejected, because splitting them introduces coupled curvature;
zero-curvature free variables use the LP behavior. Integer and binary categories and
quadratic constraints are unsupported (quadratic constraints have no DSL comparison
operators). Invalid curvature and nonfinite coefficients raise `LpModelException`. An
all-zero curvature objective uses the LP path and its validation restrictions. An
unconstrained nonzero-curvature objective adds an independent dummy equality to
satisfy the initialization contract.

## How it solves

Both Cholesky and CG solve the weighted normal system with
`W = (S/X + Q + Rp)^(-1)`, and direction recovery includes `Q*dx`. Cholesky driver
memory is quadratic in the constraint count; CG keeps the operator distributed. The
diagonal is partitioned with the sparse columns.

The stationarity residual is `A^T*lambda + s - c - Q*x`. The primal objective is
`0.5*x^T Q*x + c^T*x` and the dual expression is `b^T*lambda - 0.5*x^T Q*x`;
convergence requires the primal, stationarity, and quadratic objective-gap tolerances
(see the [QP optimality and certificate conditions](https://osqp.org/docs/solver/)).
A candidate unbounded direction must also satisfy `Q*d = 0` within certificate
tolerance, and classifying unboundedness needs a feasible point.

Defaults are `1e-8` convergence tolerance and `1e-10` CG inner tolerance. At
degenerate bounds a small objective gap can coexist with larger variable error, so
check stationarity with bound multipliers and complementary products rather than
requiring identical raw gradients. `QpSuite` independently checks analytic optima,
KKT witnesses, objective constants, both backends, keyed inputs, and rejection paths.

## Coupled convex objectives: sparse factors

Use `QpObjective.squared(expression, weight)` or
`QpObjective.sumSquares(Seq(expression -> weight, ...))` for sparse cross-variable
terms, where each factor is a scalar or keyed linear expression:

```scala
model += QpObjective.squared(x + 2.0 * y - 5.0) +
         QpObjective.squared(x - y - 1.0)
```

This represents `sum_k w_k * (a_k^T x + t_k)^2`, so under the
`0.5*x^T Q*x + c^T*x + k` convention `Q = 2*sum_k w_k*a_k*a_k^T`. A factor can use
`vars.sum(col("coefficient"))` or `weightedBy` for sparse keyed coefficients, and
every pair of keys in a factor produces the matching symmetric cross term. Repeated
coefficients within a factor sum before solving, and repeated factors add their
penalties. No dense Hessian or explicit inverse is constructed.

Nonnegative factor weights prove PSD structure, including rank deficiency. Negative
weights are rejected in a minimization objective; negate the whole objective for
concave maximization. Arbitrary raw Hessian entries are deliberately not accepted —
the caller must supply a PSD factorization rather than rely on nonnegative diagonal
checks or random probes. Any provided sparse PSD factorization works, though dense
factors can be expensive. Integer and binary variables and quadratic constraints are
unsupported.

Per factor, compilation introduces `u,v >= 0`, the equality `a^T*x+t = u-v`, and the
diagonal objective `w*(u^2+v^2)`. For a fixed residual the minimum is reached with one
of `u,v` zero and equals `w*(a^T*x+t)^2` — an exact convex reduction at the optimum.
The lifted stationarity equations recover the original gradient through the
factor-row multipliers. The diagonal-QP solver checks primal/dual residuals and
complementarity, and its recession test requires zero quadratic curvature, so a
nonzero-curvature auxiliary direction cannot pass.

The reduction adds two columns and one row per factor and applies the weighted normal
operator to the enlarged sparse matrix. CG uses a positive definite regularized
normal system and is never applied directly to the indefinite KKT matrix. Cholesky
storage is quadratic in the enlarged row count; CG retains distributed sparse columns
and bounded preconditioning storage. Near-zero residuals and free-variable splits can
cause poor conditioning. **Coupled models with free variables use regularized CG
under Auto; explicit Cholesky is rejected**, because free-variable splitting can make
the coupled Cholesky system numerically singular near convergence (see the
[conditioning evidence](../benchmarks/quadratic/failed-cholesky-free.txt)).

Factor lifting supports free, shifted, and fixed original variables: transforming the
complete factor equality preserves all cross terms. Auxiliary variables are created
per compilation and never mutate the user problem. Diagnostics include
`__qp_factor_*` rows, and the returned keyed internal snapshots include auxiliary
columns, while `value`/`values` for the user's variables reconstruct their original
units. Reserve the `__qp_factor_` prefix for compiler-generated names. Replacing an
objective or solving repeatedly does not accumulate variables or constraints.

The [QP validation report](../benchmarks/quadratic/README.md) covers lifted
linear-system validation, end-to-end solves against analytic solutions, and accuracy
and memory records. Use separable objectives for diagonal Hessians; this
representation adds cross terms and a structural convexity contract, not a
performance advantage over the specialized diagonal path.
