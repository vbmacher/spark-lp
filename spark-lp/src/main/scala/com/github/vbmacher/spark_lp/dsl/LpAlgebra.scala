package com.github.vbmacher.spark_lp.dsl

import java.io.Writer
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession

/** Explicit human-readable algebra; no implicit toString evaluation and no file-dialect guarantee. */
object LpAlgebra {
  private type Order = (Int, Int, String, Int, Int, String)

  private def number(value: Double): String = java.lang.Double.toString(value)

  private def quoted(value: String): String = "`" + value.replace("\\", "\\\\").replace("`", "\\`")
    .replace("\n", "\\n").replace("\r", "\\r") + "`"

  private def term(value: Double, name: String): String =
    s"  ${if (value < 0.0) "-" else "+"} ${number(math.abs(value))} * ${quoted(name)}"

  private def lines(view: LpModelView): RDD[(Order, String)] = {
    val variables = view.variables
    val sc = variables.sparkContext
    val names = variables.map(v => v.id -> v.name)

    def terms(coefficients: RDD[LpCoefficient], phase: Int, group: Int, suffix: String = ""): RDD[(Order, String)] =
      coefficients.map(c => c.variable -> c.value).join(names).map { case (id, (value, name)) =>
        ((phase, group, "", 1, id.family, id.key), term(value, name) + suffix)
      }

    val header: RDD[(Order, String)] = sc.parallelize(Seq(
      ((0, 0, "", 0, 0, ""), s"model ${quoted(view.name)}: ${view.sense}"),
      ((1, 0, "", 0, 0, ""), "objective:"),
      ((1, 0, "", 2, 0, ""), s"  + constant ${number(view.objectiveConstant)}")), 1)
    val rows = view.constraints
    val rowHeaders = rows.map(r => ((2, r.id.declaration, r.id.key, 0, 0, ""), s"constraint ${quoted(r.name)}:"))
    val rowEnds = rows.map(r => ((2, r.id.declaration, r.id.key, 2, 0, ""), s"  ${r.sense} ${number(r.rhs)}"))
    val matrix = view.coefficients.map(c => c.variable -> (c.row, c.value)).join(names).map {
      case (id, ((row, value), name)) => ((2, row.declaration, row.key, 1, id.family, id.key), term(value, name))
    }
    val bounds = variables.map { v =>
      val lo = if (v.lower.isNegInfinity) "-inf" else number(v.lower)
      val hi = v.upper.map(number).getOrElse("+inf")
      ((3, v.id.family, v.id.key, 0, 0, ""), s"$lo <= ${quoted(v.name)} <= $hi [${v.category}]")
    }
    val diagonal = terms(view.diagonalCoefficients.map(c => c.copy(value = 0.5 * c.value)), 1, 1, "^2")
    val factors = view.quadraticFactors.flatMap { f =>
      Vector(sc.parallelize(Seq(
        ((1, f.index + 2, "", 0, 0, ""), s"  + ${number(f.weight)} * ("),
        ((1, f.index + 2, "", 2, 0, ""), s"  + ${number(f.constant)} )^2")), 1), terms(f.coefficients, 1, f.index + 2))
    }
    val sosHeaders: RDD[(Order, String)] = sc.parallelize(view.sosGroups.zipWithIndex.map { case (group, i) =>
      ((4, i, "", 0, 0, ""), s"${group.kind} ${quoted(group.name)} (weight order):")
    })
    val sosMembers = sc.parallelize(view.sosGroups.zipWithIndex.flatMap { case (group, gi) =>
      group.members.zipWithIndex.map { case (m, i) => m.variable -> (gi, i, m.weight) }
    }).join(names).map { case (_, ((group, index, weight), name)) =>
      ((4, group, "", 1, index, ""), s"  ${quoted(name)} : ${number(weight)}")
    }
    sc.union(Vector(sosHeaders, sosMembers, header, terms(view.objectiveCoefficients, 1, 0), diagonal, rowHeaders, matrix, rowEnds, bounds) ++ factors)
  }

  /** Exact truncation count; distributed evaluation and sorting are explicit Spark actions. */
  def preview(view: LpModelView, maxLines: Int = 80): String = {
    require(maxLines >= 0 && maxLines < Int.MaxValue, "maxLines must be nonnegative and below Int.MaxValue")
    val rendered = lines(view)
    val total = rendered.count()
    val selected = rendered.takeOrdered(maxLines)(Ordering.by[(Order, String), Order](_._1))
    selected.map(_._2).mkString("\n") +
      (if (total > maxLines) s"\n... TRUNCATED: ${total - maxLines} more lines ..." else "")
  }

  /** Complete output streams sorted partitions to a caller-owned writer; the writer is not closed. */
  def write(view: LpModelView, writer: Writer): Unit =
    lines(view).sortBy(_._1).values.toLocalIterator.foreach { line => writer.write(line); writer.write("\n") }

  def expression(expression: LpExpr, maxTerms: Int = 40)(implicit spark: SparkSession): String = {
    require(maxTerms >= 0, "maxTerms must be nonnegative")
    val coefficients = expression.coefficients
    val total = coefficients.count()
    val selected = coefficients.takeOrdered(maxTerms)(Ordering.by(c => (c.variable.family, c.variable.key)))
    val owner = LpExpressionData.owner(expression, None)
    val names = owner.map(_.inspect.variables.map(v => v.id -> v.name)).getOrElse(spark.sparkContext.emptyRDD[(LpVariableId, String)])
    val parts = spark.sparkContext.parallelize(selected.toSeq).map(c => c.variable -> c.value).join(names).collect()
      .sortBy { case (id, _) => (id.family, id.key) }.map { case (_, (value, name)) => term(value, name).trim }
    (parts.toSeq :+ s"+ ${number(expression.constant)}").mkString(" ") +
      (if (total > maxTerms) s" ... TRUNCATED: ${total - maxTerms} terms ..." else "")
  }

  def constraint(constraint: LpConstraint, maxTerms: Int = 40)(implicit spark: SparkSession): String =
    quoted(constraint.explicitName.getOrElse("unnamed")) + ": " +
      expression(new LpExpr(constraint.terms, 0.0), maxTerms) + s" ${constraint.sense.symbol} ${number(constraint.rhs)}"
}
