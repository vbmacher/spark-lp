# Separable convex quadratic objectives

Continuous models support `0.5 * sum(q_i * x_i^2) + c^T x + k`.
For minimization, each aggregated `q_i` must be nonnegative; maximization requires
nonpositive curvature (a concave objective). No new runtime dependency is needed.

```scala
import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.dsl.implicits._
val model = LpProblem("fit")
val x = model.variable("x", lowerBound = -3.0, upperBound = Some(4.0))
model += QpObjective.squaredDeviation(x, target = 2.0, weight = 3.0)
val result = model.solve()
try println(result.value(x)) finally result.close()
```

`QpObjective.separable(diagonal, linear)` accepts existing scalar and keyed
expressions. Coefficients in `diagonal` specify curvature, not linear cost:
`QpObjective.separable(vars.sum(col("q")), vars.sum(col("c")))`.
The existing `weightedBy` API can supply typed keyed coefficients. Repeated terms
sum; missing keys contribute zero; invalid or duplicate source keys are rejected.
A curvature expression cannot carry a constant. Objectives can be added, combined
with linear expressions, and scaled; negate a convex objective for maximization.
Replacing it with `setObjective(linearExpression)` restores the linear path.

Bound shifts preserve `c' = c + Q*l` and
`k' = k + c^T*l + 0.5*l^T Q*l`. Fixed variables fold into the constant.
All-fixed objectives are evaluated directly. Curved free variables are rejected:
splitting them introduces coupled curvature. Zero-curvature free variables retain
the LP behavior. Integer/binary categories and quadratic constraints are unsupported
(the latter have no DSL comparison operators). Invalid curvature and nonfinite
coefficients raise `LpModelException`. All-zero curvature uses the existing LP path,
including its validation restrictions. Unconstrained nonzero-curvature objectives
use an independent dummy equality, preserving the existing initialization contract.

Both Cholesky and CG solve the weighted normal system, now using
`W = (S/X + Q + Rp)^(-1)`. Direction recovery includes `Q*dx`. Driver memory remains
quadratic in the constraint count for Cholesky; CG keeps the operator distributed.
The diagonal remains partitioned with the existing sparse columns.

The stationarity residual is `A^T*lambda + s - c - Q*x`. The primal objective is
`0.5*x^T Q*x + c^T*x`; the dual expression is `b^T*lambda - 0.5*x^T Q*x`.
Convergence requires the existing primal, stationarity and relative gap tolerances;
this is not an LP objective gap reused unchanged. See the
[QP optimality and certificate conditions](https://osqp.org/docs/solver/).
A candidate unbounded direction must also satisfy `Q*d = 0` within certificate
tolerance. A feasible point is still required to classify unboundedness.

Default convergence tolerance is `1e-8`, CG inner tolerance `1e-10`.
At degenerate bounds, a small objective gap can coexist with larger variable error;
check stationarity with bound multipliers and complementary products rather than
requiring identical raw gradients. `QpSuite` independently checks analytic optima,
KKT witnesses, objective constants, both backends, keyed inputs and rejection paths.
