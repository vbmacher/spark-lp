package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.dsl.compiler.LpCompiler

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions.{col, struct}
import org.apache.spark.sql.{Column, DataFrame, Dataset, Encoder, Row, SparkSession}

import scala.collection.mutable

object LpProblem {

  /** Creates an empty problem with the given objective sense. */
  def apply(name: String, sense: ObjectiveSense = Minimize)(implicit spark: SparkSession): LpProblem =
    new LpProblem(name, sense, spark)
}

/**
  * A declarative LP model. Building the model performs no Spark action; `solve` compiles the model
  * to the equality-form solver (`minimize c^T x` subject to `Ax = b`, `x >= 0`) and runs it.
  *
  * `solve` does not snapshot lazy sources: each call evaluates the domain and coefficient
  * DataFrames as Spark sees them at that moment. Compiled structures are cached for the solve;
  * sources must remain stable while compiling and when joining results back to domain columns.
  * Callers who need a durable snapshot should materialise their sources first.
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
  def addSos1(name: String, members: Seq[(LpVariable, Double)]): LpSosGroup =
    LpSos.add(this, name, SosKind.Sos1, members)
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

  /** Checks a complete independent assignment against original rows, bounds and categories. */
  def validateCandidate(values: RDD[LpCandidateValue],
    config: CandidateValidationConfig = CandidateValidationConfig()): LpCandidateReport =
    LpCandidateValidation.validate(this, values, config)

  /** Creates a local scalar/member assignment without accepting foreign model handles. */
  def candidateValues(values: Seq[(LpVariable, Double)]): RDD[LpCandidateValue] = {
    val mapped = values.map { case (variable, value) =>
      if (variable.handle.problem ne this) throw new LpModelException("Candidate contains a foreign variable")
      LpCandidateValue(LpVariableId(variable.handle.setIndex, variable.selectedKey.getOrElse("")), value)
    }
    spark.sparkContext.parallelize(mapped)
  }

  /** Read-only declaration snapshot; source plans are evaluated only by expanded inspection methods. */
  def inspect: LpModelView = new LpModelView(this)

  /** Copies declarations and returns identity mappings; lazy Spark sources are shared. */
  def copy(name: String = this.name): LpModelCopy =
    new LpModelCopy(this, new LpProblem(name, sense, spark))

  /** Solves priorities in this model's objective sense on an independent copy. Close the result. */
  def solvePriorities(priorities: Seq[LpPriority], config: SolveConfig = SolveConfig()): LpPriorityResult =
    LpPriorities.solve(this, priorities, config)

  /** Creates one decision variable. */
  def variable(
    name: String,
    lowerBound: Double = 0.0,
    upperBound: Option[Double] = None,
    category: VariableCategory = Continuous): LpVariable =
    new LpVariable(register(name, lowerBound, upperBound, category, new ScalarDomain(spark)))

  /** Allocates scalar variables locally in input order; keys must be unique and non-null. */
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

  /** Cartesian product in row-major order; both axes are validated even when the product is empty. */
  def matrixVariables[R: LpKeyEncoder, C: LpKeyEncoder](
    name: String, rows: Iterable[R], columns: Iterable[C], lowerBound: Double = 0.0,
    upperBound: Option[Double] = None, category: VariableCategory = Continuous): LpLocalVariables[(R, C)] = {
    val r = LpLocalVariables.validated(rows).map(_._1)
    val c = LpLocalVariables.validated(columns).map(_._1)
    indexedVariables(name, r.flatMap(row => c.map(column => row -> column)), lowerBound, upperBound, category)
  }

  /** One decision variable per unique value of `key` in `domain`. */
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

  /** Typed variant of `variables`. Key types beyond String are supported via [[LpKeyEncoder]]. */
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

  /** Sets the objective. Throws if one is already set; use `setObjective` to replace deliberately. */
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

  /** Replaces the objective. */
  def setObjective(objective: LpExpr): this.type = {
    requireEditable()
    this.quadratic = None
    this.objective = Some(objective)
    this
  }

  def +=(constraint: LpConstraint): this.type = {
    requireEditable()
    constraints += Left(constraint)
    this
  }

  def +=(constraints: LpConstraintSet): this.type = {
    requireEditable()
    this.constraints += Right(constraints)
    this
  }

  /**
    * Compiles and solves the model against the current contents of its source data; may be called
    * repeatedly. Validation failures raise [[LpModelException]]; solver-side numerical failures
    * raise [[LpNumericalException]].
    */
  def solve(config: SolveConfig = SolveConfig()): LpSolution = {
    synchronized { activeSolves += 1 }
    try new LpCompiler(this, config).solve()
    finally synchronized { activeSolves -= 1 }
  }
}
