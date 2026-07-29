package com.github.vbmacher.spark_lp.dsl

import org.apache.spark.sql.{Column, DataFrame, Dataset}

import scala.language.implicitConversions

/**
  * Operators of the modelling DSL. Users opt in with
  * `import com.github.vbmacher.spark_lp.dsl.implicits._`; the base Spark namespace is not polluted
  * (no enrichment of `Column`, no comparison enrichment of `Double`).
  *
  * Equality uses `===` rather than `==`: a stray `expr == 100.0` yields a `Boolean`, which
  * `LpProblem.+=` does not accept, so the mistake fails at compile time.
  */
object implicits {

  implicit final class VariableOps(private val x: LpVariable) extends AnyVal {
    def *(coefficient: Double): LpExpr = x.handle.toExpr(coefficient)
    def unary_- : LpExpr = x.handle.toExpr(-1.0)
    def +(other: LpExpr): LpExpr = x.handle.toExpr(1.0).plus(other)
    def -(other: LpExpr): LpExpr = x.handle.toExpr(1.0).plus(other.scaledBy(-1.0))
    def +(constant: Double): LpExpr = x.handle.toExpr(1.0).plusConstant(constant)
    def -(constant: Double): LpExpr = x.handle.toExpr(1.0).plusConstant(-constant)
    def <=(rhs: Double): LpConstraint = x.handle.toExpr(1.0).compare(LpSense.Le, rhs)
    def >=(rhs: Double): LpConstraint = x.handle.toExpr(1.0).compare(LpSense.Ge, rhs)
    def ===(rhs: Double): LpConstraint = x.handle.toExpr(1.0).compare(LpSense.Eq, rhs)
    def <=(rhs: LpExpr): LpConstraint = x.handle.toExpr(1.0).compare(LpSense.Le, rhs)
    def >=(rhs: LpExpr): LpConstraint = x.handle.toExpr(1.0).compare(LpSense.Ge, rhs)
    def ===(rhs: LpExpr): LpConstraint = x.handle.toExpr(1.0).compare(LpSense.Eq, rhs)
  }

  implicit final class VariableSetOps[K](private val x: LpVariableSet[K]) {

    def *(coefficient: Double): LpExpr = x.handle.toExpr(coefficient)

    /** Coefficient held in a Spark column, resolved against the set's own domain. */
    def *(coefficient: Column): LpExpr =
      new LpExpr(Vector(ColumnCoeffTerm(x.handle, coefficient, 1.0)), 0.0)

    def unary_- : LpExpr = x.handle.toExpr(-1.0)

    /**
      * Distributed coefficients computed from a source keyed like the set's domain. A key present
      * in `source` but absent from the domain is an error; a domain key absent from `source`
      * contributes a zero coefficient; duplicate keys in `source` are an error.
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
    def unary_- : LpExpr = expression.scaledBy(-1.0)
    def <=(rhs: Double): LpConstraint = expression.compare(LpSense.Le, rhs)
    def >=(rhs: Double): LpConstraint = expression.compare(LpSense.Ge, rhs)
    def ===(rhs: Double): LpConstraint = expression.compare(LpSense.Eq, rhs)
    def <=(rhs: LpExpr): LpConstraint = expression.compare(LpSense.Le, rhs)
    def >=(rhs: LpExpr): LpConstraint = expression.compare(LpSense.Ge, rhs)
    def ===(rhs: LpExpr): LpConstraint = expression.compare(LpSense.Eq, rhs)
  }

  /** Puts numeric literals on the left of arithmetic, PuLP-style: `3.0 * x`, `5.0 - expr`. */
  implicit final class DoubleLpOps(private val value: Double) extends AnyVal {
    def *(x: LpVariable): LpExpr = x.handle.toExpr(value)
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

    /** RHS frame: grouping columns + a `rhs` column, one row per group key. */
    def <=(rhs: DataFrame): LpConstraintSet = new LpConstraintSet(grouped, LpSense.Le, Right(rhs), None)
    def >=(rhs: DataFrame): LpConstraintSet = new LpConstraintSet(grouped, LpSense.Ge, Right(rhs), None)
    def ===(rhs: DataFrame): LpConstraintSet = new LpConstraintSet(grouped, LpSense.Eq, Right(rhs), None)

    /** The same scalar bound for every group. */
    def <=(rhs: Double): LpConstraintSet = new LpConstraintSet(grouped, LpSense.Le, Left(rhs), None)
    def >=(rhs: Double): LpConstraintSet = new LpConstraintSet(grouped, LpSense.Ge, Left(rhs), None)
    def ===(rhs: Double): LpConstraintSet = new LpConstraintSet(grouped, LpSense.Eq, Left(rhs), None)
  }

  /** Allows a variable wherever an expression argument is expected (e.g. `expr + x`). */
  implicit def variableToExpr(variable: LpVariable): LpExpr = variable.handle.toExpr(1.0)

  /** Allows a variable set wherever an expression argument is expected. */
  implicit def variableSetToExpr[K](variables: LpVariableSet[K]): LpExpr = variables.handle.toExpr(1.0)
}
