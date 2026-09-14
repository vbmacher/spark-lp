package com.github.vbmacher.spark_lp.dsl

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.DoubleType

final case class LpVariableDeclaration(id: Int, name: String, lower: Double, upper: Option[Double],
                                      category: VariableCategory, domainKind: String)
final case class LpConstraintId(declaration: Int, key: String)
final case class LpConstraintDeclaration(id: Int, name: String, sense: String, grouped: Boolean)
final case class LpExpandedVariable(id: LpVariableId, name: String, lower: Double, upper: Option[Double],
                                   category: VariableCategory, keyParts: Seq[String] = Seq.empty)
final case class LpExpandedConstraint(id: LpConstraintId, name: String, sense: String, rhs: Double,
                                     group: Seq[String])
final case class LpMatrixCoefficient(row: LpConstraintId, variable: LpVariableId, value: Double)
final case class LpModelStatistics(variableDeclarations: Int, constraintDeclarations: Int,
  variables: Long, constraints: Long, constraintNonzeros: Long, objectiveNonzeros: Long)
final case class LpQuadraticFactor(index: Int, weight: Double, constant: Double, coefficients: RDD[LpCoefficient])

/** Read-only declaration snapshot over lazy source plans. Expanded data remains distributed. */
final class LpModelView private[dsl](problem: LpProblem) {
  private implicit val spark: org.apache.spark.sql.SparkSession = problem.spark
  private val sc = spark.sparkContext
  val name: String = problem.name
  val sense: ObjectiveSense = problem.sense
  private val handles = problem.handles.toVector
  private val metadata = handles.map(_.metadata)
  private val rows = problem.constraints.toVector
  val objective: LpExpr = problem.objective.getOrElse(LpExpr.zero)
  private val quadratic = problem.quadratic
  val sosGroups: Vector[LpSosData] = problem.sosGroups.map(_.data).toVector
  val objectiveConstant: Double = objective.constant
  val variableDeclarations: Vector[LpVariableDeclaration] = handles.map(h =>
    LpVariableDeclaration(h.setIndex, h.name, h.lowerBound, h.upperBound, h.category, h.domain match {
      case _: ScalarDomain => "scalar"
      case _: ColumnDomain => "dataframe"
      case _ => "dataset"
    }))
  val constraintDeclarations: Vector[LpConstraintDeclaration] = rows.zipWithIndex.map {
    case (Left(c), i) => LpConstraintDeclaration(i, c.explicitName.getOrElse(s"_c$i"), c.sense.symbol, false)
    case (Right(c), i) => LpConstraintDeclaration(i, c.explicitName.getOrElse(s"_c$i"), c.sense.symbol, true)
  }

  def variables: RDD[LpExpandedVariable] = {
    val pieces = handles.zip(variableDeclarations).zip(metadata).map { case ((h, d), m) =>
      h.domain.keyPairs().map { case (key, display) =>
        val bounds = m.at(key)
        LpExpandedVariable(LpVariableId(d.id, key), m.display(key, display), bounds.lower, bounds.upper, d.category, display)
      }
    }
    if (pieces.isEmpty) sc.emptyRDD else sc.union(pieces)
  }

  def objectiveCoefficients: RDD[LpCoefficient] = coefficientsOf(objective)
  def diagonalCoefficients: RDD[LpCoefficient] = quadratic.map(q => coefficientsOf(q.diagonal)).getOrElse(sc.emptyRDD)
  def quadraticFactors: Vector[LpQuadraticFactor] = quadratic.toVector.flatMap(_.factors).zipWithIndex.map {
    case ((e, weight), i) => LpQuadraticFactor(i, weight, e.constant, coefficientsOf(e))
  }
  val hasQuadraticObjective: Boolean = quadratic.nonEmpty

  private def coefficientsOf(e: LpExpr): RDD[LpCoefficient] =
    LpExpressionData.expand(e, Some(problem)).map { case ((family, key), value) =>
      LpCoefficient(LpVariableId(family, key), value)
    }

  private def expanded: Vector[(RDD[LpExpandedConstraint], RDD[LpMatrixCoefficient])] =
    rows.zip(constraintDeclarations).map {
      case (Left(c), d) =>
        LpExpressionData.check(c.rhs)
        val id = LpConstraintId(d.id, "")
        (sc.parallelize(Seq(LpExpandedConstraint(id, d.name, d.sense, c.rhs, Seq.empty)), 1),
          coefficientsOf(new LpExpr(c.terms, 0.0)).map(c => LpMatrixCoefficient(id, c.variable, c.value)))
      case (Right(c), d) => expandGrouped(c, d)
    }

  def constraints: RDD[LpExpandedConstraint] = {
    val pieces = expanded.map(_._1)
    if (pieces.isEmpty) sc.emptyRDD else sc.union(pieces)
  }
  def coefficients: RDD[LpMatrixCoefficient] = {
    val pieces = expanded.map(_._2)
    if (pieces.isEmpty) sc.emptyRDD else sc.union(pieces)
  }

  private def expandGrouped(c: LpConstraintSet, d: LpConstraintDeclaration):
      (RDD[LpExpandedConstraint], RDD[LpMatrixCoefficient]) = {
    val t = c.grouped.terms
    if (t.handle.problem ne problem) throw new LpModelException(s"Constraint '${d.name}' references a foreign model")
    val names = c.grouped.by
    require(names.nonEmpty, "Grouped constraints require grouping columns")
    val n = names.size
    val selected = t.source.select((t.by :+ t.key.as("__lp_key") :+
      t.coefficient.cast(DoubleType).as("__lp_coefficient")): _*)
      .select((names.map(col) :+ col("__lp_key") :+ col("__lp_coefficient")): _*)
    val raw = selected.rdd.map { row =>
      val parts = (0 until n).map(row.get)
      val group = KeyCodec.encodeParts(parts)
      val display = parts.flatMap(KeyCodec.displayParts)
      val key = KeyCodec.encodeValue(row.get(n))
      val value = if (row.isNullAt(n + 1)) Double.NaN else row.getDouble(n + 1)
      ((group, key), (display, value))
    }
    if (raw.filter { case ((group, key), (_, value)) =>
      group == null || key == null || !LpExpressionData.finite(value)
    }.take(1).nonEmpty) throw new LpModelException(s"Constraint '${d.name}': null keys or non-finite coefficients")
    val domainKeys = t.handle.domain.keyPairs().keys
    if (raw.keys.map(_._2).subtract(domainKeys).take(1).nonEmpty)
      throw new LpModelException(s"Constraint '${d.name}': foreign variable keys")
    val groups = raw.map { case ((group, _), (display, _)) => group -> display }.reduceByKey((a, _) => a)
    val rhs = c.rhs match {
      case Left(value) => LpExpressionData.check(value); groups.mapValues(display => (display, value))
      case Right(frame) =>
        val values = frame.select((names.map(col) :+ col("rhs").cast(DoubleType)): _*).rdd.map { row =>
          val parts = (0 until n).map(row.get)
          KeyCodec.encodeParts(parts) -> (parts.flatMap(KeyCodec.displayParts),
            if (row.isNullAt(n)) Double.NaN else row.getDouble(n))
        }
        if (values.filter { case (key, (_, value)) => key == null || !LpExpressionData.finite(value) }.take(1).nonEmpty ||
          values.mapValues(_ => 1).reduceByKey(_ + _).filter(_._2 > 1).take(1).nonEmpty ||
          groups.keys.subtract(values.keys).take(1).nonEmpty)
          throw new LpModelException(s"Constraint '${d.name}': invalid, duplicate or missing RHS keys")
        values
    }
    val family = t.handle.setIndex
    val coefficients = raw.mapValues(_._2).reduceByKey(_ + _)
    if (coefficients.values.filter(v => !LpExpressionData.finite(v)).take(1).nonEmpty)
      throw new LpModelException(s"Constraint '${d.name}': coefficient overflow")
    (rhs.map { case (key, (display, value)) =>
      LpExpandedConstraint(LpConstraintId(d.id, key), KeyCodec.displayName(d.name, display), d.sense, value, display)
    }, coefficients.filter(_._2 != 0.0).map { case ((group, key), value) =>
      LpMatrixCoefficient(LpConstraintId(d.id, group), LpVariableId(family, key), value)
    })
  }

  /** Exact actions on original model sizes; no presolve, auxiliary columns or optimization. */
  def statistics(): LpModelStatistics = {
    val v = variables
    if (v.filter(_.id.key == null).take(1).nonEmpty ||
      v.map(x => x.id -> 1).reduceByKey(_ + _).filter(_._2 > 1).take(1).nonEmpty)
      throw new LpModelException("Null or duplicate original variable identities")
    val c = constraints
    if (c.map(x => x.name -> 1).reduceByKey(_ + _).filter(_._2 > 1).take(1).nonEmpty)
      throw new LpModelException("Duplicate original constraint names")
    LpModelStatistics(variableDeclarations.size, constraintDeclarations.size,
      v.count(), c.count(), coefficients.count(), objectiveCoefficients.count())
  }
}
