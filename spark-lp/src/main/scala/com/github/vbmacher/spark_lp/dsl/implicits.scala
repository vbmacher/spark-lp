package com.github.vbmacher.spark_lp.dsl

import org.apache.spark.sql.{Column, DataFrame, Dataset}

import scala.language.implicitConversions

/**
  * Arithmetic and constraint operators for spark-lp model expressions.
  *
  * Import `com.github.vbmacher.spark_lp.dsl.implicits._` before building expressions. Equality
  * constraints use `===`; Scala's `==` returns a Boolean and is not a modelling operator. The
  * import does not add methods to Spark `Column` or comparison methods to `Double`.
  */
object implicits {

  implicit final class VariableOps(private val x: LpVariable) extends AnyVal {
    def *(coefficient: Double): LpExpr = x.toExpr(coefficient)
    def /(divisor: Double): LpExpr = x.toExpr(LpArithmetic.reciprocal(divisor))
    def unary_- : LpExpr = x.toExpr(-1.0)
    def +(other: LpExpr): LpExpr = x.toExpr(1.0).plus(other)
    def -(other: LpExpr): LpExpr = x.toExpr(1.0).plus(other.scaledBy(-1.0))
    def +(constant: Double): LpExpr = x.toExpr(1.0).plusConstant(constant)
    def -(constant: Double): LpExpr = x.toExpr(1.0).plusConstant(-constant)
    def <=(rhs: Double): LpConstraint = x.toExpr(1.0).compare(LpSense.Le, rhs)
    def >=(rhs: Double): LpConstraint = x.toExpr(1.0).compare(LpSense.Ge, rhs)
    def ===(rhs: Double): LpConstraint = x.toExpr(1.0).compare(LpSense.Eq, rhs)
    def <=(rhs: LpExpr): LpConstraint = x.toExpr(1.0).compare(LpSense.Le, rhs)
    def >=(rhs: LpExpr): LpConstraint = x.toExpr(1.0).compare(LpSense.Ge, rhs)
    def ===(rhs: LpExpr): LpConstraint = x.toExpr(1.0).compare(LpSense.Eq, rhs)
  }

  implicit final class VariableSetOps[K](private val x: LpVariableSet[K]) {

    def *(coefficient: Double): LpExpr = x.handle.toExpr(coefficient)

    /** Weights every family member by a numeric column from its declaration DataFrame. */
    def *(coefficient: Column): LpExpr =
      x.sum(coefficient)

    def unary_- : LpExpr = x.handle.toExpr(-1.0)

    /**
      * Weights family members with a function evaluated over a typed Dataset.
      *
      * `source` must use the same key function as the variable family's declaration. A source key
      * absent from the family or duplicated in `source` is an error. A family key absent from
      * `source` contributes zero.
      */
    def weightedBy(source: Dataset[K])(coefficient: K => Double): LpExpr = {
      val builder = x.weightsBuilder
      val term = WeightedCoeffTerm(
        handle = x.handle,
        weights = () => builder(source, coefficient),
        scale = 1.0,
        description = s"weightedBy source of variable set '${x.name}'")
      new LpExpr(Vector(term), 0.0)
    }
  }

  implicit final class ExprOps(private val expression: LpExpr) extends AnyVal {
    def +(other: LpExpr): LpExpr = expression.plus(other)
    def -(other: LpExpr): LpExpr = expression.plus(other.scaledBy(-1.0))
    def +(constant: Double): LpExpr = expression.plusConstant(constant)
    def -(constant: Double): LpExpr = expression.plusConstant(-constant)
    def *(scale: Double): LpExpr = expression.scaledBy(scale)
    def /(divisor: Double): LpExpr = expression.scaledBy(LpArithmetic.reciprocal(divisor))
    def unary_- : LpExpr = expression.scaledBy(-1.0)
    def <=(rhs: Double): LpConstraint = expression.compare(LpSense.Le, rhs)
    def >=(rhs: Double): LpConstraint = expression.compare(LpSense.Ge, rhs)
    def ===(rhs: Double): LpConstraint = expression.compare(LpSense.Eq, rhs)
    def <=(rhs: LpExpr): LpConstraint = expression.compare(LpSense.Le, rhs)
    def >=(rhs: LpExpr): LpConstraint = expression.compare(LpSense.Ge, rhs)
    def ===(rhs: LpExpr): LpConstraint = expression.compare(LpSense.Eq, rhs)
  }

  /** Supports a numeric literal on the left, such as `3.0 * x` or `5.0 - expression`. */
  implicit final class DoubleLpOps(private val value: Double) extends AnyVal {
    def *(x: LpVariable): LpExpr = x.toExpr(value)
    def *[K](x: LpVariableSet[K]): LpExpr = x.handle.toExpr(value)
    def *(expression: LpExpr): LpExpr = expression.scaledBy(value)
    def +(expression: LpExpr): LpExpr = expression.plusConstant(value)
    def -(expression: LpExpr): LpExpr = expression.scaledBy(-1.0).plusConstant(value)
  }

  implicit final class ConstraintOps(private val constraint: LpConstraint) extends AnyVal {
    def named(name: String): LpConstraint = constraint.withName(name)
  }

  implicit final class ConstraintSetOps(private val constraints: LpConstraintSet) extends AnyVal {
    def named(name: String): LpConstraintSet = constraints.withName(name)
  }

  implicit final class GroupedExprOps(private val grouped: GroupedLpExpr) extends AnyVal {

    /**
      * Creates one `<=` constraint per group.
      *
      * `rhs` must contain every grouping column and exactly one numeric `rhs` value per group.
      */
    def <=(rhs: DataFrame): LpConstraintSet = new LpConstraintSet(grouped, LpSense.Le, Right(rhs), None)
    def >=(rhs: DataFrame): LpConstraintSet = new LpConstraintSet(grouped, LpSense.Ge, Right(rhs), None)
    def ===(rhs: DataFrame): LpConstraintSet = new LpConstraintSet(grouped, LpSense.Eq, Right(rhs), None)

    /** Creates one `<=` constraint per group using the same right-hand side for every group. */
    def <=(rhs: Double): LpConstraintSet = new LpConstraintSet(grouped, LpSense.Le, Left(rhs), None)
    def >=(rhs: Double): LpConstraintSet = new LpConstraintSet(grouped, LpSense.Ge, Left(rhs), None)
    def ===(rhs: Double): LpConstraintSet = new LpConstraintSet(grouped, LpSense.Eq, Left(rhs), None)
  }

  /** Converts one variable to a coefficient-one expression when an [[LpExpr]] is required. */
  implicit def variableToExpr(variable: LpVariable): LpExpr = variable.toExpr(1.0)

  /** Converts a variable family to the coefficient-one sum of all its members. */
  implicit def variableSetToExpr[K](variables: LpVariableSet[K]): LpExpr = variables.handle.toExpr(1.0)
}
