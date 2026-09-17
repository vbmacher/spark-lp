# Examples

All runnable examples live in this module, under `com.github.vbmacher.spark_lp.examples`.
They use local Spark to demonstrate the public API. Performance campaigns live in
the separate [benchmarks module](../benchmarks/README.md).

| Example | Demonstrates |
|---|---|
| [ExampleWhiskas](src/main/scala/com/github/vbmacher/spark_lp/examples/ExampleWhiskas.scala) | Blend six ingredients at minimum cost using weighted sums and nutritional constraints. Start here. |
| [ExampleTransportation](src/main/scala/com/github/vbmacher/spark_lp/examples/ExampleTransportation.scala) | Ship whole beer crates using integer variables, composite keys, and grouped supply/demand constraints. |
| [ExampleTwoStageProduction](src/main/scala/com/github/vbmacher/spark_lp/examples/ExampleTwoStageProduction.scala) | Buy steel before uncertainty resolves, then choose production per scenario to maximize expected profit. |
| [ExampleSetPartitioning](src/main/scala/com/github/vbmacher/spark_lp/examples/ExampleSetPartitioning.scala) | Choose wedding tables with binary variables; exploded guest memberships enforce exactly one seat per guest. |
| [ExampleSudoku](src/main/scala/com/github/vbmacher/spark_lp/examples/ExampleSudoku.scala) | Solve a 9-by-9 puzzle with binary digit choices and grouped cell, row, column, box, and clue constraints. |
| [ExampleMPS](src/main/scala/com/github/vbmacher/spark_lp/examples/ExampleMPS.scala) | Parse a supplied MPS file with JOptimizer, convert it to standard form and solve it. Parsing and conversion use driver memory. |

Run from the repository root with JDK 11 and sbt:

```sh
sbt 'examplesSpark_3_5/compile'
sbt 'examplesSpark_3_5/runMain com.github.vbmacher.spark_lp.examples.ExampleWhiskas'
sbt 'examplesSpark_3_5/runMain com.github.vbmacher.spark_lp.examples.ExampleTransportation'
sbt 'examplesSpark_3_5/runMain com.github.vbmacher.spark_lp.examples.ExampleTwoStageProduction'
sbt 'examplesSpark_3_5/runMain com.github.vbmacher.spark_lp.examples.ExampleSetPartitioning'
sbt 'examplesSpark_3_5/runMain com.github.vbmacher.spark_lp.examples.ExampleSudoku'
sbt 'examplesSpark_3_5/runMain com.github.vbmacher.spark_lp.examples.ExampleMPS /absolute/path/problem.mps'
```

The module supplies Spark 3.5.3 and Scala 2.12.20 through the build. MPS input is a
user-supplied file; all other examples are self-contained. See the
[usage guide](../docs/usage.adoc) for API syntax, result ownership and solver limits.

The five modeling examples implement the standard [PuLP case studies](https://coin-or.github.io/pulp/CaseStudies/index.html)
using only the DSL. Each source links to its original problem. `ExampleMPS` uses
the core solver API for importing external files. Synthetic generated workloads
belong in the benchmarks module.

| Case study | Expected result |
|---|---|
| [Whiskas](https://coin-or.github.io/pulp/CaseStudies/a_blending_problem.html) | 60 g beef and 40 g gel; cost 0.52 per 100-g can. |
| [Transportation](https://coin-or.github.io/pulp/CaseStudies/a_transportation_problem.html) | Minimum transport cost 8600; all five demands met within warehouse supplies. |
| [Two-stage production](https://coin-or.github.io/pulp/CaseStudies/a_two_stage_production_planning_problem.html) | Buy 27.25 units of steel; expected profit 863.25. The source model does not constrain steel purchases to its unused `capsteel = 27` parameter. |
| [Set partitioning](https://coin-or.github.io/pulp/CaseStudies/a_set_partitioning_problem.html) | Minimum total alphabetical span 12; 17 guests seated at at most five tables, with at most four guests each. Uses the minimization objective in PuLP's executable model. |
| [Sudoku](https://coin-or.github.io/pulp/CaseStudies/a_sudoku_problem.html) | Print a completed grid preserving all clues, with digits 1–9 in each row, column, and box. |

The discrete examples use the same `model.solve()` API. Wedding seating enumerates
3213 candidate tables; Sudoku has 729 binary choices. Their integer search can take
longer than the continuous examples. Sudoku explicitly selects the regularized CG
solver because its equality constraints are redundant. Each DSL example checks for
`Optimal` before presenting a solution and closes its result and Spark session.

Local validation status for the complete wedding model with default settings is
`IterationLimit`: the root LP relaxation converges, but integer optimality is
unproven. The example reports this status without presenting fractional values as a
seating plan. The reference optimum is 12: 17 guests at at most five tables require
at least 12 total span, achieved by `ABCD`, `EFG`, `IJKL`, `MNO`, `PQR`.
