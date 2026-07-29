package com.github.vbmacher.spark_lp.dsl

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
  * DataFrames as Spark sees them at that moment. Within one solve every source is read once into
  * cached, compiled structures, and two solves over identical source contents produce the same
  * matrix, ordering and solution. Callers who need a durable snapshot should persist their sources.
  */
final class LpProblem private[dsl](
  val name: String,
  val sense: ObjectiveSense,
  private[dsl] val spark: SparkSession) {

  private[dsl] val handles = mutable.ArrayBuffer.empty[VarSetHandle]
  private[dsl] var objective: Option[LpExpr] = None
  private[dsl] val constraints = mutable.ArrayBuffer.empty[Either[LpConstraint, LpConstraintSet]]

  private def register(
    name: String,
    lowerBound: Double,
    upperBound: Option[Double],
    category: VariableCategory,
    domain: DomainAccess): VarSetHandle = {
    val handle = new VarSetHandle(this, handles.size, name, lowerBound, upperBound, category, domain)
    handles += handle
    handle
  }

  /** Creates one decision variable. */
  def variable(
    name: String,
    lowerBound: Double = 0.0,
    upperBound: Option[Double] = None,
    category: VariableCategory = Continuous): LpVariable =
    new LpVariable(register(name, lowerBound, upperBound, category, new ScalarDomain(spark)))

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
    if (this.objective.isDefined) {
      throw new LpModelException(
        s"Problem '$name' already has an objective; use setObjective to replace it deliberately")
    }
    this.objective = Some(objective)
    this
  }

  /** Replaces the objective. */
  def setObjective(objective: LpExpr): this.type = {
    this.objective = Some(objective)
    this
  }

  def +=(constraint: LpConstraint): this.type = {
    constraints += Left(constraint)
    this
  }

  def +=(constraints: LpConstraintSet): this.type = {
    this.constraints += Right(constraints)
    this
  }

  /**
    * Compiles and solves the model against the current contents of its source data; may be called
    * repeatedly. Validation failures raise [[LpModelException]]; solver-side numerical failures
    * raise [[LpNumericalException]].
    */
  def solve(config: SolveConfig = SolveConfig()): LpSolution =
    new LpCompiler(this, config).solve()
}
