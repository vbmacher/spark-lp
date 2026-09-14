# Separable convex quadratic objectives

Continuous models support `0.5 * sum(q_i * x_i^2) + c^T x + k`.
For minimization, each aggregated `q_i` must be nonnegative; maximization requires
nonpositive curvature (a concave objective). QP uses the core runtime dependencies.

```scala
import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.dsl.implicits._
val model = LpProblem("fit")
val x = model.variable("x", lowerBound = -3.0, upperBound = Some(4.0))
model += QpObjective.squaredDeviation(x, target = 2.0, weight = 3.0)
val result = model.solve()
try println(result.value(x)) finally result.close()
```

`QpObjective.separable(diagonal, linear)` accepts scalar and keyed
expressions. Coefficients in `diagonal` specify curvature, not linear cost:
`QpObjective.separable(vars.sum(col("q")), vars.sum(col("c")))`.
The `weightedBy` API can supply typed keyed coefficients. Repeated terms
sum; missing keys contribute zero; invalid or duplicate source keys are rejected.
A curvature expression cannot carry a constant. Objectives can be added, combined
with linear expressions, and scaled; negate a convex objective for maximization.
Replacing it with `setObjective(linearExpression)` restores the linear path.

Bound shifts preserve `c' = c + Q*l` and
`k' = k + c^T*l + 0.5*l^T Q*l`. Fixed variables fold into the constant.
All-fixed objectives are evaluated directly. Curved free variables are rejected:
splitting them introduces coupled curvature. Zero-curvature free variables use
the LP behavior. Integer/binary categories and quadratic constraints are unsupported
(the latter have no DSL comparison operators). Invalid curvature and nonfinite
coefficients raise `LpModelException`. All-zero curvature uses the LP path,
including its validation restrictions. Unconstrained nonzero-curvature objectives
use an independent dummy equality, satisfying the initialization contract.

Both Cholesky and CG solve the weighted normal system using
`W = (S/X + Q + Rp)^(-1)`. Direction recovery includes `Q*dx`. Driver memory is
quadratic in the constraint count for Cholesky; CG keeps the operator distributed.
The diagonal is partitioned with the sparse columns.

The stationarity residual is `A^T*lambda + s - c - Q*x`. The primal objective is
`0.5*x^T Q*x + c^T*x`; the dual expression is `b^T*lambda - 0.5*x^T Q*x`.
Convergence requires the primal, stationarity and quadratic objective-gap tolerances.
See the [QP optimality and certificate conditions](https://osqp.org/docs/solver/).
A candidate unbounded direction must also satisfy `Q*d = 0` within certificate
tolerance. A feasible point is required to classify unboundedness.

Default convergence tolerance is `1e-8`, CG inner tolerance `1e-10`.
At degenerate bounds, a small objective gap can coexist with larger variable error;
check stationarity with bound multipliers and complementary products rather than
requiring identical raw gradients. `QpSuite` independently checks analytic optima,
KKT witnesses, objective constants, both backends, keyed inputs and rejection paths.


## Coupled convex objectives: sparse factors

Use `QpObjective.squared(expression, weight)` or
`QpObjective.sumSquares(Seq(expression -> weight, ...))` for sparse cross-variable
terms. Each factor is a scalar/keyed linear expression. For example:

```scala
model += QpObjective.squared(x + 2.0 * y - 5.0) +
         QpObjective.squared(x - y - 1.0)
```

This represents `sum_k w_k * (a_k^T x + t_k)^2`. Under the standard
`0.5*x^T Q*x + c^T*x + k` convention, `Q = 2*sum_k w_k*a_k*a_k^T`.
A factor can use `vars.sum(col("coefficient"))` or `weightedBy` for sparse keyed
coefficients; every pair of keys in a factor produces the corresponding symmetric
cross term. Repeated coefficients within a factor sum before solving. Repeated
factors add their penalties. No dense Hessian or explicit inverse is constructed.

Nonnegative factor weights prove PSD structurally, including rank deficiency.
Negative weights in a minimization objective are rejected; negate the entire
objective for concave maximization. Arbitrary raw Hessian entries are deliberately
not accepted: the caller must supply a PSD factorization, rather than rely on
nonnegative diagonal checks or random probes. The representation supports any
provided sparse PSD factorization; dense factors can be expensive.
Integer/binary variables and quadratic constraints are unsupported.

For each factor, compilation introduces `u,v >= 0`, the linear equality
`a^T*x+t = u-v`, and diagonal objective `w*(u^2+v^2)`.
For a fixed residual, its minimum is attained with one of `u,v` zero and equals
`w*(a^T*x+t)^2`. This is an exact convex reduction at the optimum. The lifted
stationarity equations recover the original gradient through the factor-row
multipliers. The diagonal-QP solver checks primal/dual residuals and complementarity,
and its recession test requires zero quadratic curvature. A nonzero-curvature
auxiliary direction cannot pass that condition.

The reduction adds two columns and one row per factor and uses the
weighted normal operator on the enlarged sparse matrix. CG applies a positive
definite regularized normal system; it is never applied directly to an indefinite
KKT matrix. Cholesky has quadratic driver storage in the enlarged row count;
CG retains distributed sparse columns and bounded preconditioning storage.
Near-zero residuals and free-variable splits can cause poor conditioning.
**Coupled models containing free variables use regularized CG with Auto; explicit
Cholesky is rejected.** Free-variable splitting can make the coupled Cholesky
system numerically singular near convergence; see the
[conditioning evidence](../benchmarks/quadratic/failed-cholesky-free.txt).

Factor lifting supports free, shifted and fixed original variables: all cross terms
are preserved by transforming the complete factor equality. Auxiliary variables
are created per compilation and never mutate the user problem. Diagnostics include
`__qp_factor_*` rows and the returned keyed internal snapshots include auxiliary
columns; `value`/`values` for the user's variables reconstruct their original units.
Reserve the `__qp_factor_` prefix for compiler-generated names. Replacing an
objective or solving repeatedly does not accumulate variables or constraints.

The [QP validation report](../benchmarks/quadratic/README.md) covers lifted
linear-system validation, end-to-end solves against analytic solutions,
accuracy and memory records. Use separable objectives for diagonal Hessians;
this representation offers cross terms and a structural convexity contract, not a
performance advantage over the specialized diagonal path.
