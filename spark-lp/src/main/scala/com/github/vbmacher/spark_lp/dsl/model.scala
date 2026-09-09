package com.github.vbmacher.spark_lp.dsl

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions.{col, lit, struct}
import org.apache.spark.sql.types.{DoubleType, StringType, StructField, StructType}
import org.apache.spark.sql.{AnalysisException, Column, DataFrame, Dataset, Encoder, Encoders, Row, SparkSession}

private[dsl] sealed trait LpSense { def symbol: String }

private[dsl] object LpSense {
  case object Le extends LpSense { val symbol = "<=" }
  case object Ge extends LpSense { val symbol = ">=" }
  case object Eq extends LpSense { val symbol = "==" }
}

/**
  * An immutable linear expression: a sum of terms plus a constant. Operators are supplied by
  * [[implicits]]; building an expression performs no Spark action.
  */
final class LpExpr private[dsl](
  private[dsl] val terms: Vector[LpTerm],
  private[dsl] val constant: Double) {

  private[dsl] def plus(other: LpExpr): LpExpr = new LpExpr(terms ++ other.terms, constant + other.constant)

  private[dsl] def plusConstant(value: Double): LpExpr = new LpExpr(terms, constant + value)

  private[dsl] def scaledBy(factor: Double): LpExpr = new LpExpr(terms.map(_.scaledBy(factor)), constant * factor)

  private[dsl] def compare(sense: LpSense, rhs: Double): LpConstraint =
    new LpConstraint(terms, sense, rhs - constant, None)

  /** An expression RHS is normalised by moving all its terms to the left. */
  private[dsl] def compare(sense: LpSense, rhs: LpExpr): LpConstraint = {
    val moved = plus(rhs.scaledBy(-1.0))
    new LpConstraint(moved.terms, sense, -moved.constant, None)
  }
}

private[dsl] object LpExpr {
  val zero: LpExpr = new LpExpr(Vector.empty, 0.0)

  def constant(value: Double): LpExpr = new LpExpr(Vector.empty, value)
}

/** One symbolic term: a variable set combined with a coefficient rule. */
private[dsl] sealed trait LpTerm {
  def handle: VarSetHandle
  def scaledBy(factor: Double): LpTerm
}

/** The same constant coefficient for every key of the set (scalar variables have one key). */
private[dsl] final case class ConstCoeffTerm(handle: VarSetHandle, coeff: Double) extends LpTerm {
  override def scaledBy(factor: Double): LpTerm = copy(coeff = coeff * factor)
}

/** A coefficient held in a Spark column, resolved against the set's own domain. */
private[dsl] final case class ColumnCoeffTerm(handle: VarSetHandle, column: Column, scale: Double) extends LpTerm {
  override def scaledBy(factor: Double): LpTerm = copy(scale = scale * factor)
}

/** A coefficient computed from a keyed source dataset (`weightedBy`); evaluated at solve time. */
private[dsl] final case class WeightedCoeffTerm(
  handle: VarSetHandle,
  weights: () => RDD[(String, Double)],
  scale: Double,
  description: String) extends LpTerm {
  override def scaledBy(factor: Double): LpTerm = copy(scale = scale * factor)
}

/** A single scalar constraint with normalised terms on the left and a constant RHS. */
final class LpConstraint private[dsl](
  private[dsl] val terms: Vector[LpTerm],
  private[dsl] val sense: LpSense,
  private[dsl] val rhs: Double,
  private[dsl] val explicitName: Option[String]) {

  private[dsl] def withName(name: String): LpConstraint = new LpConstraint(terms, sense, rhs, Some(name))
}

/** A keyed family of constraints — one row per group key — built from [[GroupedLpExpr]]. */
final class LpConstraintSet private[dsl](
  private[dsl] val grouped: GroupedLpExpr,
  private[dsl] val sense: LpSense,
  private[dsl] val rhs: Either[Double, DataFrame],
  private[dsl] val explicitName: Option[String]) {

  private[dsl] def withName(name: String): LpConstraintSet = new LpConstraintSet(grouped, sense, rhs, Some(name))
}

/**
  * Relational term rows: each row is one non-zero coefficient of one decision variable in one
  * grouped constraint row. Built by [[LpVariableSet.terms]]; consumed by `lpSumBy`.
  */
final class LpTerms private[dsl](
  private[dsl] val handle: VarSetHandle,
  private[dsl] val key: Column,
  private[dsl] val source: DataFrame,
  private[dsl] val by: Seq[Column],
  private[dsl] val coefficient: Column)

/** One symbolic expression per group key; comparison operators are supplied by [[implicits]]. */
final class GroupedLpExpr private[dsl](
  private[dsl] val terms: LpTerms,
  private[dsl] val by: Seq[String])

/** How a set's domain and coefficient sources are read. All evaluation happens at solve time. */
private[dsl] sealed trait DomainAccess {

  /** (encoded key, display parts) for every domain row. Encoded key is `null` for a null key. */
  def keyPairs(): RDD[(String, Seq[String])]

  /** (encoded key, coefficient) for a coefficient column resolved against the set's own domain. */
  def columnPairs(column: Column, context: String): RDD[(String, Double)]

  /** Original domain plus `lp_variable` and `lp_value` columns, joined by encoded key. */
  def attachValues(values: RDD[(String, Double)], setName: String): DataFrame
}

private[dsl] final class ScalarDomain(spark: SparkSession) extends DomainAccess {

  override def keyPairs(): RDD[(String, Seq[String])] =
    spark.sparkContext.parallelize(Seq(("" , Seq.empty[String])), 1)

  override def columnPairs(column: Column, context: String): RDD[(String, Double)] =
    throw new LpModelException(s"$context: a scalar variable does not support column coefficients")

  override def attachValues(values: RDD[(String, Double)], setName: String): DataFrame = {
    val schema = StructType(Seq(StructField("lp_variable", StringType), StructField("lp_value", DoubleType)))
    val rows = values.map { case (_, value) => Row(setName, value) }
    spark.createDataFrame(rows, schema)
  }
}

private[dsl] final class ColumnDomain(val df: DataFrame, key: Column) extends DomainAccess {

  override def keyPairs(): RDD[(String, Seq[String])] = {
    df.select(key.as("__lp_key")).rdd.map { row =>
      val value = row.get(0)
      (KeyCodec.encodeValue(value), KeyCodec.displayParts(value))
    }
  }

  override def columnPairs(column: Column, context: String): RDD[(String, Double)] = {
    val selected =
      try {
        df.select(key.as("__lp_key"), column.cast(DoubleType).as("__lp_coeff"))
      } catch {
        case e: AnalysisException =>
          throw new LpModelException(s"$context: cannot resolve coefficient column against the variable domain: ${e.getMessage}")
      }
    selected.rdd.map { row =>
      val coeff = if (row.isNullAt(1)) Double.NaN else row.getDouble(1)
      (KeyCodec.encodeValue(row.get(0)), coeff)
    }
  }

  override def attachValues(values: RDD[(String, Double)], setName: String): DataFrame = {
    val spark = df.sparkSession
    val schema = StructType(df.schema.fields ++
      Seq(StructField("lp_variable", StringType), StructField("lp_value", DoubleType)))
    val columns = df.columns.map(col)
    val keyed = df.select(key.as("__lp_key"), struct(columns: _*).as("__lp_row")).rdd.map { row =>
      val value = row.get(0)
      (KeyCodec.encodeValue(value), (KeyCodec.displayParts(value), row.getStruct(1)))
    }
    val rows = keyed.join(values).map { case (_, ((display, original), value)) =>
      Row.fromSeq(original.toSeq :+ KeyCodec.displayName(setName, display) :+ value)
    }
    spark.createDataFrame(rows, schema)
  }
}

private[dsl] final class TypedDomain[K, Key](
  ds: Dataset[K],
  keyFn: K => Key,
  keyEncoder: LpKeyEncoder[Key],
  kEncoder: Encoder[K]) extends DomainAccess {

  override def keyPairs(): RDD[(String, Seq[String])] = {
    val fn = keyFn
    val enc = keyEncoder
    ds.rdd.map { k =>
      val key = fn(k)
      if (key == null) (null: String, Seq("null"))
      else {
        val parts = enc.parts(key)
        (KeyCodec.encodeParts(parts), parts.flatMap(KeyCodec.flatParts).map(String.valueOf(_)))
      }
    }
  }

  override def columnPairs(column: Column, context: String): RDD[(String, Double)] =
    throw new LpModelException(
      s"$context: column coefficients require a variable set declared over a DataFrame domain " +
        "(LpProblem.variables); for a typed variable set use weightedBy")

  override def attachValues(values: RDD[(String, Double)], setName: String): DataFrame = {
    val spark = ds.sparkSession
    val fn = keyFn
    val enc = keyEncoder
    val keyed = ds.rdd.map { k =>
      val key = fn(k)
      val parts = if (key == null) Seq.empty[Any] else enc.parts(key)
      val display = parts.flatMap(KeyCodec.flatParts).map(String.valueOf(_))
      (KeyCodec.encodeParts(parts), (display, k))
    }
    val setNameLocal = setName
    val joined = keyed.join(values).map { case (_, ((display, k), value)) =>
      (k, KeyCodec.displayName(setNameLocal, display), value)
    }
    implicit val tupleEncoder: Encoder[(K, String, Double)] =
      Encoders.tuple(kEncoder, Encoders.STRING, Encoders.scalaDouble)
    val df = spark.createDataset(joined).toDF("__lp_domain", "lp_variable", "lp_value")
    df.schema.head.dataType match {
      case _: StructType =>
        df.select(col("__lp_domain.*"), col("lp_variable"), col("lp_value"))
      case _ =>
        val original = ds.toDF().columns.head
        df.withColumnRenamed("__lp_domain", original)
    }
  }
}

/** Internal identity + metadata shared by [[LpVariable]] and [[LpVariableSet]]. */
private[dsl] final class VarSetHandle(
  val problem: LpProblem,
  val setIndex: Int,
  val name: String,
  val lowerBound: Double,
  val upperBound: Option[Double],
  val category: VariableCategory,
  val domain: DomainAccess) {

  private[dsl] def toExpr(coeff: Double): LpExpr = new LpExpr(Vector(ConstCoeffTerm(this, coeff)), 0.0)
}

/** One scalar decision variable. */
final class LpVariable private[dsl](private[dsl] val handle: VarSetHandle) {
  def name: String = handle.name
}

/**
  * One symbolic decision variable for every unique key in the domain; it is not a collected Scala
  * map. The original domain is retained so a solved value can be joined back to application columns.
  */
final class LpVariableSet[K] private[dsl](
  private[dsl] val handle: VarSetHandle,
  private[dsl] val weightsBuilder: (Dataset[K], K => Double) => RDD[(String, Double)],
  private[dsl] val keyColumn: Option[Column]) {

  def name: String = handle.name

  /** Sum all variables, with coefficient one. */
  def sum: LpExpr = handle.toExpr(1.0)

  /** Sum variables weighted by a column of their domain. */
  def sum(coefficient: Column): LpExpr =
    new LpExpr(Vector(ColumnCoeffTerm(handle, coefficient, 1.0)), 0.0)

  /** One sum per domain group: `amount.sumBy("region")($"cost")` or `amount.sumBy("region")()`. */
  def sumBy(by: String*)(coefficient: Column = lit(1.0)): GroupedLpExpr = handle.domain match {
    case domain: ColumnDomain => lpSumBy(terms(domain.df, by.map(col), coefficient), by)
    case _ => throw new LpModelException(s"sumBy requires a DataFrame variable domain: '${handle.name}'")
  }

  /**
    * Relational term rows for DataFrame-native bulk constraints: one row per non-zero coefficient,
    * identified by the grouping columns and the set's key column resolved against `source`.
    */
  def terms(source: DataFrame, by: Seq[Column], coefficient: Column): LpTerms = keyColumn match {
    case Some(key) => new LpTerms(handle, key, source, by, coefficient)
    case None =>
      throw new LpModelException(
        s"terms(...) requires variable set '${handle.name}' to be declared over a DataFrame domain " +
          "with a key column (LpProblem.variables)")
  }
}
