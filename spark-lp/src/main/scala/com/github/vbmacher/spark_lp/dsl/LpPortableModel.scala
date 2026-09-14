package com.github.vbmacher.spark_lp.dsl

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession

/** Public affine component: repeated sparse entries add; constants are explicit. */
final case class LpAffineData(coefficients: RDD[LpCoefficient], constant: Double)
final case class LpConstraintData(row: LpExpandedConstraint, expression: LpAffineData)

/** Schema 1 stores evaluated algebra, not Spark source plans or closures. RDDs remain distributed. */
final case class LpPortableModel(schemaVersion: Int, name: String, sense: ObjectiveSense,
  declarations: Vector[LpVariableDeclaration], variables: RDD[LpExpandedVariable],
  constraints: RDD[LpExpandedConstraint], coefficients: RDD[LpMatrixCoefficient],
  objective: LpAffineData, diagonal: RDD[LpCoefficient], factors: Vector[LpQuadraticFactor]) {

  /** Explicit import action: at most maxLocalRows row metadata is brought to the driver. */
  def toProblem(maxLocalRows: Int = 10000, maxLocalOverrides: Int = 10000)(implicit spark: SparkSession): LpImportedModel = {
    require(schemaVersion == 1, s"Unsupported portable model schema $schemaVersion")
    require(maxLocalRows >= 0 && maxLocalRows < Int.MaxValue, "maxLocalRows must be nonnegative and below Int.MaxValue")
    require(declarations.map(_.id).distinct.size == declarations.size, "Duplicate variable declaration IDs")
    require(declarations.map(_.name).distinct.size == declarations.size, "Duplicate variable declaration names")
    require(declarations.indices.forall(i => declarations(i).id == i), "Declaration IDs must be contiguous in order")
    declarations.foreach { d =>
      if (d.lower.isNaN || d.lower.isPosInfinity || d.upper.exists(u => !LpExpressionData.finite(u) || u < d.lower))
        throw new LpModelException("Invalid portable variable bounds")
    }
    require(maxLocalOverrides >= 0, "maxLocalOverrides must be nonnegative")
    val declarationMap = declarations.map(d => d.id -> d).toMap
    val invalid = variables.filter { v =>
      v.id == null || v.id.key == null || !declarationMap.contains(v.id.family) ||
        v.name == null || v.name.trim.isEmpty || v.lower.isNaN || v.lower.isPosInfinity ||
        v.upper.exists(u => !LpExpressionData.finite(u) || u < v.lower) ||
        declarationMap.get(v.id.family).exists(d => v.category != d.category || d.domainKind == "scalar" && v.id.key != "")
    }.take(1)
    if (invalid.nonEmpty || variables.map(v => v.id -> 1).reduceByKey(_ + _).filter(_._2 > 1).take(1).nonEmpty)
      throw new LpModelException("Invalid or duplicate portable variable identity/metadata")
    if (constraints.filter(r => r.id == null || r.id.key == null).take(1).nonEmpty)
      throw new LpModelException("Invalid portable constraint identity")
    val localRows = constraints.take(maxLocalRows + 1).toVector.sortBy(r => (r.id.declaration, r.id.key))
    if (localRows.size > maxLocalRows) throw new LpModelException(s"Portable import exceeds $maxLocalRows local constraint rows")
    if (localRows.map(_.id).distinct.size != localRows.size || localRows.map(_.name).distinct.size != localRows.size)
      throw new LpModelException("Duplicate portable constraint IDs or names")
    localRows.foreach { r =>
      if (!Set("<=", ">=", "==").contains(r.sense) || r.id.key == null)
        throw new LpModelException("Invalid portable constraint sense or key")
      LpExpressionData.check(r.rhs)
    }
    val rowIds = localRows.map(_.id).toSet
    if (coefficients.filter(c => !rowIds(c.row) || !LpExpressionData.finite(c.value)).take(1).nonEmpty)
      throw new LpModelException("Missing row reference or non-finite portable coefficient")
    val model = LpProblem(name, sense)
    declarations.foreach { d =>
      val keys = variables.filter(_.id.family == d.id).map(v => v.id.key -> v.keyParts)
      val domain: DomainAccess = new EncodedDomain(keys, spark)
      model.handles += new VarSetHandle(model, d.id, d.name, d.lower, d.upper, d.category, domain)
    }
    val changes = variables.filter { v =>
      val d = declarationMap(v.id.family)
      v.lower != d.lower || v.upper != d.upper || v.name != KeyCodec.displayName(d.name, v.keyParts)
    }.take(maxLocalOverrides + 1)
    if (changes.length > maxLocalOverrides) throw new LpModelException(s"Portable import exceeds $maxLocalOverrides local metadata overrides")
    changes.foreach { v =>
      val h = model.handles(v.id.family)
      h.metadata = h.metadata.copy(members = h.metadata.members.updated(v.id.key, LpBounds(v.lower, v.upper)),
        names = h.metadata.names.updated(v.id.key, v.name))
    }
    val imported = new LpImportedModel(model, variables,
      localRows.zipWithIndex.map { case (row, i) => row.id -> LpConstraintId(i, "") }.toMap)
    imported.validateReferences(coefficients.map(c => LpCoefficient(c.variable, c.value)))
    val linear = imported.expression(objective)
    if (factors.nonEmpty || diagonal.take(1).nonEmpty) {
      val base = QpObjective.separable(imported.expression(LpAffineData(diagonal, 0.0)), linear)
      model.setObjective(factors.foldLeft(base) { case (q, factor) =>
        LpExpressionData.check(factor.weight)
        val expression = imported.expression(LpAffineData(factor.coefficients, factor.constant))
        new QpObjective(q.diagonal, q.linear, q.factors :+ (expression -> factor.weight))
      })
    } else model.setObjective(linear)
    localRows.foreach { row =>
      val id = row.id
      val affine = LpAffineData(coefficients.filter(_.row == id).map(c => LpCoefficient(c.variable, c.value)), 0.0)
      model += imported.constraint(LpConstraintData(row, affine))
    }
    imported
  }
}

object LpPortableModel {
  val SchemaVersion = 1
  def fromView(view: LpModelView): LpPortableModel = LpPortableModel(SchemaVersion, view.name, view.sense,
    view.variableDeclarations, view.variables, view.constraints, view.coefficients,
    LpAffineData(view.objectiveCoefficients, view.objectiveConstant), view.diagonalCoefficients, view.quadraticFactors)
  def variable(variable: LpVariable): LpExpandedVariable = {
    val h = variable.handle
    LpExpandedVariable(LpVariableId(h.setIndex, variable.selectedKey.getOrElse("")), variable.name,
      variable.lowerBound, variable.upperBound, h.category, variable.display)
  }
  def constraint(constraint: LpConstraint, id: LpConstraintId = LpConstraintId(0, ""))(
    implicit spark: SparkSession): LpConstraintData =
    LpConstraintData(LpExpandedConstraint(id, constraint.explicitName.getOrElse(s"_c${id.declaration}"),
      constraint.sense.symbol, constraint.rhs, Seq.empty), expression(new LpExpr(constraint.terms, 0.0)))

  def expression(expression: LpExpr)(implicit spark: SparkSession): LpAffineData =
    LpAffineData(expression.coefficients, expression.constant)
}

/** Explicit rebinding of portable identities and components into the reconstructed model. */
final class LpImportedModel private[dsl](val model: LpProblem, val variables: RDD[LpExpandedVariable],
  val constraintIds: Map[LpConstraintId, LpConstraintId]) {
  private implicit val spark: SparkSession = model.spark
  private[dsl] def validateReferences(coefficients: RDD[LpCoefficient]): Unit = {
    if (coefficients.filter(c => c.variable == null || !LpExpressionData.finite(c.value)).take(1).nonEmpty ||
      coefficients.map(_.variable).subtract(variables.map(_.id)).take(1).nonEmpty)
      throw new LpModelException("Missing variable reference or non-finite portable coefficient")
  }

  def variable(id: LpVariableId): LpVariable = {
    val metadata = variables.filter(_.id == id).take(1).headOption.getOrElse(
      throw new LpModelException(s"Missing portable variable $id"))
    new LpVariable(model.handles(id.family), if (id.key.isEmpty) None else Some(id.key), metadata.keyParts)
  }

  def expression(data: LpAffineData): LpExpr = {
    LpExpressionData.check(data.constant)
    validateReferences(data.coefficients)
    val terms = model.handles.toVector.map { handle =>
      val family = handle.setIndex
      val coefficients = data.coefficients
      WeightedCoeffTerm(handle, () => coefficients.filter(_.variable.family == family)
        .map(c => c.variable.key -> c.value).reduceByKey(_ + _), 1.0, "portable expression"): LpTerm
    }
    new LpExpr(terms, data.constant)
  }

  def constraint(data: LpConstraintData): LpConstraint = {
    val expr = expression(data.expression)
    LpExpressionData.check(data.row.rhs)
    val sense = data.row.sense match {
      case "<=" => LpSense.Le
      case ">=" => LpSense.Ge
      case "==" => LpSense.Eq
      case other => throw new LpModelException(s"Invalid portable sense '$other'")
    }
    expr.compare(sense, data.row.rhs).withName(data.row.name)
  }
}

/** Optional result data is informational and separate from the mathematical model. */
final case class LpSolutionData(status: LpStatus, objective: Option[Double],
  candidate: com.github.vbmacher.spark_lp.CandidateInfo, values: Option[RDD[LpCandidateValue]],
  residuals: Option[LpResiduals] = None)
object LpSolutionData {
  def fromSolution(solution: LpSolution): LpSolutionData =
    LpSolutionData(solution.status, if (solution.objectiveValue.isNaN) None else Some(solution.objectiveValue),
      solution.candidate, if (!solution.candidate.available) None else Some(solution.userValues.map {
        case ((family, key), value) => LpCandidateValue(LpVariableId(family, key), value)
      }), Some(solution.residuals))
}
