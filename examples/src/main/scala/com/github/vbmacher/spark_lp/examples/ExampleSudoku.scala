package com.github.vbmacher.spark_lp.examples

import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.dsl.implicits._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{lit, struct}

/** Completes the following 9-by-9 Sudoku, where zero denotes an empty cell:
  * {{{
  * 5 3 0 | 0 7 0 | 0 0 0
  * 6 0 0 | 1 9 5 | 0 0 0
  * 0 9 8 | 0 0 0 | 0 6 0
  * ------+-------+------
  * 8 0 0 | 0 6 0 | 0 0 3
  * 4 0 0 | 8 0 3 | 0 0 1
  * 7 0 0 | 0 2 0 | 0 0 6
  * ------+-------+------
  * 0 6 0 | 0 0 0 | 2 8 0
  * 0 0 0 | 4 1 9 | 0 0 5
  * 0 0 0 | 0 8 0 | 0 7 0
  * }}}
  *
  * Fill every cell with a digit from 1 through 9 while preserving all 29 given clues.
  * Every row, every column, and each of the nine nonoverlapping 3-by-3 boxes must contain
  * every digit exactly once.
  *
  * The model has 729 binary variables, one for each (row, column, digit) combination;
  * a value of one places that digit in that cell. Four families of equality constraints
  * require exactly one digit per cell and exactly one occurrence of each digit per row,
  * column, and box. Each clue fixes its corresponding binary variable to one. The
  * objective is identically zero: any valid completion is an optimal solution.
  *
  * The example uses grouped DSL constraints and the regularized CG backend because the
  * equality constraints contain redundant rows. It prints the completed grid only after
  * an optimal status is returned; it does not enumerate completions or test uniqueness.
  *
  * Source: [[https://coin-or.github.io/pulp/CaseStudies/a_sudoku_problem.html PuLP Sudoku case study]].
  */
object ExampleSudoku extends App {
  implicit val spark: SparkSession = SparkSession.builder()
    .appName("ExampleSudoku")
    .master("local[2]")
    .config("spark.sql.shuffle.partitions", "2")
    .getOrCreate()
  import spark.implicits._

  try {
    spark.sparkContext.setLogLevel("ERROR")
    // Zero denotes an empty cell in the puzzle used by the PuLP case study.
    val puzzle = Vector(
      "530070000", "600195000", "098000060",
      "800060003", "400803001", "700020006",
      "060000280", "000419005", "000080070")
    val candidates = (for {
      row <- 1 to 9
      column <- 1 to 9
      digit <- 1 to 9
    } yield (row, column, digit, (row - 1) / 3 * 3 + (column - 1) / 3))
      .toDF("row", "column", "digit", "box")
    val clues = (for {
      (line, row) <- puzzle.zipWithIndex
      (digit, column) <- line.zipWithIndex
      if digit != '0'
    } yield (row + 1, column + 1, digit.asDigit)).toDF("row", "column", "digit")

    val model = LpProblem("Sudoku")
    val choice = model.variables("choice", candidates, key = struct($"row", $"column", $"digit"),
      category = Binary)
    model += choice.sum * 0.0
    model += (choice.sumBy("row", "column")() === 1.0).named("cell")
    model += (choice.sumBy("row", "digit")() === 1.0).named("row_digit")
    model += (choice.sumBy("column", "digit")() === 1.0).named("column_digit")
    model += (choice.sumBy("box", "digit")() === 1.0).named("box_digit")
    model += (lpSumBy(choice.terms(clues, Seq($"row", $"column"), lit(1.0)),
      Seq("row", "column")) === 1.0).named("clue")

    // Sudoku's equality rows are redundant; use the regularized matrix-free solver.
    val solution = model.solve(SolveConfig(newtonSolver = NewtonSolver.ConjugateGradient))
    try {
      require(solution.status == LpStatus.Optimal, s"Unexpected status: ${solution.status}")
      val cells = solution.values(choice).filter($"lp_value" > 0.5)
        .select("row", "column", "digit").orderBy("row", "column")
      // Only the final 81 cells reach the driver for display.
      val grid = cells.collect().grouped(9)
      println("Completed Sudoku:")
      grid.foreach(row => println(row.map(_.getInt(2)).mkString(" ")))
    } finally solution.close()
  } finally spark.stop()
}
