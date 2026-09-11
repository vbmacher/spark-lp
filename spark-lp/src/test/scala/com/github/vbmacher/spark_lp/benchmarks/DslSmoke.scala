package com.github.vbmacher.spark_lp.dsl

import java.io.{File, PrintWriter}
import com.github.vbmacher.spark_lp.{SolveControl, SolvePhase}
import com.github.vbmacher.spark_lp.dsl.implicits._
import org.apache.spark.sql.SparkSession
import org.json4s.{DefaultFormats, Extraction}
import org.json4s.jackson.JsonMethods.{compact, render}

/** Bounded DSL compilation/end-to-end smoke; reported separately from core crossover timing. */
object DslSmoke {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "absolute-output.jsonl cholesky|cg")
    val backend = args(1) match {
      case "cholesky" => NewtonSolver.Cholesky
      case "cg" => NewtonSolver.ConjugateGradient
      case _ => throw new IllegalArgumentException("Unknown backend")
    }
    implicit val spark: SparkSession = SparkSession.builder().master("local[4]")
      .appName("bounded-dsl-smoke").config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "8").getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")
    implicit val formats: DefaultFormats.type = DefaultFormats
    val output = new PrintWriter(new File(args(0)))
    try { (0 to 5).foreach { repetition =>
      val model = LpProblem("bounded")
      val x = model.variable("x", upperBound = Some(2.0))
      val y = model.variable("y", upperBound = Some(2.0))
      model += x + y * 2.0
      model += (x + y === 3.0)
      val compiler = new LpCompiler(model, SolveConfig(newtonSolver = backend))
      val (m, n) = try {
        val compiled = compiler.compile()
        (compiled.numRows, compiled.numCols)
      } finally compiler.close()
      val start = System.nanoTime()
      var compilationSeconds = Option.empty[Double]
      val result = model.solve(SolveConfig(newtonSolver = backend, maxIterations = 100,
        control = SolveControl(onProgress = Some(event => {
          if (event.phase == SolvePhase.Initialization && compilationSeconds.isEmpty)
            compilationSeconds = Some((System.nanoTime() - start) / 1e9)
        }))))
      try {
        val endToEnd = (System.nanoTime() - start) / 1e9
        val xv = result.value(x)
        val yv = result.value(y)
        val valid = result.status == LpStatus.Optimal && math.abs(xv - 2.0) < 1e-7 && math.abs(yv - 1.0) < 1e-7 &&
          math.abs(result.objectiveValue - 4.0) < 1e-7
        require(valid, s"Invalid bounded DSL solution: $xv $yv")
        val row: Map[String, Any] = Map("backend" -> args(1), "repetition" -> repetition,
          "warmup" -> (repetition == 0), "status" -> "Success", "m" -> m, "n" -> n,
          "generated_bound_rows" -> 2, "generated_slack_columns" -> 2,
          "compilation_seconds" -> compilationSeconds, "end_to_end_seconds" -> endToEnd,
          "x" -> xv, "y" -> yv, "objective" -> result.objectiveValue)
        output.println(compact(render(Extraction.decompose(row)))); output.flush()
      } finally result.close()
      spark.sparkContext.getPersistentRDDs.values.foreach(_.unpersist(blocking = true))
    } } finally { output.close(); spark.stop() }
  }
}
