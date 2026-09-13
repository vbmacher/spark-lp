# Examples

All runnable examples live in this module, under `com.github.vbmacher.spark_lp.examples`.
They use local Spark to demonstrate the public API. Performance campaigns live in
the separate [benchmarks module](../benchmarks/README.md).

| Example | Demonstrates |
|---|---|
| [ExampleWhiskasDsl](src/main/scala/com/github/vbmacher/spark_lp/examples/ExampleWhiskasDsl.scala) | Model a minimum-cost ingredient blend with DataFrame variables and nutritional constraints; inspect values and constraint diagnostics. Start here. |
| [ExampleWhiskas](src/main/scala/com/github/vbmacher/spark_lp/examples/ExampleWhiskas.scala) | Solve the same blend through `LP.solve`, constructing equality constraints and slack variables explicitly. |
| [ExampleStandardForm](src/main/scala/com/github/vbmacher/spark_lp/examples/ExampleStandardForm.scala) | Build a small standard-form LP from costs, transposed matrix columns and an RHS vector. |
| [ExampleRandomLP](src/main/scala/com/github/vbmacher/spark_lp/examples/ExampleRandomLP.scala) | Generate distributed sparse random inputs and construct a feasible RHS before calling the core solver. |
| [ExampleMPS](src/main/scala/com/github/vbmacher/spark_lp/examples/ExampleMPS.scala) | Parse a supplied MPS file with JOptimizer, convert it to standard form and solve it. Parsing and conversion use driver memory. |

Run from the repository root with JDK 11 and sbt:

```sh
sbt 'examplesSpark_3_5/compile'
sbt 'examplesSpark_3_5/runMain com.github.vbmacher.spark_lp.examples.ExampleWhiskasDsl'
sbt 'examplesSpark_3_5/runMain com.github.vbmacher.spark_lp.examples.ExampleWhiskas'
sbt 'examplesSpark_3_5/runMain com.github.vbmacher.spark_lp.examples.ExampleStandardForm'
sbt 'examplesSpark_3_5/runMain com.github.vbmacher.spark_lp.examples.ExampleRandomLP'
sbt 'examplesSpark_3_5/runMain com.github.vbmacher.spark_lp.examples.ExampleMPS /absolute/path/problem.mps'
```

The module supplies Spark 3.5.3 and Scala 2.12.20 through the build. MPS input is a
user-supplied file; all other examples are self-contained. See the
[usage guide](../docs/usage.adoc) for API syntax, result ownership and solver limits.
