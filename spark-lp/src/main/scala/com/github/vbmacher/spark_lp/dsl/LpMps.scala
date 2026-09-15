package com.github.vbmacher.spark_lp.dsl

import java.nio.file.Path
import org.apache.spark.rdd.RDD

/** Free MPS with OBJSENSE, a negative RHS objective offset, and explicit bounds. */
object LpMps extends Serializable {
  def write(view: LpModelView, path: Path, naming: ExportNaming = ExportNaming.Normalized,
    relaxIntegrality: Boolean = false, overwrite: Boolean = false): LpExportMapping = {
    if (view.sosGroups.nonEmpty) throw new LpModelException("This export dialect cannot preserve SOS groups; use JSON")
    if (view.hasQuadraticObjective) throw new LpModelException("MPS export supports linear objectives only")
    view.statistics()
    val mapping = LpExport.mappings(view, naming)
    val variables = view.variables.persist()
    val rows = view.constraints.persist()
    val coefficients = view.coefficients.persist()
    val costs = view.objectiveCoefficients.persist()
    type Order = (Int, String, Int, String)
    try {
      val sc = variables.sparkContext
      val names = mapping.variables.map(v => v.id -> v.exportedName)
      val rowNames = mapping.constraints.map(r => r.id -> r.exportedName)
      val namedVars = variables.map(v => v.id -> v).join(names)
      val namedRows = rows.map(r => r.id -> r).join(rowNames)
      val header: RDD[(Order, String)] = sc.parallelize(Seq(
        ((0, "", 0, ""), "NAME          SPARKLP"),
        ((0, "", 1, ""), "OBJSENSE"),
        ((0, "", 2, ""), if (view.sense == Maximize) " MAX" else " MIN"),
        ((1, "", 0, ""), "ROWS"), ((1, "", 1, ""), " N  __objective"),
        ((2, "", 0, ""), "COLUMNS"), ((3, "", 0, ""), "RHS"),
        ((3, "", 1, ""), s" RHS1  __objective  ${LpExport.number(-view.objectiveConstant)}"),
        ((4, "", 0, ""), "BOUNDS"), ((5, "", 0, ""), "ENDATA")), 1)
      val rowLines = namedRows.map { case (_, (r, name)) =>
        val sense = r.sense match { case "==" => "E"; case "<=" => "L"; case ">=" => "G" }
        ((1, name, 2, ""), s" $sense  $name")
      }
      // A zero objective record declares even unused columns. Markers wrap each integer column.
      val objective = namedVars.leftOuterJoin(costs.map(c => c.variable -> c.value)).flatMap {
        case (_, ((v, name), cost)) =>
          val line = ((2, name, 1, ""), s" $name  __objective  ${LpExport.number(cost.getOrElse(0.0))}")
          if (relaxIntegrality || v.category == Continuous) Seq(line)
          else Seq(((2, name, 0, ""), s" M_${name}_s  'MARKER'  'INTORG'"), line,
            ((2, name, 3, ""), s" M_${name}_e  'MARKER'  'INTEND'"))
      }
      val matrix = coefficients.map(c => (c.variable, (c.row, c.value))).join(names)
        .map { case (_, ((row, value), name)) => (row, (name, value)) }.join(rowNames)
        .map { case (_, ((name, value), row)) => ((2, name, 2, row), s" $name  $row  ${LpExport.number(value)}") }
      val rhs = namedRows.map { case (_, (r, name)) => ((3, name, 2, ""), s" RHS1  $name  ${LpExport.number(r.rhs)}") }
      val bounds = namedVars.flatMap { case (_, (v, name)) =>
        val lower = if (v.category == Binary) math.max(0.0, v.lower) else v.lower
        val upper = if (v.category == Binary) Some(math.min(1.0, v.upper.getOrElse(1.0))) else v.upper
        def record(kind: String, value: Option[Double] = None): String =
          s" $kind  BND1  $name" + value.map(n => s"  ${LpExport.number(n)}").getOrElse("")
        val entries = if (upper.contains(lower)) Vector(record("FX", Some(lower)))
          else if (lower.isNegInfinity && upper.isEmpty) Vector(record("FR"))
          else Vector(if (lower.isNegInfinity) record("MI") else record("LO", Some(lower))) ++
            Vector(upper.map(u => record("UP", Some(u))).getOrElse(record("PL")))
        val binary = if (v.category == Binary && !relaxIntegrality) Vector(record("BV")) else Vector.empty
        (binary ++ entries).zipWithIndex.map { case (line, index) => ((4, name, index + 1, ""), line) }
      }
      val lines = sc.union(Seq(header, rowLines, objective, matrix, rhs, bounds)).sortBy(_._1).values
      LpExport.writeFile(path, overwrite)(writer => lines.toLocalIterator.foreach { line => writer.write(line); writer.write("\n") })
      mapping
    } catch { case scala.util.control.NonFatal(e) => mapping.close(); throw e }
    finally { variables.unpersist(false); rows.unpersist(false); coefficients.unpersist(false); costs.unpersist(false) }
  }
}
