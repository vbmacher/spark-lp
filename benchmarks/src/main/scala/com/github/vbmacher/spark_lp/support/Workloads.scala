package com.github.vbmacher.spark_lp.support

import com.github.vbmacher.spark_lp.{Benchmark, LP}
import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.dsl.implicits._
import org.apache.spark.mllib.linalg.{DenseVector, Vector => SparkVector}
import org.apache.spark.sql.SparkSession
import org.apache.spark.wrappers.NativeNetlib
import org.netlib.util.intW

/** Existing mathematical fixtures; scheduling, repetitions and output belong to the runner. */
trait Workload extends AutoCloseable {
  def measure(): Map[String, Double]
  override def close(): Unit = ()
}

object Workloads {
  def open(s: Scenario, diagnostic: Map[String, Any] => Unit)(implicit spark: SparkSession): Workload =
    if (s.kind == "lp") linear(s, diagnostic)
    else new Workload {
      override def measure(): Map[String, Double] = s.kind match {
        case "presolve" => presolve(s, diagnostic)
        case "start" => start(s, diagnostic)
        case "mip" => mip(s, diagnostic)
        case "qp" => quadratic(s)
        case "factorization" => factorization(s)
      }
    }

  private def linear(s: Scenario, diagnostic: Map[String, Any] => Unit)(implicit spark: SparkSession): Workload = {
    val spec = s.spec.getOrElse(throw new IllegalArgumentException("LP requires fixture metadata"))
    val generated = new Stopwatch
    val data = DataGenerator.generate(spec, s.partitions)
    try diagnostic(Map("fixture_hash" -> data.hash, "nnz" -> data.nnz,
      "known_objective" -> data.objective, "generation_seconds" -> generated.seconds))
    catch { case error: Throwable => data.close(); throw error }
    val sc = spark.sparkContext
    val fixtureCaches = sc.getPersistentRDDs.keySet
    new Workload {
      override def measure(): Map[String, Double] = {
        try {
          val prepared = new Stopwatch
          val input = data.columns(s.partitions).cache()
          val rows = input.map(v => v._3: SparkVector).cache()
          val costs = input.mapPartitions(it => Iterator(new DenseVector(it.map(_._2).toArray))).cache()
          val rhs = data.b
          rows.count(); costs.count()
          val preparation = prepared.seconds
          val timing = new SolveMeasurements
          var residuals = Map.empty[String, Double]
          val result = Benchmark.named(s.backend).solve(costs, rows, rhs, spec.tolerance, timing.progress, (x, y, slack) => {
            timing.validate { residuals = data.residuals(input.map(_._1), x, y, slack) }
          })
          val seconds = timing.solveSeconds
          try {
            diagnostic(timing.snapshot(seconds) ++ Map("residuals" -> residuals, "preparation_seconds" -> preparation,
              "termination" -> result.termination.toString, "maximum_rank" -> result.preconditionerRank))
            require(result.termination == LP.Termination.Converged, s"Solver terminated: ${result.termination}")
            require(DataGenerator.passes(residuals, spec.tolerance), s"AccuracyFailure: $residuals")
            Map("solve-seconds" -> seconds)
          } finally result.x.unpersist(blocking = true)
        } finally sc.getPersistentRDDs.values.filterNot(rdd => fixtureCaches(rdd.id)).foreach(_.unpersist(blocking = true))
      }
      override def close(): Unit = data.close()
    }
  }

  private def checked(model: LpProblem, variables: Seq[LpVariable], expected: Double,
                      result: LpSolution, seconds: Double): Map[String, Double] = {
    val report = model.validateCandidate(model.candidateValues(variables.map(v => v -> result.value(v))),
      CandidateValidationConfig(tolerance = 1e-6))
    val error = math.abs(result.objectiveValue - expected)
    try require(report.feasible && result.status == LpStatus.Optimal && BenchmarkResults.finite(error) && error <= 1e-6,
      s"AccuracyFailure: ${result.status}, objective error $error")
    finally report.close()
    Map("solve-seconds" -> seconds)
  }

  private def presolve(s: Scenario, diagnostic: Map[String, Any] => Unit)(implicit spark: SparkSession): Map[String, Double] = {
    val n = 12
    val model = LpProblem(s.caseId)
    val xs = Vector.tabulate(n)(i => model.variable(s"x$i", upperBound = Some(if (s.caseId == "fixed_rows") 10.0 else 1.0)))
    var variables = xs
    val expected = s.caseId match {
      case "fixed_rows" =>
        xs.zipWithIndex.foreach { case (x, i) => model += (x === (i % 3).toDouble).named(s"fix$i") }
        model += lpDot(Vector.fill(n)(1.0), xs) + 7.0
        7.0 + (0 until n).map(_ % 3).sum
      case "singleton_columns" =>
        val ys = Vector.tabulate(n)(i => model.variable(s"free$i", Double.NegativeInfinity))
        variables = xs ++ ys
        xs.zip(ys).zipWithIndex.foreach { case ((x, y), i) => model += (y + 2.0 * x === (i + 3.0)).named(s"definition$i") }
        model += lpDot(Vector.fill(n)(1.0), xs) + 7.0
        7.0
      case "irreducible" =>
        model += lpDot((1 to n).map(_.toDouble), xs) + 7.0
        model += (lpDot(Vector.fill(n)(1.0), xs) === (n / 2.0)).named("total")
        7.0 + (1 to n / 2).sum
    }
    val config = SolveConfig(presolve = PresolveConfig(enabled = s.mode != "off", effort = PresolveEffort.Full))
    val clock = new Stopwatch
    val result = model.solve(config)
    val seconds = clock.seconds
    try {
      diagnostic(Map("presolve" -> result.presolve))
      checked(model, variables, expected, result, seconds)
    } finally result.close()
  }

  private def knapsack(kind: String)(implicit spark: SparkSession): (LpProblem, Vector[LpVariable], Vector[Double], Double) = {
    val four = kind == "knapsack4"
    val weights = if (four) Vector(2.0, 3.0, 4.0, 5.0) else Vector(2.0, 3.0, 4.0, 5.0, 7.0, 9.0)
    val profits = if (four) Vector(3.0, 4.0, 5.0, 8.0) else Vector(4.0, 5.0, 7.0, 8.0, 11.0, 13.0)
    val capacity = if (four) 8.0 else 12.0
    val model = LpProblem(kind, Maximize)
    val xs = weights.indices.map(i => model.variable(s"item$i", category = Binary)).toVector
    model += lpDot(profits, xs) + 7.0
    model += (lpDot(weights, xs) <= capacity)
    val optimum = (0 until (1 << xs.size)).map { bits =>
      val values = xs.indices.map(i => if ((bits & (1 << i)) != 0) 1.0 else 0.0).toVector
      (weights.zip(values).map { case (a, b) => a * b }.sum,
        profits.zip(values).map { case (a, b) => a * b }.sum, values)
    }.filter(_._1 <= capacity).maxBy(_._2)
    (model, xs, optimum._3, optimum._2 + 7.0)
  }

  private def mip(s: Scenario, diagnostic: Map[String, Any] => Unit)(implicit spark: SparkSession): Map[String, Double] = {
    val (model, xs, _, expected) = knapsack(s.caseId)
    val modes = s.mode.split("\\+").toSet
    val policy = MipSearchConfig(cuts = MipCutsConfig(enabled = modes("cuts"), maxRounds = 1, maxCutsPerNode = 4, maxCuts = 8),
      strongBranching = StrongBranchingConfig(enabled = modes("strong"), maxCandidates = 2, maxIterations = 8, maxProbes = 8),
      parallelNodes = if (modes("parallel")) 2 else 1)
    val control = MipControl(onProgress = p => diagnostic(Map("mip_progress" -> p)))
    val clock = new Stopwatch
    val result = model.solve(SolveConfig(mip = MipConfig(maxNodes = 256, search = policy, control = control)))
    val seconds = clock.seconds
    try {
      diagnostic(Map("mip" -> result.mip))
      checked(model, xs, expected, result, seconds)
    } finally result.close()
  }

  private def start(s: Scenario, diagnostic: Map[String, Any] => Unit)(implicit spark: SparkSession): Map[String, Double] = {
    val (model, assignments, expected, config) = if (s.caseId == "lp_coordinates") {
      val model = LpProblem(s.caseId, Maximize)
      val xs = Vector.tabulate(12)(i => model.variable(s"x$i", -2.0, Some(3.0)))
      val ys = Vector.tabulate(12)(i => model.variable(s"upper$i", Double.NegativeInfinity, Some(2.0)))
      val zs = Vector.tabulate(12)(i => model.variable(s"free$i", Double.NegativeInfinity))
      xs.zip(ys).foreach { case (x, y) => model += (x + y <= 3.0) }
      zs.foreach(z => model += (z === -1.0))
      model += lpDot(Vector.fill(12)(1.0), xs) + lpDot(Vector.fill(12)(1.0), ys) + 7.0
      (model, xs.map(_ -> 1.0) ++ ys.map(_ -> 2.0) ++ zs.map(_ -> -1.0), 43.0,
        SolveConfig(newtonSolver = NewtonSolver.ConjugateGradient))
    } else {
      val (model, xs, values, expected) = knapsack("knapsack6")
      (model, xs.zip(values), expected, SolveConfig())
    }
    val clock = new Stopwatch
    val initial = if (s.mode == "started") Some(model.start(assignments)) else None
    val snapshot = clock.seconds
    try {
      val result = model.solve(config.copy(start = initial))
      val seconds = clock.seconds
      try {
        diagnostic(Map("snapshot_seconds" -> snapshot, "start" -> result.start))
        checked(model, assignments.map(_._1), expected, result, seconds)
      } finally result.close()
    } finally initial.foreach(_.close())
  }

  private def quadratic(s: Scenario)(implicit spark: SparkSession): Map[String, Double] = {
    val n = s.caseId.toInt
    val model = LpProblem("comparison")
    val targets = (0 until n).map(i => 1.0 + i.toDouble / n)
    val variables = targets.indices.map(i => model.variable(s"x$i"))
    val terms = variables.zip(targets).map { case (v, target) =>
      if (s.mode == "factor") QpObjective.squared(v - target) else QpObjective.squaredDeviation(v, target)
    }
    model += terms.reduce(_ + _)
    model += (variables.map(v => 1.0 * v).reduce(_ + _) === targets.sum - n * 0.25)
    val clock = new Stopwatch
    val result = model.solve(SolveConfig(newtonSolver = Benchmark.named(s.backend).algorithm, maxIterations = 100))
    val seconds = clock.seconds
    try {
      val errors = Map("primal-max" -> result.residuals.primal, "dual-max" -> result.residuals.dual,
        "gap-max" -> result.residuals.gap,
        "solution-error-max" -> variables.zip(targets).map { case (v, t) => math.abs(result.value(v) - (t - 0.25)) }.max)
      require(errors.values.forall(v => BenchmarkResults.finite(v) && v >= 0 && v < 1e-8), s"AccuracyFailure: $errors")
      checked(model, variables, n * 0.0625, result, seconds)
    } finally result.close()
  }

  /** Isolated JVM kernel benchmark, not a Spark scalability measurement. */
  private def factorization(s: Scenario): Map[String, Double] = {
    val n = s.caseId.toInt
    def off(row: Int, column: Int): Double = (Math.floorMod(row * 131 + column * 17, 101) - 50) * 1e-7
    val packed = (0 until n).flatMap(c => (0 to c).map(r => if (r == c) 1.0 else off(r, c))).toArray
    val rhs = Array.tabulate(n)(r => 1.0 + (0 until n).filter(_ != r).map(c => off(math.min(r, c), math.max(r, c))).sum)
    val factor = if (s.mode == "packed") packed else {
      val full = new Array[Double](n * n)
      var offset = 0
      (0 until n).foreach { c => System.arraycopy(packed, offset, full, c * n, c + 1); offset += c + 1 }
      full
    }
    val lapack = NativeNetlib.lapack
    val info = new intW(0)
    val clock = new Stopwatch
    if (s.mode == "full") lapack.dpotrf("U", n, factor, n, info) else lapack.dpptrf("U", n, factor, info)
    val seconds = clock.seconds
    require(info.`val` == 0, s"Factorization failed: ${info.`val`}")
    if (s.mode == "full") lapack.dpotrs("U", n, 1, factor, n, rhs, n, info) else lapack.dpptrs("U", n, 1, factor, rhs, n, info)
    val error = rhs.map(v => math.abs(v - 1.0)).max
    require(info.`val` == 0 && BenchmarkResults.finite(error) && error < 1e-8, "Factorization accuracy failure")
    Map("solve-seconds" -> seconds)
  }
}
