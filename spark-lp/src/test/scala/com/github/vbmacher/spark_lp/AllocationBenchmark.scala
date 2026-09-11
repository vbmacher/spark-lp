package com.github.vbmacher.spark_lp

import java.io.{File, PrintWriter}
import scala.concurrent.duration._

import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.dsl.implicits._
import org.apache.spark.sql.functions.col
import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.apache.spark.sql.SparkSession

/** Synthetic daily allocation LP: overlapping conserved scopes, soft share targets and
  * zero-cost allocation factors. This is not a replay of production input.
  * Args: absolute output.csv, days, cholesky|cg, regularization (default 1e-8),
  * none|report|stagnation|candidate|time (default none), core|dsl (default core).
  */
object AllocationBenchmark {
  def main(args: Array[String]): Unit = {
    require(args.length >= 3, "Supply output.csv, days and cholesky|cg")
    val output = new File(args(0))
    require(output.isAbsolute, "Output CSV path must be absolute")
    val days = args(1).toInt
    require(days > 0, "Days must be positive")
    val backend = args(2) match {
      case "cholesky" => NewtonSolver.Cholesky
      case "cg" => NewtonSolver.ConjugateGradient
      case other => throw new IllegalArgumentException(s"Unknown backend: $other")
    }
    val mode = args.lift(4).getOrElse("none")
    val regularization = args.lift(3).map(_.toDouble).getOrElse(1e-8)
    implicit val spark: SparkSession = SparkSession.builder().master("local[4]")
      .appName("spark-lp allocation benchmark").config("spark.ui.enabled", "false")
      .config("spark.ui.retainedJobs", "100000").getOrCreate()
    val sc = spark.sparkContext
    sc.setLogLevel("WARN")
    val scopes = 12
    val targets = 24
    val cells = 30
    val rowsPerDay = scopes + targets
    val colsPerDay = cells + 2 * targets
    val m = days * rowsPerDay
    val n = days * colsPerDay
    val floor = 1e-5
    // Each non-total scope has a unique cell, plus overlapping membership in the tail.
    // This guarantees independent conservation rows without giving targets independent
    // decision variables: targets compete for the same conserved allocation factors.
    def inScope(scope: Int, cell: Int): Boolean =
      scope == 0 || cell == scope - 1 || (cell >= scopes && (cell + scope) % 3 == 0)
    def inCategory(target: Int, cell: Int): Boolean =
      inScope(target % scopes, cell) && (cell * 7 + target * 3) % 11 < 5
    def mass(day: Int, cell: Int): Double =
      math.pow(10.0, ((day * 13 + cell * 7) % 31) / 10.0)
    val totals = Array.tabulate(days, scopes) { (day, scope) =>
      (0 until cells).filter(inScope(scope, _)).map(mass(day, _)).sum
    }
    def share(target: Int): Double = 0.05 + 0.05 * (target % 12)
    val columns = sc.parallelize(0 until n, 4).map { index =>
      val day = index / colsPerDay
      val col = index % colsPerDay
      val entries = if (col < cells) {
        (0 until rowsPerDay).flatMap { row =>
          val scope = if (row < scopes) row else (row - scopes) % scopes
          val member = if (row < scopes) inScope(scope, col) else inCategory(row - scopes, col)
          if (member) Some((day * rowsPerDay + row) -> (mass(day, col) / totals(day)(scope)))
          else None
        }
      } else {
        val error = col - cells
        Seq((day * rowsPerDay + scopes + error / 2) -> (if (error % 2 == 0) -1.0 else 1.0))
      }
      (Vectors.sparse(m, entries), if (col < cells) 0.0 else 1.0 / (targets * days))
    }.cache()
    val at = columns.map(_._1).cache()
    val cost = columns.mapPartitions(it => Iterator.single(new DenseVector(it.map(_._2).toArray))).cache()
    val rhs = Array.tabulate(m) { index =>
      val day = index / rowsPerDay
      val row = index % rowsPerDay
      if (row < scopes) 1.0 - floor else {
        val target = row - scopes
        val current = (0 until cells).filter(inCategory(target, _)).map(mass(day, _)).sum /
          totals(day)(target % scopes)
        share(target) - floor * current
      }
    }
    val writer = new PrintWriter(output)
    writer.println("days,m,n,backend,regularization,status,primal,dual,gap,objective,wall_seconds,outer_iterations,inner_iterations,preconditioner_rank,spark_jobs,error,control,stop_reason,candidate_available,candidate_feasible,candidate_iteration,progress_events")
    writer.flush()
    try {
      at.count()
      cost.count()
      sc.setJobGroup("allocation", "allocation")
      val start = System.nanoTime()
      var previous = start
      var events = 0
      var stop = false
      val report: SolveProgress => Unit = e => {
        events += 1
        if (mode == "candidate" && e.feasible.contains(true)) stop = true
      }
      val control = mode match {
        case "none" => SolveControl()
        case "report" => SolveControl(onProgress = Some(report))
        case "stagnation" => SolveControl(onProgress = Some(report), stagnation = Some(StagnationConfig()))
        case "candidate" => SolveControl(onProgress = Some(report), shouldStop = Some(() => stop))
        case "time" => SolveControl(onProgress = Some(report), timeLimit = Some(5.seconds))
        case other => throw new IllegalArgumentException(s"Unknown control mode: $other")
      }
      try {
        if (args.lift(5).contains("dsl")) {
          import spark.implicits._
          val domain = columns.zipWithIndex().map { case ((_, c), id) => (id, c) }.toDF("id", "cost")
          val coefficients = columns.zipWithIndex().flatMap { case ((vector, _), id) =>
            val sparse = vector.toSparse
            sparse.indices.zip(sparse.values).map { case (row, c) => (id, row, c) }
          }.toDF("id", "constraint", "coefficient")
          val rhsFrame = rhs.zipWithIndex.map { case (value, row) => (row, value) }.toSeq.toDF("constraint", "rhs")
          val model = LpProblem("allocation-controls", Minimize)
          val amount = model.variables("amount", domain, col("id"))
          model += lpSum(amount * col("cost"))
          model += (lpSumBy(amount.terms(coefficients, Seq(col("constraint")), col("coefficient")),
            Seq("constraint")) === rhsFrame).named("allocation")
          val result = model.solve(SolveConfig(newtonSolver = backend,
            matrixFree = MatrixFreeConfig(regularization, regularization), control = control))
          try {
            writer.println(Seq(days, m, n, backend, regularization, result.status,
              result.residuals.primal, result.residuals.dual, result.residuals.gap, result.objectiveValue,
              (System.nanoTime() - start) / 1e9, result.iterations, "NA", "NA",
              sc.statusTracker.getJobIdsForGroup("allocation").length, "", mode, result.stopReason,
              result.candidate.available, result.candidate.feasible, result.candidate.iteration, events).mkString(","))
          } finally result.close()
        } else {
        val result = LP.solveSummary(cost, at, new DenseVector(rhs), solver = backend,
          matrixFree = MatrixFreeConfig(regularization, regularization), control = control,
          stopAfterIteration = Some(iteration => {
            val now = System.nanoTime()
            println(s"ALLOCATION iteration=$iteration seconds=${(now - previous) / 1e9}")
            previous = now
            false
          }))
        try {
          writer.println(Seq(days, m, n, backend, regularization, result.termination,
            result.primalResidual, result.dualResidual, result.dualityGap, result.objectiveValue,
            (System.nanoTime() - start) / 1e9, result.iterations, result.innerIterations,
            result.preconditionerRank, sc.statusTracker.getJobIdsForGroup("allocation").length, "", mode, result.stopReason,
            result.candidate.available, result.candidate.feasible, result.candidate.iteration, events).mkString(","))
        } finally result.x.unpersist(blocking = true)
        }
      } catch {
        case e: LpNumericalException =>
          writer.println(Seq(days, m, n, backend, regularization, "NumericalFailure",
            "NaN", "NaN", "NaN", "NaN", (System.nanoTime() - start) / 1e9,
            e.completedIterations, "NA", "NA", sc.statusTracker.getJobIdsForGroup("allocation").length,
            e.getMessage.replace(',', ';').replace('\n', ' '), mode, "", false, false, "None", events).mkString(","))
      }
    } finally {
      writer.close()
      sc.clearJobGroup()
      sc.getPersistentRDDs.values.foreach(_.unpersist(blocking = true))
      spark.stop()
    }
  }
}
