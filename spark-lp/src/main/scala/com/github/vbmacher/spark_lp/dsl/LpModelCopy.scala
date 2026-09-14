package com.github.vbmacher.spark_lp.dsl

/** Independent declarations sharing immutable, lazy Spark sources with the source model. */
final class LpModelCopy private[dsl](source: LpProblem, val model: LpProblem) {
  private val mapping: Map[VarSetHandle, VarSetHandle] = source.handles.iterator.map { handle =>
    val copied = new VarSetHandle(model, handle.setIndex, handle.name, handle.lowerBound,
      handle.upperBound, handle.category, handle.domain)
    model.handles += copied
    handle -> copied
  }.toMap

  private def handle(original: VarSetHandle): VarSetHandle = mapping.getOrElse(original,
    throw new LpModelException("Variable is not part of the copied source model"))

  private def term(original: LpTerm): LpTerm = original match {
    case t: ConstCoeffTerm => t.copy(handle = handle(t.handle))
    case t: ColumnCoeffTerm => t.copy(handle = handle(t.handle))
    case t: WeightedCoeffTerm => t.copy(handle = handle(t.handle))
  }

  def variable(original: LpVariable): LpVariable = new LpVariable(handle(original.handle))

  def variables[K](original: LpVariableSet[K]): LpVariableSet[K] =
    new LpVariableSet[K](handle(original.handle), original.weightsBuilder, original.keyColumn)

  def expression(original: LpExpr): LpExpr =
    new LpExpr(original.terms.map(term), original.constant)

  def constraint(original: LpConstraint): LpConstraint =
    new LpConstraint(original.terms.map(term), original.sense, original.rhs, original.explicitName)

  def constraints(original: LpConstraintSet): LpConstraintSet = {
    val t = original.grouped.terms
    val terms = new LpTerms(handle(t.handle), t.key, t.source, t.by, t.coefficient)
    new LpConstraintSet(new GroupedLpExpr(terms, original.grouped.by), original.sense,
      original.rhs, original.explicitName)
  }

  def objective(original: QpObjective): QpObjective =
    new QpObjective(expression(original.diagonal), expression(original.linear),
      original.factors.map { case (e, weight) => expression(e) -> weight })

  model.objective = source.objective.map(expression)
  model.quadratic = source.quadratic.map(objective)
  source.constraints.foreach {
    case Left(c) => model.constraints += Left(constraint(c))
    case Right(c) => model.constraints += Right(constraints(c))
  }
}
