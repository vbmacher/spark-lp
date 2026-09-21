package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.dsl.compiler.LpCompiler

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions.{col, struct}
import org.apache.spark.sql.{Column, DataFrame, Dataset, Encoder, Row, SparkSession}

import scala.collection.mutable

object LpProblem {

  /** Creates an empty optimization model using the implicit Spark session. */
  def apply(name: String, sense: ObjectiveSense = Minimize)(implicit spark: SparkSession): LpProblem =
    new LpProblem(name, sense, spark)
}

/**
  * Mutable declaration of an optimization model solved by spark-lp.
  *
  * Declaring variables, expressions and constraints is lazy and performs no Spark action. Each
  * Call `solve` to read the current contents of referenced DataFrames and Datasets, validate the
  * model, converts it to the solver's internal form and reconstructs the result in the original
  * variable coordinates.
  *
  * Source data must remain stable for the duration of a solve, including result reconstruction.
  * Persist or otherwise materialize application data first when a repeatable snapshot is required.
  * Model edits are rejected while any solve or prepared native session is active.
  *
  * @param name model name used by inspection and export APIs
  * @param sense whether the objective is minimized or maximized
  */
final class LpProblem private[dsl](
  val name: String,
  val sense: ObjectiveSense,
  private[dsl] val spark: SparkSession) {

  private var activeSolves = 0
  private[dsl] def requireEditable(): Unit = synchronized {
    if (activeSolves != 0) throw new LpModelException("Model edits are unsupported during an active solve")
  }

  private[dsl] val sosGroups = mutable.ArrayBuffer.empty[LpSosGroup]

  /** Adds an SOS1 group: at most one member may be nonzero. */
  def addSos1(name: String, members: Seq[(LpVariable, Double)]): LpSosGroup =
    LpSos.add(this, name, SosKind.Sos1, members)

  /** Adds an SOS2 group: at most two adjacent members in weight order may be nonzero. */
  def addSos2(name: String, members: Seq[(LpVariable, Double)]): LpSosGroup =
    LpSos.add(this, name, SosKind.Sos2, members)

  private[dsl] val handles = mutable.ArrayBuffer.empty[VarSetHandle]
  private[dsl] var quadratic: Option[QpObjective] = None
  private[dsl] var objective: Option[LpExpr] = None
  private[dsl] val constraints = mutable.ArrayBuffer.empty[Either[LpConstraint, LpConstraintSet]]

  private def register(
    name: String,
    lowerBound: Double,
    upperBound: Option[Double],
    category: VariableCategory,
    domain: DomainAccess): VarSetHandle = {
    requireEditable()
    val handle = new VarSetHandle(this, handles.size, name, lowerBound, upperBound, category, domain)
    handles += handle
    handle
  }

  /**
    * Checks a complete assignment against this model's original bounds, variable types, SOS groups
    * and constraints without optimizing it.
    */
  def validateCandidate(values: RDD[LpCandidateValue],
    config: CandidateValidationConfig = CandidateValidationConfig()): LpCandidateReport =
    LpCandidateValidation.validate(this, values, config)

  /** Converts local `(variable, value)` pairs to candidate records owned by this model. */
  def candidateValues(values: Seq[(LpVariable, Double)]): RDD[LpCandidateValue] = {
    val mapped = values.map { case (variable, value) =>
      if (variable.handle.problem ne this) throw new LpModelException("Candidate contains a foreign variable")
      LpCandidateValue(LpVariableId(variable.handle.setIndex, variable.selectedKey.getOrElse("")), value)
    }
    spark.sparkContext.parallelize(mapped)
  }

  /** Returns a read-only view of this model; expanded RDD fields evaluate their source plans. */
  def inspect: LpModelView = new LpModelView(this)

  /**
    * Copies the model declarations and returns mappings between original and copied variables.
    *
    * The copy can be edited independently, but it shares the immutable lazy Spark source plans.
    */
  def copy(name: String = this.name): LpModelCopy =
    new LpModelCopy(this, new LpProblem(name, sense, spark))

  /**
    * Solves objectives in priority order on an independent model copy.
    *
    * Each optimal stage constrains its objective before the next stage. The returned result owns
    * every stage solution and must be closed.
    */
  def solvePriorities(priorities: Seq[LpPriority], config: SolveConfig = SolveConfig()): LpPriorityResult =
    LpPriorities.solve(this, priorities, config)

  /**
    * Declares one decision variable.
    *
    * Bounds are inclusive. `upperBound = None` means no upper bound; the default lower bound is
    * zero.
    */
  def variable(
    name: String,
    lowerBound: Double = 0.0,
    upperBound: Option[Double] = None,
    category: VariableCategory = Continuous): LpVariable =
    new LpVariable(register(name, lowerBound, upperBound, category, new ScalarDomain(spark)))

  /**
    * Declares a small, driver-local variable collection in input order.
    *
    * Keys must be unique and non-null. Use [[variables]] or [[variablesOf]] when Spark owns the
    * domain.
    */
  def indexedVariables[K: LpKeyEncoder](
    name: String, keys: Iterable[K], lowerBound: Double = 0.0,
    upperBound: Option[Double] = None, category: VariableCategory = Continuous): LpLocalVariables[K] = {
    val entries = LpLocalVariables.validated(keys)
    val names = entries.map { case (_, encoded) => s"$name[$encoded]" }
    require(!names.exists(n => handles.exists(_.name == n)), "Local variable name already exists")
    new LpLocalVariables(entries.zip(names).map { case ((key, _), n) =>
      key -> variable(n, lowerBound, upperBound, category)
    })
  }

  /**
    * Declares driver-local variables for every `(row, column)` pair.
    *
    * Output order follows the row input first and the column input second. Both inputs are checked
    * for null and duplicate keys even when the Cartesian product is empty.
    */
  def matrixVariables[R: LpKeyEncoder, C: LpKeyEncoder](
    name: String, rows: Iterable[R], columns: Iterable[C], lowerBound: Double = 0.0,
    upperBound: Option[Double] = None, category: VariableCategory = Continuous): LpLocalVariables[(R, C)] = {
    val r = LpLocalVariables.validated(rows).map(_._1)
    val c = LpLocalVariables.validated(columns).map(_._1)
    indexedVariables(name, r.flatMap(row => c.map(column => row -> column)), lowerBound, upperBound, category)
  }

  /**
    * Declares one variable for each unique, non-null DataFrame key.
    *
    * Key uniqueness and null checks run when the model is evaluated. Use `struct(...)` for a key
    * composed from multiple columns.
    */
  def variables(
    name: String,
    domain: DataFrame,
    key: Column,
    lowerBound: Double = 0.0,
    upperBound: Option[Double] = None,
    category: VariableCategory = Continuous): LpVariableSet[Row] = {
    val handle = register(name, lowerBound, upperBound, category, new ColumnDomain(domain, key))
    val keyLocal = key
    val weightsBuilder: (Dataset[Row], Row => Double) => RDD[(String, Double)] = (source, fn) => {
      val df = source.toDF()
      val columns = df.columns.map(col)
      val fnLocal = fn
      df.select(keyLocal.as("__lp_key"), struct(columns: _*).as("__lp_row")).rdd.map { row =>
        (KeyCodec.encodeValue(row.get(0)), fnLocal(row.getStruct(1)))
      }
    }
    new LpVariableSet[Row](handle, weightsBuilder, Some(key))
  }

  /**
    * Declares one variable for each unique, non-null key produced from a typed Dataset.
    *
    * Primitive and tuple keys have built-in encoders; define an [[LpKeyEncoder]] for another key
    * type.
    */
  def variablesOf[K, Key](
    name: String,
    domain: Dataset[K],
    key: K => Key,
    lowerBound: Double = 0.0,
    upperBound: Option[Double] = None,
    category: VariableCategory = Continuous)(
    implicit kEncoder: Encoder[K], keyEncoder: LpKeyEncoder[Key]): LpVariableSet[K] = {
    val handle = register(name, lowerBound, upperBound, category,
      new TypedDomain[K, Key](domain, key, keyEncoder, kEncoder))
    val keyLocal = key
    val encLocal = keyEncoder
    val weightsBuilder: (Dataset[K], K => Double) => RDD[(String, Double)] = (source, fn) => {
      val fnLocal = fn
      source.rdd.map { k =>
        val keyValue = keyLocal(k)
        val encoded = if (keyValue == null) null else KeyCodec.encodeParts(encLocal.parts(keyValue))
        (encoded, fnLocal(k))
      }
    }
    new LpVariableSet[K](handle, weightsBuilder, None)
  }

  /** Sets the linear objective and fails if an objective already exists. */
  def +=(objective: LpExpr): this.type = {
    requireEditable()
    if (this.objective.isDefined) {
      throw new LpModelException(
        s"Problem '$name' already has an objective; use setObjective to replace it deliberately")
    }
    this.objective = Some(objective)
    this
  }

  def +=(objective: QpObjective): this.type = {
    requireEditable()
    if (this.objective.isDefined) throw new LpModelException("Problem already has an objective; use setObjective")
    setObjective(objective)
  }

  def setObjective(objective: QpObjective): this.type = {
    requireEditable()
    this.objective = Some(objective.linear)
    this.quadratic = Some(objective)
    this
  }

  /** Replaces any existing objective with a linear objective. */
  def setObjective(objective: LpExpr): this.type = {
    requireEditable()
    this.quadratic = None
    this.objective = Some(objective)
    this
  }

  /** Adds one scalar constraint to the model. */
  def +=(constraint: LpConstraint): this.type = {
    requireEditable()
    constraints += Left(constraint)
    this
  }

  /** Adds one generated constraint for each key represented by the constraint set. */
  def +=(constraints: LpConstraintSet): this.type = {
    requireEditable()
    this.constraints += Right(constraints)
    this
  }

  /**
    * Solves the model with spark-lp's built-in solver.
    *
    * Repeated calls re-read all lazy sources and compile the current declarations. Invalid models
    * raise [[LpModelException]]; numerical linear-algebra failures raise
    * [[LpNumericalException]]. Close the returned [[LpSolution]] after completing Spark actions on
    * its data.
    */
  def solve(config: SolveConfig = SolveConfig()): LpSolution = {
    solve(config, LpSolveClock.system)
  }

  private[dsl] def solve(config: SolveConfig, clock: LpSolveClock): LpSolution = {
    synchronized { activeSolves += 1 }
    try new LpCompiler(this, config, clock).solve()
    finally synchronized { activeSolves -= 1 }
  }

  /** Creates an owned starting-value snapshot from distributed candidate records. */
  def start(values: org.apache.spark.rdd.RDD[LpCandidateValue], config: LpStartConfig = LpStartConfig()): LpStart =
    LpStart.create(this, values, config)

  /** Creates starting values from local variable/value pairs. */
  def start(values: Seq[(LpVariable, Double)]): LpStart = start(candidateValues(values))

  /** Solves through an external adapter using default transfer and validation options. */
  def solve(adapter: LpSolverAdapter): LpSolution = solve(adapter, LpAdapterOptions())

  /** Solves through an external adapter and independently validates any returned candidate. */
  def solve(adapter: LpSolverAdapter, options: LpAdapterOptions): LpSolution = {
    synchronized { activeSolves += 1 }
    try LpAdapterSolve.run(this, adapter, options)
    finally synchronized { activeSolves -= 1 }
  }

  /**
    * Prepares a reusable native adapter session when the adapter supports one.
    *
    * The returned session prevents structural model edits until it is closed.
    */
  def prepareNative(adapter: LpSolverAdapter, options: LpAdapterOptions = LpAdapterOptions()): Either[LpUnsupported, LpNativeSession] = {
    if (!adapter.capabilities.nativeSession) Left(LpUnsupported("nativeSession", s"${adapter.name} does not expose native sessions"))
    else {
      synchronized { activeSolves += 1 }
      var released = false
      val release = () => synchronized { if (!released) { released = true; activeSolves -= 1 } }
      LpNativeSession.open(this, adapter, options, release)
    }
  }
}
