# Verifying solve evidence

Continuous solve results expose `solution.evidence: Option[LpEvidence]`.
`None` means evidence is unavailable, including optimal, stopped and integer solves.
Presolve validation errors (contradictory declared bounds, inconsistent
duplicate equalities, impossible constant rows) raise exceptions rather than return solve results.

```scala
val solution = model.solve()
try {
  solution.evidence.foreach { proof =>
    val checked = proof.verify(tolerance = 1e-7)
    println(s"valid=${checked.valid} residual=${checked.residual} margin=${checked.margin}")
  }
} finally solution.close()
```

`InfeasibilityCertificate` exposes original row multipliers (including names and
group labels) and distributed `(setIndex, encodedKey) -> (lower, upper)` bound
multipliers. `model.variables` includes display names, declared bounds and the
original sparse coefficients. Fixed variables are retained. Merged/presolved rows
have zero multipliers. This compiler does not rescale emitted rows: their internal
multipliers have original row units. Bound shifts change the right-hand
side, not the row multiplier. Free-variable splits are recombined.

The convention is `A^T y + lower + upper = 0`, with
`b^T y + l^T lower + u^T upper = 1`. A `<=` or upper-bound multiplier
is nonpositive; a `>=` or lower-bound multiplier is nonnegative. Equality
multipliers are unrestricted. Absent-bound multipliers must be zero.

`UnboundedDirection` exposes an original-unit direction and, when the solver
proved primal feasibility, a point. The direction has zero components for fixed
variables and omits bound shifts. Its objective improvement is normalized to one
in the model's objective sense. It obeys homogeneous row and bound recession
conditions. Without a point, `verify` returns `valid=false`: the direction alone
cannot distinguish infeasibility from unboundedness. A caller may verify a
separately obtained original-model point with `proof.copy(point = Some(values))`.

The public `LpEvidenceVerifier` independently recomputes these conditions against
the materialised original-model snapshot, including eliminated variables and rows.
It checks missing/duplicate keys, nonfinite proof values, signs, stationarity,
feasibility and normalization. The snapshot can also carry diagonal curvature;
a recession direction must satisfy `Q*d = 0`. It does not trust status or internal residuals.
Residuals use absolute original units; large coefficient scales or cancellation
can make evidence fail a tighter tolerance even when the solver classified it.
These are numerical witnesses, not exact-arithmetic proofs. The caller selects
the verification tolerance. `solution.close()` releases the evidence snapshots;
finish Spark actions before closing. No IIS or MIP proof is implied.

For quadratic models the compiler stores the original unshifted linear
cost and diagonal curvature in the snapshot. Coupled objectives expose the
factor-expanded model, including named auxiliary rows/variables; verification of
this equivalent formulation certifies the original problem after projection onto
user variables. These auxiliary fields are explicit, not silently discarded.
