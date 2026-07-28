# DataFrame/Dataset LP DSL design

Status: proposal for issues [#8](https://github.com/vbmacher/spark-lp/issues/8) and [#9](https://github.com/vbmacher/spark-lp/issues/9). This is a design document only. The existing `LP.solve(c, AT, b)` signature is unchanged; the sole solver-side addition is an internal solve variant that also reports iteration count, convergence, and final residuals, and wraps numerical failures in a typed exception (see "Results and errors").

## Goal

Expose a model-building API with the same flow as PuLP:

1. create a problem and its objective sense;
2. create one variable or a keyed family of variables;
3. build a linear objective and constraints with arithmetic and comparison operators;
4. solve; and
5. retrieve named values and constraint diagnostics.

The public input and output are Spark `DataFrame`s or `Dataset`s. Internally, the builder compiles the declarative model to the existing equality-form solver:

```
minimize cᵀx, subject to Ax = b and x >= 0
```

This removes the need for callers to hand-build slack columns or to keep the position of an element in a `DVector`/`DMatrix` in sync with an application record.

## Non-goals for the first release

- Replacing the numerical solver or its `LP.solve(c, AT, b)` API.
- Claiming that arbitrary, infeasible, or rank-deficient LPs can be solved. Existing solver preconditions remain explicit.
- Making a driver-sized right-hand-side vector disappear. The present solver uses a local `DenseVector` for `b`; the compiler must reject a model whose constraint rows cannot safely be collected on the driver.

Mixed-integer optimisation is part of the DSL: `Integer` and `Binary` are ordinary variable
categories and `solve()` chooses the appropriate internal solve path automatically.

## Public API

All new public types live in `com.github.vbmacher.spark_lp.dsl`. Users opt into the operators, so the base Spark namespace is not polluted:

```scala
import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.dsl.implicits._
import org.apache.spark.sql.functions._
```

The following signatures describe the intended surface. Exact implementation details can remain private.

```scala
sealed trait ObjectiveSense
case object Minimize extends ObjectiveSense
case object Maximize extends ObjectiveSense

sealed trait VariableCategory
case object Continuous extends VariableCategory
case object Integer extends VariableCategory // integral values within declared finite bounds
case object Binary extends VariableCategory  // domain {0,1} ∩ declared bounds

sealed trait LpStatus
object LpStatus {
  case object Optimal extends LpStatus        // all solver convergence conditions met within tolerance
  case object IterationLimit extends LpStatus // maxIterations reached; values are the last iterate,
                                              // which need NOT be primal-feasible (see residuals)
  case object Infeasible extends LpStatus     // certificate-backed: a Farkas ray proves no feasible point exists
  case object Unbounded extends LpStatus      // certificate-backed unbounded ray plus a primal-feasible iterate
  case object InfeasibleOrUnbounded extends LpStatus // dual-infeasibility certificate without a
                                              // primal-feasible iterate; the two cases are indistinguishable
}

sealed abstract class LpException(message: String) extends RuntimeException(message)

/** Model or validation error; always names the offending variable, constraint, or key. */
final class LpModelException private[dsl] (...) extends LpException(...)

/** Numerical failure inside the solver: a non-positive-definite Gramian during initialization
  * or an iteration's Cholesky step, or a zero iterate element. Names the phase (initialization
  * or iteration k) and the count of completed iterations. No iterate values are exposed —
  * a numerically failed run has no iterate with meaningful convergence semantics.
  * Before this exception is thrown, the last successful iterate is tested for an infeasibility
  * certificate; when one exists the run terminates with the corresponding status instead. */
final class LpNumericalException private[dsl] (...) extends LpException(...)

final case class SolveConfig(
  tolerance: Double = 1e-8,
  infeasibilityTolerance: Double = 1e-8, // acceptance threshold for Farkas certificates; see "Results and errors"
  maxIterations: Int = 50,
  etaIteration: Double = 0.999,
  valueCap: Double = 1e20,
  epsilon: Double = 1e-20,
  maxLocalConstraints: Long = 5000L // every constraint row is driver-local; see "Compilation contract"
)

object LpProblem {
  def apply(name: String, sense: ObjectiveSense = Minimize)
           (implicit spark: SparkSession): LpProblem
}

final class LpProblem private[dsl] (...) {
  def variable(
    name: String,
    lowerBound: Double = 0.0,
    upperBound: Option[Double] = None,
    category: VariableCategory = Continuous
  ): LpVariable

  /** One decision variable per unique value of `key` in `domain`. */
  def variables(
    name: String,
    domain: DataFrame,
    key: Column,
    lowerBound: Double = 0.0,
    upperBound: Option[Double] = None,
    category: VariableCategory = Continuous
  ): LpVariableSet[Row]

  /** Typed variant of `variables`. Key types beyond String are supported via LpKeyEncoder. */
  def variablesOf[K: Encoder, Key: LpKeyEncoder](
    name: String,
    domain: Dataset[K],
    key: K => Key,
    lowerBound: Double = 0.0,
    upperBound: Option[Double] = None,
    category: VariableCategory = Continuous
  ): LpVariableSet[K]

  /** Sets the objective. Throws if one is already set; use setObjective to replace deliberately. */
  def +=(objective: LpExpr): this.type
  def setObjective(objective: LpExpr): this.type

  def +=(constraint: LpConstraint): this.type
  def +=(constraints: LpConstraintSet): this.type

  /** Compiles and solves the model against the current contents of its source data;
    * may be called repeatedly. See "Compilation contract" for snapshot semantics. */
  def solve(config: SolveConfig = SolveConfig()): LpSolution
}
```

`LpVariableSet[K]` represents one symbolic decision variable for every unique key in `domain`; it is not a collected Scala map. Its original domain is retained so a solved value can be joined back to application columns. The typed variant is deliberately *not* an overload of `variables`: Scala 2 rejects two overloads that both declare default arguments, so an overloaded pair would not compile. A key column may be a `struct(...)` when the natural key has several parts; a typed key may be any type with an `LpKeyEncoder` instance (provided for `String`, numeric primitives, `Boolean`, and tuples/case classes of these), which defines its canonical encoded form. The encoded key is specified in "Open decisions — resolved"; it is the identity used for sorting, deduplication, joining `weightedBy` sources, and display names.

### Expressions and constraints

`LpVariable`, `LpVariableSet`, and `LpExpr` support `+`, `-`, unary `-`, scalar multiplication, and constant terms. `LpExpr` supports `<=`, `>=`, and `===` with a numeric or `LpExpr` right-hand side; an expression RHS is normalised by moving all its terms to the left, exactly as PuLP does. Equality uses `===` rather than `==`: Scala defines `==` as ordinary object equality, so it cannot safely mean “create an LP constraint”. A stray `expr == 100.0` yields a `Boolean`, which `LpProblem.+=` does not accept, so the mistake fails at compile time rather than silently building a wrong model.

The operators are supplied by `dsl.implicits` as small implicit classes, rather than by converting Spark `Column` itself:

```scala
implicit final class VariableSetOps[K](private val x: LpVariableSet[K]) {
  def *(coefficient: Double): LpExpr
  def *(coefficient: Column): LpExpr // resolved against the set's own domain
  def unary_- : LpExpr
  def weightedBy(source: Dataset[K])(coefficient: K => Double): LpExpr
}

implicit final class ExprOps(private val expression: LpExpr) {
  def +(other: LpExpr): LpExpr
  def -(other: LpExpr): LpExpr
  def +(constant: Double): LpExpr
  def -(constant: Double): LpExpr
  def *(scale: Double): LpExpr
  def unary_- : LpExpr
  def <=(rhs: Double): LpConstraint
  def >=(rhs: Double): LpConstraint
  def ===(rhs: Double): LpConstraint
  def <=(rhs: LpExpr): LpConstraint
  def >=(rhs: LpExpr): LpConstraint
  def ===(rhs: LpExpr): LpConstraint
}

/** Puts numeric literals on the left of arithmetic, PuLP-style: 3.0 * x, 5.0 - expr. */
implicit final class DoubleLpOps(private val value: Double) {
  def *(x: LpVariable): LpExpr
  def *[K](x: LpVariableSet[K]): LpExpr
  def *(expression: LpExpr): LpExpr
  def +(expression: LpExpr): LpExpr
  def -(expression: LpExpr): LpExpr
}

implicit final class ConstraintOps(private val constraint: LpConstraint) {
  def named(name: String): LpConstraint
}

implicit final class ConstraintSetOps(private val constraints: LpConstraintSet) {
  def named(name: String): LpConstraintSet
}
```

Comparison operators are deliberately *not* added to `Double` (`100.0 >= lpSum(x)` is a compile error): enriching `Double` with comparisons overloads operators the JVM already defines on primitives, which produces confusing overload-resolution failures. Arithmetic on the left is safe and supported; comparisons keep the expression on the left.

Each operator builds an immutable logical expression and performs no Spark action. `LpProblem +=` is the only model mutation.

```scala
model += lpSum(x * $"cost")                 // first expression is the objective
model += (lpSum(x) === 100.0).named("mass")
model += (lpSum(x * $"protein") >= 8.0).named("protein_min")
model += (lpSum(x * $"fibre") <= 2.0).named("fibre_max")
```

`lpSum` is intentionally named after PuLP. It accepts a variable, a variable set, an expression, or an `Iterable` of expressions. For a coefficient held in a Spark `Column`, write `variables * $"coefficient"`; the column is resolved against the variable set's domain, and an unresolvable column is an `LpModelException` at solve time. Spark already owns `Column.*`, so `col("coefficient") * variables` would not reliably dispatch to this DSL; the compiler rejects it, and `3.0 * variables` covers the literal-on-left habit from PuLP.

`named(name)` is available on `LpConstraint` and on a bulk `LpConstraintSet`. Constraint names must be unique after expansion; a bulk constraint names its rows as `<name>[<encoded group key>]`. Unnamed constraints receive `_c<creation index>`.

## Example: Whiskas without manual slack variables

This is the current Whiskas example expressed in the proposed API. It is deliberately close to the equivalent PuLP model while preserving Spark data ownership.

```scala
import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.dsl.implicits._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

implicit val spark: SparkSession = SparkSession.builder()
  .appName("whiskas")
  .getOrCreate()
import spark.implicits._

val ingredients = Seq(
  ("chicken", 0.013, 0.100, 0.080, 0.001, 0.002),
  ("beef",    0.008, 0.200, 0.100, 0.005, 0.005)
).toDF("ingredient", "cost", "protein", "fat", "fibre", "salt")

val model = LpProblem("Whiskas", Minimize)
val amount = model.variables(
  name = "amount",
  domain = ingredients,
  key = $"ingredient",
  lowerBound = 0.0
)

model += lpSum(amount * $"cost")
model += (lpSum(amount) === 100.0).named("total_weight")
model += (lpSum(amount * $"protein") >= 8.0).named("protein_min")
model += (lpSum(amount * $"fat")     >= 6.0).named("fat_min")
model += (lpSum(amount * $"fibre")   <= 2.0).named("fibre_max")
model += (lpSum(amount * $"salt")    <= 0.4).named("salt_max")

val solution = model.solve()
require(solution.status == LpStatus.Optimal)

solution.values(amount)
  .select("ingredient", "lp_value")
  .orderBy("ingredient")
  .show()

println(solution.objectiveValue)
solution.constraints.select("name", "activity", "sense", "rhs", "slack").show()
```

The model builder expands the two `>=` constraints as `a x - s = b` and the two `<=` constraints as `a x + s = b`, with non-negative, internal slack variables. Slack variables are not returned by `solution.values(amount)`; diagnostic slack is exposed through `solution.constraints`.

For a typed domain, the equivalent declaration keeps the key function type-safe:

```scala
case class Ingredient(name: String, cost: Double, protein: Double)
val ingredientDs: Dataset[Ingredient] = ...

val amount = model.variablesOf("amount", ingredientDs, _.name)
val cost = amount.weightedBy(ingredientDs)(_.cost)
model += lpSum(cost)
model += (lpSum(amount.weightedBy(ingredientDs)(_.protein)) >= 8.0)
  .named("protein_min")
```

`weightedBy` creates distributed terms from the supplied dataset; it does not call `collect` on the domain. The supplied dataset must be keyed by the same key function as the variable set's domain; a key present in `source` but absent from the domain is an `LpModelException`, while a domain key absent from `source` contributes a zero coefficient. Duplicate keys in `source` are an `LpModelException` naming the offending keys — summing them silently would hide data errors, and no single aggregation rule is right for all models; users who intend aggregation must pre-aggregate explicitly.

## DataFrame-native bulk constraints

One scalar constraint per category or market should not require a driver-side `foreach`. The relational API represents each non-zero coefficient as a term row and each constraint right-hand side as a row keyed by the same grouping columns. `lpSumBy` returns a `GroupedLpExpr` — one symbolic expression per group key — and its comparison operators take the RHS as a DataFrame:

```scala
implicit final class GroupedExprOps(private val grouped: GroupedLpExpr) {
  def <=(rhs: DataFrame): LpConstraintSet  // rhs: grouping columns + `rhs` column
  def >=(rhs: DataFrame): LpConstraintSet
  def ===(rhs: DataFrame): LpConstraintSet
  def <=(rhs: Double): LpConstraintSet     // same scalar bound for every group
  def >=(rhs: Double): LpConstraintSet
  def ===(rhs: Double): LpConstraintSet
}
```

```scala
val shipment = model.variables(
  "shipment", arcs, key = $"arc_id", lowerBound = 0.0
)

val shippedByMarket = lpSumBy(
  shipment.terms(
    source = arcs,
    by = Seq($"market"),
    coefficient = $"volume"
  ),
  by = Seq("market")
)

model += (shippedByMarket <= capacities.select(
  $"market", $"capacity".as("rhs")
)).named("market_capacity")
```

The intermediate term schema is deliberately ordinary Spark data:

| Field | Meaning |
| --- | --- |
| grouping columns | identify one expanded constraint row |
| `variable_id` | stable internal ID of one decision variable |
| `coefficient` | finite coefficient for that variable in that row |
| `rhs` | finite right-hand-side value, supplied once per grouping key |

`lpSumBy` preserves the grouping keys and aggregates duplicate `(group, variable_id)` pairs by summing their coefficients (the standard linear-algebra meaning of repeated terms in one expression; unlike duplicate `weightedBy` keys, duplicate terms within one group are well-defined). The comparison validates the match with the RHS frame: a duplicate RHS key is an `LpModelException` naming the offending keys, as is a group with terms but no RHS row. An RHS row with no matching terms is *legal* and produces a zero-term constraint row (`0 = rhs` after normalisation): such rows arise naturally when a filter leaves a group empty, and rejecting them would make valid degenerate models inexpressible. A zero-term row with a non-zero RHS is trivially infeasible and *is* rejected at compile time as an `LpModelException` — this one case is detectable without any linear algebra. Every expanded row still becomes one driver-local equality constraint, so bulk constraints are bounded by `maxLocalConstraints` like any others; the bulk form removes driver-side model *construction*, not the solver's driver-side constraint state (see "Compilation contract").

The relational compiler consumes these term rows directly: grouped terms are sorted and assembled into the sparse row vectors of the transposed `DMatrix` partition by partition. At no point does compilation pivot terms into a wide DataFrame or materialise a dense `n × m` coefficient structure — the coefficient matrix exists only as the solver's `DMatrix` of sparse rows.

## Results and errors

```scala
/** Final residuals of the returned iterate, in the solver's minimization form:
  * primal = ‖Ax − b‖ / (1 + ‖b‖), dual = ‖Aᵀλ + s − c‖ / (1 + ‖c‖),
  * gap = |cᵀx − bᵀλ| / (1 + |bᵀλ|). All three are below `tolerance` iff status is Optimal. */
final case class LpResiduals(primal: Double, dual: Double, gap: Double)

final class LpSolution private[dsl] (...) {
  val status: LpStatus
  val objectiveValue: Double   // in the user's sense: solver optimum plus all constant terms
                               // (explicit expression constants and Σ cᵢ·lᵢ from bound shifts),
                               // with the sign restored for Maximize
  val iterations: Int
  val residuals: LpResiduals
  val constraints: DataFrame
  def values[K](variables: LpVariableSet[K]): DataFrame
  def value(variable: LpVariable): Double
}
```

The current `LP.solve` returns only `(objectiveValue, x)` and does not report whether the convergence conditions were actually met. Truthful `status`, `iterations`, and `residuals` fields therefore require one small, backwards-compatible solver change: an internal `LP.solveSummary` (used by the DSL and by the existing `solve`, which keeps its signature) that also returns the iteration count, the termination kind (converged, iteration limit, or an infeasibility certificate), and the three final residuals the solver already computes each iteration. The same change wraps the solver's numerical failure modes — a non-positive-definite Gramian in `Initialize.init` or the per-iteration Cholesky step, and the zero-iterate guard in the corrector step — in `LpNumericalException` instead of letting bare linear-algebra errors (or a `MatchError` from a partial function) escape. Without this the DSL would have to recompute residuals or, worse, report `Optimal` for a model that merely hit `maxIterations` — silently wrong answers are the one failure mode a modelling API must not have.

Because `objectiveValue` must include constants that the equality-form model cannot carry (the solver minimizes `cᵀy` over shifted variables), the compiler records the accumulated constant — explicit constants in the objective expression plus `Σ cᵢ·lᵢ` contributed by every `x = l + y` bound substitution — and adds it back before reporting. Restoring only the `Maximize` sign is not sufficient.

`values(variableSet)` returns the original variable domain plus these columns:

| Column | Meaning |
| --- | --- |
| `lp_variable` | stable, human-readable variable name, for example `amount[beef]` |
| `lp_value` | primal value in the caller’s original variable units (bound shifts and free-variable splits already undone) |

`constraints` returns `name`, grouping columns (when present), `activity`, `sense` (`<=`, `>=`, `==`), `rhs`, and `slack` (the distance to the bound in the constraint's own direction: `rhs − activity` for `<=`, `activity − rhs` for `>=`). At `Optimal`, slack is non-negative up to the solver tolerance. At `IterationLimit` and the infeasibility statuses the iterate need not be primal-feasible, so slack may be materially negative and equality rows may be violated — `residuals.primal` quantifies this, and diagnostics must not promise feasibility the iterate does not have. At `Infeasible` the negative slacks are useful precisely because of that: they locate the conflicting constraints. `constraints` also reserves `dual` for a later solver result, returning `NULL` until dual values are deliberately supported — the interior-point iterate `lambda` exists internally, but exposing it as "the dual" before validating its convergence semantics would invite misuse.

An interior-point method returns values like `33.999999999`, not `34.0`. Documentation and examples must show rounding at the point of use; `values` must not round on the caller's behalf.

Validation happens during `solve`, after Spark has materialised the lightweight metadata needed for compilation. Failures are actionable `LpModelException`s that name the offending variable, constraint, or key, including:

- missing, null, or duplicate variable keys;
- non-finite coefficients or RHS values; a `NaN` or `+inf` lower bound (`Double.NegativeInfinity` is legal and means a free variable); a non-finite upper bound (unbounded is expressed as `None`);
- more than one objective (via `+=`), or no objective;
- variable-set terms joined with the wrong domain/key, an unresolvable coefficient column, or duplicate keys in a `weightedBy` source;
- missing or duplicate RHS rows for a bulk constraint group, or a zero-term group row with a non-zero RHS;
- `lowerBound > upperBound`;
- a finite `upperBound` combined with `lowerBound = -inf` (rejected in v1: the free-variable split has no single shifted variable to bound; support for `x <= u` alone can be added later as `u - x >= 0`);
- an `Integer` variable without finite bounds, or integral bounds enclosing no integer;
- an empty model (no constraints), which `Initialize.init` already rejects;
- duplicate coefficient rows: merged when the RHS also matches, an `LpModelException` naming both constraints when it does not (see "Open decisions — resolved"); and
- more than `maxLocalConstraints` expanded rows. General rank deficiency (beyond exact duplicates) is *not* validated up front: it surfaces when Cholesky fails and is reported as `LpNumericalException` naming the full-row-rank precondition.

Infeasibility and unboundedness are detected through Farkas certificates, never through heuristics. At each iteration the solver tests the current iterate against the equality-form problem (min `c^T x` s.t. `A x = b`, `x >= 0`):

- **Primal infeasibility** — a ray `y = λ / (b^T λ)` with `b^T y = 1` and `max(0, max_i (A^T y)_i) / (b^T λ) <= ε_inf` proves that no feasible `x` exists.
- **Dual infeasibility** — a ray `z = x / |c^T x|` with `z >= 0`, `c^T z = -1`, and `‖A z‖_∞ / |c^T x| <= ε_inf` proves that the dual is infeasible, i.e. the primal is unbounded *if* it is feasible at all.

`ε_inf` is `SolveConfig.infeasibilityTolerance` (default `1e-8`); setting it to a negative value disables detection. The DSL maps the solver's terminations to statuses as follows: a primal certificate is always `Infeasible`; a dual certificate is `Unbounded` when the final iterate is primal-feasible within `SolveConfig.tolerance` (a feasible point plus an unbounded ray is conclusive) and `InfeasibleOrUnbounded` otherwise, because a dual certificate alone genuinely cannot distinguish the two cases. `objectiveValue` is `NaN` at `Infeasible` and `InfeasibleOrUnbounded`, and the signed infinity matching the objective sense at `Unbounded` (`+inf` for `Maximize`, `-inf` for `Minimize`). The same certificate test runs on the last successful iterate before an `LpNumericalException` would be thrown — infeasible instances often degenerate the Cholesky step, and a certificate is a better answer than an exception — but when no certificate exists the exception is preserved unchanged: reclassifying a numerical failure without proof would be a false claim. A LIPSOL-style divergence backstop additionally stops runs whose residuals grow uncontrollably, reporting plain `IterationLimit` since divergence is a heuristic, not a certificate. Certificate rays are kept internal to the solver summary in v1; only statuses, residuals, and diagnostics are public. The specific pair `x === 1` and `x === 2` never reaches the solver at all — the two rows have identical coefficients, so exact-duplicate row hashing (see "Open decisions — resolved") catches them and fails with an `LpModelException` naming both constraints as inconsistent duplicates.

## Integer and Binary variables

`Integer` and `Binary` are ordinary variable categories. `model.solve()` honours their domains automatically; callers never select a solver or opt into a separate mode. Purely continuous models keep the unchanged single-solve path.

Domains: a `Binary` variable ranges over `{0, 1}` intersected with its declared bounds (so `lowerBound = 1` pins it to `1`, and bounds excluding both `0` and `1` are rejected as holding no integral value). An `Integer` variable requires a finite `lowerBound` and an explicit finite `upperBound`; the declared range is tightened to the enclosed integral range (`ceil`/`floor`, absorbing floating-point fuzz within the solver tolerance), and an empty integral range is rejected at compile time.

The compiler builds the relaxation once and keeps the discrete search internal. Candidate solves reuse the shared distributed cost vector and constraint matrix and differ only in the driver-local RHS. Per integral column the driver holds bounds, cost, and constraint-row coefficients, so the practical number of integral columns remains much smaller than the distributed LP dimension.

Statuses stay truthful across the discrete search: a candidate is discarded as infeasible only on a Farkas certificate; `Optimal` is claimed only when every candidate is resolved and no better integral solution remains; `Unbounded` requires a dual-infeasibility certificate together with a primal-feasible integral iterate; `Infeasible` means every leaf was certificate-pruned (`InfeasibleOrUnbounded` when some candidate held only a dual certificate). Anything unresolved — the internal search limit, a candidate ending at `IterationLimit` with nothing left to branch on, or a numerical failure in a non-root candidate — degrades the result to `IterationLimit`, reporting the best integer-feasible incumbent found so far (with `NaN` objective when there is none). A numerical failure at the root propagates as `LpNumericalException`, matching the continuous path. Reported integer and binary values are exact integers, and `LpSolution.values` preserves the original domain join in the caller's units.

## Compilation contract

Compilation is deterministic *for fixed source data*: variable sets are ordered by `(set creation order, encoded key)`, generated rows by `(constraint creation order, encoded group key)`, and every generated slack/split variable receives an internal name derived from its constraint or source variable (`__slack[<constraint>]`, `<name>__pos`/`<name>__neg`). Ordering by encoded key requires a sort, not partition order — `Dataset.distinct` alone is not deterministic across runs. The compiler records the key-to-index mapping so it can reconstruct the user-facing values after `LP.solve` returns.

`solve` does **not** snapshot lazy sources. Each call evaluates the domain and coefficient DataFrames as Spark sees them at that moment; if a source table changed between two calls, the two solves are over different models, and a source that changes *during* one solve (e.g. an overwritten path) can fail or corrupt the run, exactly as it can for any multi-action Spark job. Two guarantees are made instead: (1) within one `solve` call, every domain and coefficient source is read once into cached, compiled structures before the solver starts, so a single solve is internally consistent; and (2) two solves over identical source contents produce the same matrix, the same variable ordering, and the same solution. Callers who need a durable snapshot should persist or checkpoint their sources — the DSL will not do it implicitly, because silently caching arbitrarily large user data is a worse default than documented re-evaluation.

| User feature | Equality-form transformation |
| --- | --- |
| `a x <= b` | `a x + s = b`, `s >= 0` |
| `a x >= b` | `a x - s = b`, `s >= 0` |
| `l <= x`, `l != 0` | substitute `x = l + y`, `y >= 0`; fold `a·l` into each constraint RHS and add `c·l` to the objective constant |
| `x <= u` (with finite lower bound `l < u`) | substitute as above; add row `y + s = u - l` |
| `l == u` (fixed variable) | presolved: `x` is fixed at `l`, contributes `a·l` to each RHS and `c·l` to the objective constant, and is never emitted to the solver; `values` reports `l` exactly |
| free `x` (lowerBound `-inf`, no upper bound) | substitute `x = x_plus - x_minus`, both non-negative |
| free `x` with finite upper bound | rejected in v1 (see validation list) |
| `Maximize cᵀx` | solve `Minimize -cᵀx`; restore objective sign and constants |

The objective constant deserves emphasis: every bound substitution shifts the objective by `c·l`, and explicit constants written in the objective expression survive normalisation. The compiler accumulates them all and `objectiveValue` adds them back — a model like `minimize x` with `x >= 5` and no other constraint on `x` must report 5, not 0. The fixed-variable presolve exists for the same reason `y + s = 0` must not be emitted: a row forcing two non-negative variables to zero puts the iterate on the boundary, which an interior-point method tolerates poorly, and it wastes a driver-local row on something known at compile time.

Two consequences deserve emphasis:

- **Each finite upper bound adds one constraint row.** The solver keeps constraint rows on the driver, so bounding a large variable set multiplies the local constraint count. The compiler counts bound rows against `maxLocalConstraints` like any other rows and says so in the error message.
- **Free-variable splitting doubles those variables and degrades interior-point conditioning** (the split is never strictly interior in both parts). It is supported because PuLP users expect it, but the documentation should steer users toward natural `x >= 0` formulations.

Before calling `LP.solve`, the compiler produces the existing `DVector` objective, transposed `DMatrix` coefficients, and local `DenseVector` RHS, honouring the solver's partitioning contract: partition `i` of the objective `DVector` must contain exactly as many elements as partition `i` of the `DMatrix` has rows. The compiler owns this repartitioning; users never see it.

### Scale limits are constraint-side, not variable-side

This DSL makes model *construction* distributed and readable; it does not make the solver's constraint-side state distributed. For `m` equality-form rows (user constraints + slack-generating inequalities + bound rows), the driver's per-solve cost is larger than one dense triangle: initialization holds the packed Gramian (`m²/2` doubles), a working copy for inversion, and a *full* `m × m` inverse at once (`Initialize.init`), and every iteration clones the packed Gramian for the factorization — roughly `2m²` doubles live simultaneously, i.e. `16m²` bytes plus broadcasts and user state. Concretely: ~160 MB at `m = 10³·√10 ≈ 3200`, ~1.6 GB at `m = 10⁴`, ~160 GB at `m = 10⁵`, on top of the `O(m³)` Cholesky per iteration. Practical guidance the DSL must encode rather than hide:

- `m ≤ 5000` (the `maxLocalConstraints` default): ~400 MB of Gramian-related driver allocations — safe on typical multi-GB driver heaps;
- `m ≈ 10⁴`: ~1.6 GB for the Gramian structures alone; feasible, but only with a driver heap of several GB deliberately provisioned for it — hence above the default, requiring an explicit raise;
- `m ≈ 10⁵` and beyond: dense driver memory alone is in the hundreds of GB; effectively out of reach for this solver.

The *variable* count is the distributed dimension and can be large. The error message for exceeding `maxLocalConstraints` must state this asymmetry and the `16m²`-byte estimate, because "Spark solver" otherwise implies the wrong axis scales.

## Risks and mitigations

| Risk | Mitigation in this design |
| --- | --- |
| Users assume constraint count scales like Spark data | Conservative `maxLocalConstraints` default, explicit opt-out, asymmetry documented in the error message and README |
| `status` reported as optimal when the solver merely stopped | Solver-side `solveSummary` returning the termination kind; `LpStatus` has no members the solver cannot truthfully report |
| `expr == rhs` compiles as `Boolean` comparison | `===` for equality; `+=` accepts no `Boolean`, so misuse fails to compile |
| Rank-deficient rows (e.g. a constraint added twice) crash Cholesky with an opaque error | Compiler hashes normalised rows: consistent duplicates are merged, duplicates with conflicting RHS fail as inconsistent; a remaining Cholesky failure surfaces as `LpNumericalException` explaining the full-row-rank precondition |
| Solver aborts mid-run (non-PD Gramian, zero iterate) with a bare linear-algebra error | All numerical failures are wrapped in typed `LpNumericalException` naming the phase and completed iterations |
| `IterationLimit` iterate mistaken for a feasible answer | `residuals` (primal/dual/gap) always returned; docs state the iterate need not be feasible and slack may be negative |
| Lazy sources change between/during solves, breaking "same model" assumptions | Documented evaluate-on-solve semantics: one consistent read per solve, no implicit snapshot; users persist sources for durability |
| Implicit-operator ambiguity against Spark's own `Column` operators | Operators live on DSL types only; `Column * variables` is rejected in favour of `variables * column`; no enrichment of `Column`, no comparison enrichment of `Double` |
| Non-deterministic variable order between runs changes results | Sort by encoded key; internal IDs never depend on partition order |
| Two `variables` overloads with default args do not compile in Scala 2 | Typed variant renamed to `variablesOf` |
| Silent objective replacement when `+=` is called twice with an expression | `+=` throws on a second objective; `setObjective` is the explicit replacement path |
| Interior-point results are approximate (`33.999…`) | Documented; examples round at point of use; no hidden rounding in `values` |
| DSL churn breaks expert users | `LP.solve` remains public and unchanged; DSL is additive in a new package |

## Implementation sequence

1. Add `LP.solveSummary` (iterations, termination kind, final primal/dual/gap residuals) with the existing `solve` delegating to it, and wrap the numerical failure paths (`Initialize.init` inversion, per-iteration Cholesky, zero-iterate guard) in `LpNumericalException`; no behaviour change for successful current callers.
2. Add the model AST (`LpProblem`, variables, expressions, scalar constraints) and compiler for continuous, non-negative variables; cover the Whiskas example and exact equality compatibility.
3. Add `<=`/`>=` translation, lower/upper bounds (including fixed-variable presolve and the objective-constant accumulator), free-variable splitting, and result reconstruction. This completes issue #9 for continuous LPs.
4. Add DataFrame/Dataset variable domains, column-weighted terms, and typed `weightedBy`.
5. Add grouped term/RHS constraints (`lpSumBy`, `GroupedLpExpr`), diagnostics, deterministic ordering, and validation tests.
6. Keep `LP.solve` public as the expert/compatibility API; migrate examples and README only after the DSL tests prove parity.

Tests must cover each behaviour this document commits to, in particular: objective constants restored after bound shifts and with explicit expression constants (for both senses); `lowerBound == upperBound` presolved with no emitted row; rejection of a free variable with a finite upper bound; `IterationLimit` returning residuals for an iterate that is not primal-feasible; `LpNumericalException` from a forced non-PD system; certificate-backed `Infeasible`, `Unbounded` (with signed-infinity objective), and `InfeasibleOrUnbounded` statuses, including an infeasible instance that previously threw `LpNumericalException`; inconsistent duplicate rows (`x === 1`, `x === 2`) failing as duplicates rather than reaching the solver; zero-term groups (legal with zero RHS, rejected with non-zero RHS); duplicate `weightedBy` keys rejected; encoded-key determinism across runs; evaluate-on-solve semantics when a source changes between solves; and an assertion (e.g. via a plan check or memory bound) that compilation never materialises a dense `n × m` structure or a DataFrame pivot.

## Open decisions — resolved

**Should `maxLocalConstraints` default to a fixed value or be derived from driver memory?**
A fixed, conservative `5000`. Driver-memory heuristics are fragile (the same heap must also hold the Gramian structures, broadcasts, and user state) and make failures dependent on deployment configuration, which is worse than a predictable limit. The `O(m²)` memory and `O(m³)` factorisation costs are intrinsic, so the ceiling should express algorithmic reality, not available RAM — and the reality, counting the packed Gramian, its inversion copy, the full inverse in `Initialize.init`, and the per-iteration clone, is ~`16m²` bytes of simultaneous driver allocations (~400 MB at the default, ~1.6 GB at `m = 10⁴`). Users who accept the cost raise the limit explicitly in `SolveConfig`; the error message states the estimate so raising it is an informed act.

**What exactly is the encoded key, and what key types are allowed?**
The encoded key is a canonical byte string: each key part is rendered in a type-tagged, locale-independent form (UTF-8 for strings, IEEE-754 bit pattern for doubles rendered canonically, decimal for integral types, `true`/`false` for booleans), parts joined in declaration order with a `0x1F` separator; multi-part keys come from `struct` columns or tuple/case-class keys. This encoding — not the display string — is the identity used for sorting, duplicate detection, `weightedBy` joins, and `lpSumBy` group matching. Typed keys are therefore not restricted to `String`: any type with an `LpKeyEncoder` (provided for `String`, numeric primitives, `Boolean`, and products of these) works. Null key parts are rejected by validation.

**What is the display-name format and its escaping rules?**
Display names are `<setName>[<part1>,<part2>,…]` with key parts rendered by Spark's own literal formatting; `,`, `[`, `]`, and `\` inside a part are escaped with `\`. Display names are *presentation only*: internal variable IDs are `(set creation index, row index after sorting by encoded key)` and never parse or depend on the display string, so an exotic key can never corrupt the model — at worst it renders oddly.

**Does v1 accept a finite upper bound on scalar variables only, or also on variable sets?**
Both, in the same release (step 3). The mechanics are identical — one extra row per bounded variable — and shipping scalar-only bounds would make `variables(..., upperBound = Some(u))` a documented-but-throwing parameter, which is exactly the kind of trap this DSL exists to remove. The real hazard, that bounding a large set multiplies driver-local rows, is handled by counting bound rows against `maxLocalConstraints` and naming the responsible variable set in the error.

**How much presolve/rank validation can run without collecting the coefficient matrix?**
V1 performs only cheap, distributed-friendly checks: finiteness, empty rows/columns, fixed-variable elimination, exact-duplicate constraint rows, and the `maxLocalConstraints` ceiling. Duplicate detection hashes each normalised row (coefficients scaled to a canonical leading value) *including* its RHS: rows identical in coefficients and RHS are merged into one with a note in diagnostics; rows identical in coefficients but differing in RHS are inconsistent and fail as an `LpModelException` naming both constraints. This catches the most common rank-deficiency and trivial-infeasibility source — a constraint added twice, or twice with different values — deterministically at compile time, which is why the `x === 1`/`x === 2` example must never be described as "returns `IterationLimit`". Scaled duplicates (`x === 1`, `2x === 2`) and general rank deficiency are deferred: the Cholesky step effectively detects them, and the compiler wraps that failure in `LpNumericalException` naming the full-row-rank precondition instead of surfacing a bare linear-algebra error. General infeasibility and unboundedness are not a presolve concern at all: the solver detects them at run time through Farkas certificates (see "Results and errors").
