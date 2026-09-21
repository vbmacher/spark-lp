package com.github.vbmacher.spark_lp.dsl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

sealed trait ExportNaming

object ExportNaming {
  case object Normalized extends ExportNaming

  case object Original extends ExportNaming
}

/**
  * Name mapping for one exported variable.
  *
  * @param id stable variable identity in the expanded model.
  * @param originalName display name before export normalization.
  * @param exportedName identifier written to the target format.
  */
final case class LpExportVariable(
  id: LpVariableId,
  originalName: String,
  exportedName: String
)

/**
  * Name mapping for one exported constraint row.
  *
  * @param id stable row identity in the expanded model.
  * @param originalName display name before export normalization.
  * @param exportedName identifier written to the target format.
  */
final case class LpExportConstraint(
  id: LpConstraintId,
  originalName: String,
  exportedName: String
)

final class LpExportMapping(
  val variables: RDD[LpExportVariable],
  val constraints: RDD[LpExportConstraint]
) extends AutoCloseable {
  override def close(): Unit = {
    variables.unpersist(false); constraints.unpersist(false)
  }
}

/**
  * The persisted model RDDs plus the exported-name joins shared by every text dialect. Passed to the
  * body supplied to [[LpExport.render]] so each dialect only expresses its own line layout.
  */
private[dsl] final class ExportContext(
  val view: LpModelView,
  val variables: RDD[LpExpandedVariable],
  val constraints: RDD[LpExpandedConstraint],
  val coefficients: RDD[LpMatrixCoefficient],
  val costs: RDD[LpCoefficient],
  val names: RDD[(LpVariableId, String)],
  val rowNames: RDD[(LpConstraintId, String)],
  val sc: SparkContext)

object LpExport extends Serializable {
  private[dsl] def number(value: Double): String = {
    LpExpressionData.check(value)
    java.lang.Double.toString(value)
  }

  private val reserved = Set("minimize", "maximize", "minimum", "maximum", "subject", "to", "bounds",
    "generals", "general", "binary", "binaries", "end", "free", "inf", "infinity", "__objective")

  private[dsl] def mappings(view: LpModelView, naming: ExportNaming): LpExportMapping = {
    val variables = view.variables.sortBy(v => (v.id.family, v.id.key)).zipWithIndex().map { case (v, i) =>
      LpExportVariable(v.id, v.name, if (naming == ExportNaming.Normalized) s"v$i" else v.name)
    }.persist()
    val rows = view.constraints.sortBy(r => (r.id.declaration, r.id.key)).zipWithIndex().map { case (r, i) =>
      LpExportConstraint(r.id, r.name, if (naming == ExportNaming.Normalized) s"r$i" else r.name)
    }.persist()
    val result = new LpExportMapping(variables, rows)
    try {
      val names = variables.map(_.exportedName).union(rows.map(_.exportedName))
      if (names.filter(n => n == null || n.length > 200 || !n.matches("[A-Za-z_][A-Za-z0-9_]*") ||
        reserved(n.toLowerCase(java.util.Locale.ROOT))).take(1).nonEmpty)
        throw new LpModelException("Unsafe or reserved original export name; use Normalized naming")
      if (names.map(_ -> 1).reduceByKey(_ + _).filter(_._2 > 1).take(1).nonEmpty)
        throw new LpModelException("Colliding export names; use Normalized naming")
      variables.count();
      rows.count()
      result
    } catch {
      case scala.util.control.NonFatal(e) => result.close(); throw e
    }
  }

  private[dsl] def writeFile(path: Path, overwrite: Boolean)(write: java.io.Writer => Unit): Unit = {
    val target = path.toAbsolutePath
    if (Files.exists(target) && !overwrite) throw new LpModelException(s"Export destination already exists: $path")
    val temporary = Files.createTempFile(target.getParent, ".spark-lp-", ".tmp")
    try {
      val writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)
      try write(writer) finally writer.close()
      if (overwrite) Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
      else Files.move(temporary, target)
    } finally Files.deleteIfExists(temporary)
  }

  /**
    * Shared skeleton for the linear text dialects: reject SOS/quadratic models, build the name mapping,
    * persist the model RDDs, hand a ready [[ExportContext]] to `body`, then order the emitted
    * `((section, name, slot, tie), line)` records and stream them to `path`. Cleans up the mapping on
    * failure and always unpersists.
    */
  private[dsl] def render(view: LpModelView, path: Path, naming: ExportNaming, overwrite: Boolean, dialect: String)(
    body: ExportContext => RDD[((Int, String, Int, String), String)]): LpExportMapping = {
    if (view.sosGroups.nonEmpty) throw new LpModelException("This export dialect cannot preserve SOS groups; use JSON")
    if (view.hasQuadraticObjective) throw new LpModelException(s"$dialect export supports linear objectives only")
    view.statistics()
    val mapping = mappings(view, naming)
    val variables = view.variables.persist()
    val rows = view.constraints.persist()
    val coefficients = view.coefficients.persist()
    val costs = view.objectiveCoefficients.persist()
    try {
      val ctx = new ExportContext(view, variables, rows, coefficients, costs,
        mapping.variables.map(v => v.id -> v.exportedName),
        mapping.constraints.map(r => r.id -> r.exportedName),
        variables.sparkContext)
      val lines = body(ctx).sortBy(_._1).values
      writeFile(path, overwrite)(writer => lines.toLocalIterator.foreach { line => writer.write(line); writer.write("\n") })
      mapping
    } catch {
      case scala.util.control.NonFatal(e) => mapping.close(); throw e
    }
    finally {
      variables.unpersist(false); rows.unpersist(false); coefficients.unpersist(false); costs.unpersist(false)
    }
  }

  /** CPLEX-style LP dialect with explicit bounds, integrality, original coefficients and offset. */
  def lp(view: LpModelView, path: Path, naming: ExportNaming = ExportNaming.Normalized,
    overwrite: Boolean = false): LpExportMapping =
    render(view, path, naming, overwrite, "LP") { ctx =>
      import ctx.{view => _, _}
      type Order = (Int, String, Int, String)

      def term(value: Double, name: String): String = s" ${if (value < 0) "-" else "+"} ${number(math.abs(value))} $name"

      val header: RDD[(Order, String)] = sc.parallelize(Seq(
        ((0, "", 0, ""), if (view.sense == Minimize) "Minimize" else "Maximize"),
        ((1, "", 0, ""), s" __objective: ${number(view.objectiveConstant)}"),
        ((2, "", 0, ""), "Subject To"), ((4, "", 0, ""), "Bounds"), ((7, "", 0, ""), "End")), 1)
      val objective = costs.map(c => c.variable -> c.value).join(names).map { case (_, (value, name)) =>
        ((1, "", 1, name), term(value, name))
      }
      val rowData = constraints.map(r => r.id -> r).join(rowNames)
      val rowHeaders = rowData.map { case (_, (_, name)) => ((3, name, 0, ""), s" $name: 0") }
      val rowEnds = rowData.map { case (_, (row, name)) => ((3, name, 2, ""), s" ${LpSense.fromSymbol(row.sense).lpCode} ${number(row.rhs)}") }
      val entries = coefficients.map(c => c.variable -> (c.row, c.value)).join(names)
        .map { case (_, ((row, value), name)) => row -> (value, name) }.join(rowNames)
        .map { case (_, ((value, name), row)) => ((3, row, 1, name), term(value, name)) }
      val namedVars = variables.map(v => v.id -> v).join(names)
      val bounds = namedVars.map { case (_, (v, name)) =>
        val text = if (v.upper.contains(v.lower)) s" $name = ${number(v.lower)}"
        else if (v.lower.isNegInfinity && v.upper.isEmpty) s" $name free"
        else s" ${if (v.lower.isNegInfinity) "-inf" else number(v.lower)} <= $name" + v.upper.map(u => s" <= ${number(u)}").getOrElse("")
        ((4, name, 1, ""), text)
      }
      val general = namedVars.filter(_._2._1.category == Integer).map { case (_, (_, name)) => ((5, name, 1, ""), s" $name") }
      val binary = namedVars.filter(_._2._1.category == Binary).map { case (_, (_, name)) => ((6, name, 1, ""), s" $name") }
      val categoryHeaders: RDD[(Order, String)] = sc.parallelize(
        (if (general.take(1).nonEmpty) Seq(((5, "", 0, ""), "Generals")) else Seq.empty) ++
          (if (binary.take(1).nonEmpty) Seq(((6, "", 0, ""), "Binaries")) else Seq.empty), 1)
      sc.union(Seq(header, objective, rowHeaders, entries, rowEnds, bounds, general, binary, categoryHeaders))
    }
}
