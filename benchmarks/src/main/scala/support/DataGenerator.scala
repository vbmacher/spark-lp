package support

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.apache.spark.mllib.linalg.{DenseVector, SparseVector, Vectors}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Column, DataFrame, Row, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.DecimalType
import org.apache.spark.storage.StorageLevel

/** Distributed, versioned LP fixtures for the sparsity/conditioning campaign.
  * CSV case descriptions control dimensions, support, family and seed. Spark range,
  * projection, joins and aggregation generate coefficients and primal-dual witnesses;
  * neither the matrix nor n-length vectors are constructed or collected on the driver.
  * Only scalar diagnostics and the m-length right-hand side cross that boundary.
  * The deterministic hash expressions are independent of Spark partition placement.
  */
object DataGenerator {
  /** Version tag mixed into [[Data.hash]] so fixtures produced by different
    * generator logic never share a fingerprint even when dimensions and seed match.
    */
  val GeneratorVersion = "dataframe-v1"

  /** Distributed input plus small diagnostics; callers must close cached DataFrames.
    * Coefficients contain (i,j,value), variables (j,x,s,c), and constraints (i,y,b).
    * Solver adapters allocate a sparse vector per column and a cost vector per Spark
    * partition, never an array for the complete matrix or all variables on the driver.
    */
  final class Data(
    val spec: BenchmarkCase, val coefficients: DataFrame, val variables: DataFrame,
    val constraints: DataFrame
  ) extends AutoCloseable {
    private val spark = coefficients.sparkSession

    import spark.implicits._

    /** Number of stored coefficient nonzeros. */
    lazy val nnz: Long = coefficients.count()

    /** Known optimal objective `bᵀy` computed from the planted dual witness. */
    lazy val objective: Double = constraints.agg(sum(col("b") * col("y"))).first().getDouble(0)

    /** The m-length right-hand side ordered by row index, collected to the driver. */
    lazy val b: DenseVector = new DenseVector(constraints.orderBy("i").select("b").as[Double].collect())

    /** Deterministic, partition-independent reproducibility fingerprint of coefficients and witnesses. */
    lazy val hash: String = {
      // Commutative integer summaries avoid ordering/partition-dependent float reductions.
      // This is a reproducibility fingerprint, not a cryptographic proof of all input bytes.
      val summaries = Seq(coefficients, variables.select("j", "x", "s"), constraints.select("i", "y")).map { frame =>
        val h = xxhash64(frame.columns.sorted.map(col): _*).cast(DecimalType(38, 0))
        frame.agg(count(lit(1)), sum(h), min(h), max(h)).first().toSeq.mkString(":")
      }
      MessageDigest.getInstance("SHA-256").digest(
          (GeneratorVersion + ":" + spec.toString + ":" + summaries.mkString(";")).getBytes(StandardCharsets.UTF_8))
        .map(b => f"${b & 0xff}%02x").mkString
    }

    /** Materializes the constraint matrix as an RDD of columns, repartitioned by column id.
      *
      * @param partitions number of output partitions; must be positive.
      * @return one tuple per variable: `(j, cost c_j, column as a length-m sparse vector)`.
      */
    def columns(partitions: Int): RDD[(Long, Double, SparseVector)] = {
      require(partitions > 0)
      val grouped = coefficients.groupBy("j").agg(sort_array(collect_list(struct(col("i"), col("value")))).as("entries"))
      val m = spec.m // Avoid capturing this Data object in executor closures.
      variables.select("j", "c").join(grouped, Seq("j"), "left")
        .repartition(partitions, col("j")).sortWithinPartitions("j").rdd.map { row =>
          val entries = Option(row.getAs[Seq[Row]]("entries")).getOrElse(Seq.empty)
          val vector = Vectors.sparse(m, entries.map(e => e.getLong(0).toInt -> e.getDouble(1))).asInstanceOf[SparseVector]
          (row.getAs[Long]("j"), row.getAs[Double]("c"), vector)
        }
    }

    /** @return `(min |value|, max |value|, max nonzeros in any single row)` across all coefficients. */
    def coefficientStats: (Double, Double, Long) = {
      val extremes = coefficients.agg(min(abs(col("value"))), max(abs(col("value")))).first()
      val largestRow = coefficients.groupBy("i").count().agg(max(col("count"))).first().getLong(0)
      (extremes.getDouble(0), extremes.getDouble(1), largestRow)
    }

    /** Validates the original LP optimality conditions against a candidate solution,
      * computing every sum distributed so only scalar aggregates return to the driver.
      *
      * @param actual DataFrame of solved primal/slack values with columns `j`, `x`, `s`.
      * @param dual   DataFrame of dual values with columns `i`, `y`.
      * @return normalized `primal`, `dual` and `gap` residuals, primal/dual `objective`
      *         values, `objective_error` against [[objective]] and `min_x`/`min_s` witnesses.
      */
    def residuals(actual: DataFrame, dual: DataFrame): Map[String, Double] = {
      val ax = coefficients.join(actual.select("j", "x"), Seq("j"))
        .groupBy("i").agg(sum(col("value") * col("x")).as("ax"))
      val pr = constraints.select("i", "b").join(ax, Seq("i"))
        .agg(sum(pow(col("ax") - col("b"), 2)), sum(pow(col("b"), 2))).first()
      val aty = coefficients.join(dual, Seq("i")).groupBy("j").agg(sum(col("value") * col("y")).as("aty"))
      val joined = variables.select("j", "c").join(actual, Seq("j")).join(aty, Seq("j"), "left")
      val dr = joined.agg(sum(pow(coalesce(col("aty"), lit(0.0)) + col("s") - col("c"), 2)),
        sum(pow(col("c"), 2)), sum(col("c") * col("x")), min(col("x")), min(col("s")), count(lit(1))).first()
      val dualObjective = constraints.select("i", "b").join(dual, Seq("i"))
        .agg(sum(col("b") * col("y")), count(lit(1))).first()
      require(dr.getLong(5) == spec.n && dualObjective.getLong(1) == spec.m, "Incomplete validation vectors")
      val primalObjective = dr.getDouble(2)
      val dualValue = dualObjective.getDouble(0)
      Map("primal" -> (math.sqrt(pr.getDouble(0)) / (1.0 + math.sqrt(pr.getDouble(1)))),
        "dual" -> (math.sqrt(dr.getDouble(0)) / (1.0 + math.sqrt(dr.getDouble(1)))),
        "gap" -> (math.abs(primalObjective - dualValue) / (1.0 + math.abs(dualValue))),
        "objective" -> primalObjective, "dual_objective" -> dualValue,
        "objective_error" -> (math.abs(primalObjective - objective) / (1.0 + math.abs(objective))),
        "min_x" -> dr.getDouble(3), "min_s" -> dr.getDouble(4))
    }

    /** Adapts distributed solver output into DataFrames and delegates to the DataFrame `residuals` overload.
      *
      * @param ids variable ids aligned element-wise with the `x` and `s` blocks.
      * @param x   distributed primal values.
      * @param y   dense m-length dual vector required by the solver.
      * @param s   distributed slack values.
      * @return the residual map described on the DataFrame overload.
      */
    def residuals(ids: RDD[Long], x: RDD[DenseVector], y: DenseVector, s: RDD[DenseVector]): Map[String, Double] = {
      val actual = ids.zip(x.flatMap(_.values)).zip(s.flatMap(_.values))
        .map { case ((j, xv), sv) => (j, xv, sv) }.toDF("j", "x", "s").persist(StorageLevel.MEMORY_AND_DISK)
      // y is already an m-vector required by the solver; x and s remain distributed.
      val dual = y.values.zipWithIndex.map { case (v, i) => (i.toLong, v) }.toSeq.toDF("i", "y")
      try residuals(actual, dual) finally actual.unpersist(blocking = true)
    }

    /** Unpersists the three cached fixture DataFrames. */
    override def close(): Unit = {
      variables.unpersist(blocking = true)
      constraints.unpersist(blocking = true)
      coefficients.unpersist(blocking = true)
    }
  }

  /** Decides whether a residual map indicates convergence within `tolerance`.
    *
    * @param r         residual map produced by [[Data.residuals]].
    * @param tolerance strict upper bound for the normalized residuals.
    * @return true iff every value is finite, `primal`/`dual`/`gap`/`objective_error`
    *         lie in `[0, tolerance)` and `min_x`/`min_s` are at least `-tolerance`.
    */
  def passes(r: Map[String, Double], tolerance: Double): Boolean =
    r.values.forall(v => !v.isNaN && !v.isInfinity) &&
      Seq("primal", "dual", "gap", "objective_error").forall(k => r.get(k).exists(v => v >= 0 && v < tolerance)) &&
      Seq("min_x", "min_s").forall(k => r.get(k).exists(_ >= -tolerance))

  /** Builds a distributed LP fixture with a planted optimal primal-dual solution.
    *
    * Coefficients and the length-n witnesses are produced with Spark range, joins and
    * aggregations and stay distributed; the right-hand side and costs are derived so the
    * generated witness is exactly optimal for the requested case family.
    *
    * @param spec       case description controlling dimensions, support, family and seed.
    * @param partitions number of partitions for the generated frames; must be positive.
    * @param spark      implicit session used to build the frames.
    * @return a cached [[Data]] fixture that the caller must [[Data.close]] when done.
    */
  def generate(spec: BenchmarkCase, partitions: Int = 8)(implicit spark: SparkSession): Data = {
    require(partitions > 0)

    def uniform(salt: String, keys: Column*): Column =
      pmod(xxhash64((Seq(lit(spec.seed), lit(salt)) ++ keys): _*), lit(1000000007L)).cast("double") / lit(1000000007.0)

    val rowIds = spark.range(0, spec.m.toLong, 1, partitions).toDF("i")
    val variableIds = spark.range(0, spec.n.toLong, 1, partitions).toDF("j")
    val basis = rowIds.select(col("i"), col("i").as("j"), lit(1.0).as("value"))
    val initial = if (spec.family == "dense") {
      rowIds.crossJoin(variableIds).select(col("i"), col("j"),
        when(col("i") === col("j"), lit(1.0))
          .otherwise((lit(0.01) + lit(0.04) * uniform("dense", col("i"), col("j"))) / lit(spec.n)).as("value"))
    } else if (spec.width == 1) basis else {
      val extra = rowIds.withColumn("k", explode(sequence(lit(0), lit(math.min(spec.width - 1, spec.n - spec.m) - 1))))
        .withColumn("j", lit(spec.m) + pmod(pmod(xxhash64(lit(spec.seed), col("i")), lit(spec.n - spec.m)) + col("k"), lit(spec.n - spec.m)))
        .select(col("i"), col("j").cast("long"), (lit(0.1) + lit(0.4) * uniform("coefficient", col("i"), col("j"))).as("value"))
      basis.unionByName(extra)
    }
    val dependent = if (spec.family == "dependent") {
      initial.withColumn("value", when(pmod(col("i"), lit(2)) === 1, col("value") * lit(1e-4)).otherwise(col("value")))
        .unionByName(initial.filter(pmod(col("i"), lit(2)) === 0 && col("i") + 1 < spec.m).withColumn("i", col("i") + 1))
        .groupBy("i", "j").agg(sum(col("value")).as("value"))
    } else initial
    val coefficients = (if (spec.family == "wide") dependent.withColumn("value", col("value") *
      pow(lit(1e-6), col("i").cast("double") / lit(math.max(1, spec.m - 1)))) else dependent)
      .persist(StorageLevel.MEMORY_AND_DISK)
    val witnesses = variableIds
      .withColumn("x", when(col("j") >= spec.m || (lit(spec.family == "degenerate") && pmod(col("j"), lit(5)) === 0), lit(0.0))
        .otherwise(lit(0.5) + uniform("x", col("j"))))
      .withColumn("s", when(col("j") < spec.m, lit(0.0)).otherwise(lit(0.5) + uniform("s", col("j"))))
    val dual = rowIds.withColumn("y", lit(2.0) * uniform("y", col("i")) - lit(1.0))
    val rhs = coefficients.join(witnesses.select("j", "x"), Seq("j"))
      .groupBy("i").agg(sum(col("value") * col("x")).as("b"))
    val costs = coefficients.join(dual, Seq("i"))
      .groupBy("j").agg(sum(col("value") * col("y")).as("aty"))
    val variables = witnesses.join(costs, Seq("j"), "left")
      .withColumn("c", coalesce(col("aty"), lit(0.0)) + col("s")).drop("aty").persist(StorageLevel.MEMORY_AND_DISK)
    val constraints = dual.join(rhs, Seq("i")).persist(StorageLevel.MEMORY_AND_DISK)
    val data = new Data(spec, coefficients, variables, constraints)
    variables.count();
    constraints.count()
    data
  }
}
