package com.github.vbmacher.spark_lp.dsl

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import com.github.vbmacher.spark_lp.Numerics

/** Stable identity within one model: declaration index and canonical key (empty for a scalar). */
final case class LpVariableId(family: Int, key: String)
final case class LpCoefficient(variable: LpVariableId, value: Double)

private[dsl] object LpExpressionData {
  def check(value: Double): Unit =
    if (!Numerics.isFinite(value)) throw new LpModelException("Expression coefficients and constants must be finite")

  def owner(expression: LpExpr, expected: Option[LpProblem]): Option[LpProblem] = {
    val owners = expression.terms.map(_.handle.problem).distinct
    if (owners.size > 1 || expected.exists(p => owners.exists(_ ne p)))
      throw new LpModelException("Expression contains variables from a different problem")
    owners.headOption.orElse(expected)
  }

  def expand(expression: LpExpr, expected: Option[LpProblem] = None)(
    implicit spark: SparkSession): RDD[((Int, String), Double)] = {
    owner(expression, expected)
    check(expression.constant)
    val sc = spark.sparkContext
    val domains = expression.terms.map(_.handle).distinct.map { h =>
      val keys = h.domain.keyPairs().map(_._1)
      if (keys.filter(_ == null).take(1).nonEmpty ||
        keys.map(_ -> 1).reduceByKey(_ + _).filter(_._2 > 1).take(1).nonEmpty)
        throw new LpModelException(s"Variable '${h.name}': null or duplicate domain keys")
      h -> keys
    }.toMap
    def term(t: LpTerm): RDD[((Int, String), Double)] = {
      val h = t.handle
      val si = h.setIndex
      val pairs: RDD[(String, Double)] = t match {
        case ConstCoeffTerm(_, c) => check(c); domains(h).map(_ -> c)
        case KeyCoeffTerm(_, key, c) =>
          check(c)
          if (domains(h).filter(_ == key).take(1).isEmpty)
            throw new LpModelException(s"Variable '${h.name}': missing or incompatible key '$key'")
          sc.parallelize(Seq(key -> c), 1)
        case ColumnCoeffTerm(_, column, scale) =>
          check(scale); h.domain.columnPairs(column, "expression inspection").mapValues(_ * scale)
        case WeightedCoeffTerm(_, weights, scale, description) =>
          check(scale)
          val w = weights()
          if (w.keys.filter(_ == null).take(1).nonEmpty ||
            w.mapValues(_ => 1).reduceByKey(_ + _).filter(_._2 > 1).take(1).nonEmpty ||
            w.keys.subtract(domains(h)).take(1).nonEmpty)
            throw new LpModelException(s"$description: null, duplicate or foreign keys")
          w.mapValues(_ * scale)
        case FilteredCoeffTerm(inner, excluded) =>
          return term(inner).filter { case ((_, key), _) => !excluded(key) }
      }
      if (pairs.values.filter(v => !Numerics.isFinite(v)).take(1).nonEmpty)
        throw new LpModelException(s"Variable '${h.name}': non-finite expression coefficient")
      pairs.map { case (key, c) => ((si, key), c) }
    }
    val pieces = expression.terms.map(term)
    val result = if (pieces.isEmpty) sc.emptyRDD[((Int, String), Double)] else sc.union(pieces).reduceByKey(_ + _)
    if (result.values.filter(v => !Numerics.isFinite(v)).take(1).nonEmpty)
      throw new LpModelException("Repeated expression coefficients overflow")
    result.filter(_._2 != 0.0)
  }
}
