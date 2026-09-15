# Verifying solve evidence

Continuous solve results expose `solution.evidence: Option[LpEvidence]`. `None`
means evidence is unavailable — including optimal, stopped, and integer solves.
Presolve validation errors (contradictory declared bounds, inconsistent duplicate
equalities, impossible constant rows) raise exceptions instead of returning a result.

```scala
val solution = model.solve()
try {
  solution.evidence.foreach { proof =>
    val checked = proof.verify(tolerance = 1e-7)
    println(s"valid=${checked.valid} residual=${checked.residual} margin=${checked.margin}")
  }
} finally solution.close()
```

## Infeasibility certificate

`InfeasibilityCertificate` exposes original row multipliers (with names and group
labels) and distributed bound multipliers keyed by
`(setIndex, encodedKey) -> (lower, upper)`. `model.variables` carries display names,
declared bounds, and the original sparse coefficients; fixed variables are retained.
Merged or presolved rows have zero multipliers. Rows are never rescaled, so
multipliers keep original row units, and bound shifts change only the right-hand
side. Free-variable splits are recombined.

The sign convention is `A^T y + lower + upper = 0` with
`b^T y + l^T lower + u^T upper = 1`. A `<=` or upper-bound multiplier is nonpositive;
a `>=` or lower-bound multiplier is nonnegative; equality multipliers are
unrestricted; absent-bound multipliers must be zero.

## Unbounded direction

`UnboundedDirection` exposes an original-unit direction and, when the solver proved
primal feasibility, a point. The direction is zero on fixed variables, omits bound
shifts, normalizes its objective improvement to one in the model's objective sense,
and obeys homogeneous row and bound recession conditions. Without a point, `verify`
returns `valid=false`, because a direction alone cannot separate infeasibility from
unboundedness. To verify a separately obtained original-model point, use
`proof.copy(point = Some(values))`.

## Independent verification

`LpEvidenceVerifier` recomputes these conditions against the materialised
original-model snapshot, including eliminated variables and rows. It checks missing
or duplicate keys, nonfinite values, signs, stationarity, feasibility, and
normalization; it does not trust solver status or internal residuals. The snapshot
can also carry diagonal curvature, in which case a recession direction must satisfy
`Q*d = 0`.

Residuals use absolute original units, so large coefficient scales or cancellation
can fail a tight tolerance even for a correctly classified result. These are
numerical witnesses, not exact-arithmetic proofs, and the caller chooses the
tolerance. `solution.close()` releases the snapshots, so finish Spark actions first.
No IIS or MIP proof is implied.

For quadratic models the snapshot keeps the original unshifted linear cost and
diagonal curvature. Coupled objectives expose the factor-expanded model with its
named auxiliary rows and variables; verifying that equivalent formulation certifies
the original problem after projection onto the user variables. These auxiliary
fields are explicit, never silently discarded.
