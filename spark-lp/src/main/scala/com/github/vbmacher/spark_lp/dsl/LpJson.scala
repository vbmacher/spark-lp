package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.Numerics
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import org.apache.hadoop.fs.{FileAlreadyExistsException, FileContext, Options, Path}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import scala.collection.JavaConverters._

/**
  * Model and optional result metadata read from or written to an [[LpJson]] directory.
  *
  * Imported solution fields are informational; validate candidate values independently before
  * treating them as feasible or optimal.
  *
  * @param model portable mathematical model reconstructed from the directory.
  * @param solution optional serialized result metadata and candidate values.
  * @param source input directory recorded for diagnostics.
  * @param solutionMetadataVerified true when stored summary fields were verified against their
  *                                 serialized candidate values while reading.
  */
final case class LpJsonDocument(
  model: LpPortableModel,
  solution: Option[LpSolutionData],
  source: String,
  solutionMetadataVerified: Boolean = false
)

/**
  * Reads and writes spark-lp's versioned JSON-lines directory format.
  *
  * Variables, constraints and coefficients remain partitioned Spark data rather than one
  * driver-local JSON document.
  */
object LpJson {
  private val mapper = new ObjectMapper()
  private val factory = mapper.getNodeFactory

  private def obj(fields: (String, JsonNode)*): ObjectNode = {
    val result = mapper.createObjectNode()
    fields.foreach { case (key, value) => result.replace(key, value) }
    result
  }

  private def str(value: String): JsonNode = factory.textNode(value)

  private def num(value: Double): JsonNode = {
    if (value.isNaN) factory.nullNode()
    else if (value.isPosInfinity) str("positiveInfinity")
    else if (value.isNegInfinity) str("negativeInfinity")
    else factory.numberNode(value)
  }

  private def int(value: Int): JsonNode = factory.numberNode(value)

  private def bool(value: Boolean): JsonNode = factory.booleanNode(value)

  private def optional(value: Option[Double]): JsonNode = value.map(num).getOrElse(factory.nullNode())

  private def array(values: Iterable[JsonNode]): JsonNode = {
    val node = mapper.createArrayNode();
    values.foreach(node.add);
    node
  }

  private def id(value: LpVariableId): JsonNode = obj("family" -> int(value.family), "key" -> str(value.key))

  private def rowId(value: LpConstraintId): JsonNode = obj("declaration" -> int(value.declaration), "key" -> str(value.key))

  private def coefficient(value: LpCoefficient): JsonNode = obj("variable" -> id(value.variable), "value" -> num(value.value))

  private def variable(value: LpExpandedVariable): JsonNode = obj("id" -> id(value.id), "name" -> str(value.name),
    "lower" -> num(value.lower), "upper" -> optional(value.upper), "category" -> str(value.category.toString),
    "keyParts" -> array(value.keyParts.map(str)))

  private def row(value: LpExpandedConstraint): JsonNode = obj("id" -> rowId(value.id), "name" -> str(value.name),
    "sense" -> str(value.sense), "rhs" -> num(value.rhs), "group" -> array(value.group.map(str)))

  private def field(node: JsonNode, key: String): JsonNode = {
    if (!node.has(key)) throw new LpModelException(s"JSON field '$key' is missing")
    node.get(key)
  }

  private def text(node: JsonNode): String = {
    if (!node.isTextual) throw new LpModelException("Expected a JSON string")
    node.textValue()
  }

  private def number(node: JsonNode): Double = {
    if (!node.isNumber || !Numerics.isFinite(node.doubleValue()))
      throw new LpModelException("Expected a finite JSON number")
    node.doubleValue()
  }

  private def integer(node: JsonNode): Int = {
    if (!node.isIntegralNumber || !node.canConvertToInt) throw new LpModelException("Expected a JSON integer")
    node.intValue()
  }

  private def boolean(node: JsonNode): Boolean = {
    if (!node.isBoolean) throw new LpModelException("Expected a JSON boolean")
    node.booleanValue()
  }

  private def elements(node: JsonNode): Vector[JsonNode] = {
    if (!node.isArray) throw new LpModelException("Expected a JSON array")
    node.elements().asScala.toVector
  }

  private def lower(node: JsonNode): Double =
    if (node.isTextual && node.textValue() == "negativeInfinity") Double.NegativeInfinity else number(node)

  private def upper(node: JsonNode): Option[Double] = if (node.isNull) None else Some(number(node))

  private def resultNumber(node: JsonNode): Option[Double] =
    if (node.isNull) None else if (node.isTextual) text(node) match {
      case "positiveInfinity" => Some(Double.PositiveInfinity)
      case "negativeInfinity" => Some(Double.NegativeInfinity)
      case other => throw new LpModelException(s"Invalid non-finite JSON number '$other'")
    } else Some(number(node))

  private def category(node: JsonNode): VariableCategory = text(node) match {
    case "Continuous" => Continuous
    case "Integer" => Integer
    case "Binary" => Binary
    case other => throw new LpModelException(s"Invalid JSON variable category '$other'")
  }

  private def variableId(node: JsonNode): LpVariableId = LpVariableId(integer(field(node, "family")), text(field(node, "key")))

  private def constraintId(node: JsonNode): LpConstraintId = LpConstraintId(integer(field(node, "declaration")), text(field(node, "key")))

  private def readCoefficient(node: JsonNode): LpCoefficient =
    LpCoefficient(variableId(field(node, "variable")), number(field(node, "value")))

  def write(model: LpProblem, destination: String, solution: Option[LpSolutionData] = None,
    overwrite: Boolean = false): Unit = {
    implicit val spark: SparkSession = model.spark
    val view = model.inspect
    view.statistics()
    val data = LpPortableModel.fromView(view)
    val target = new Path(destination)
    val configuration = spark.sparkContext.hadoopConfiguration
    val fs = target.getFileSystem(configuration)
    if (fs.exists(target) && !overwrite) throw new LpModelException(s"JSON destination already exists: $destination")
    val temporary = new Path(target.toString + ".tmp-" + java.util.UUID.randomUUID().toString)

    def save[A](values: RDD[A], name: String)(encode: A => JsonNode): Unit =
      values.map(v => encode(v).toString).saveAsTextFile(new Path(temporary, name).toString)

    try {
      val declarations = data.declarations.map(d => obj("id" -> int(d.id), "name" -> str(d.name),
        "lower" -> num(d.lower), "upper" -> optional(d.upper), "category" -> str(d.category.toString),
        "domainKind" -> str(d.domainKind)))
      val result: Option[JsonNode] = solution.map(s => obj("status" -> str(s.status.toString), "objective" -> optional(s.objective),
        "available" -> bool(s.candidate.available), "feasible" -> bool(s.candidate.feasible),
        "iteration" -> s.candidate.iteration.map(int).getOrElse(factory.nullNode()),
        "hasValues" -> bool(s.values.nonEmpty),
        "residuals" -> s.residuals.map(r => array(Seq(num(r.primal), num(r.dual), num(r.gap)))).getOrElse(factory.nullNode()),
        "provenance" -> str("exported solver metadata")))
      val header = obj("schemaVersion" -> int(1), "name" -> str(data.name), "sense" -> str(data.sense.toString),
        "objectiveConstant" -> num(data.objective.constant), "declarations" -> array(declarations),
        "factors" -> array(data.factors.map(f => obj("index" -> int(f.index), "weight" -> num(f.weight), "constant" -> num(f.constant)))),
        "sosGroups" -> array(data.sosGroups.map(g => obj("name" -> str(g.name), "kind" -> str(g.kind.toString),
          "members" -> array(g.members.map(m => obj("variable" -> id(m.variable), "weight" -> num(m.weight))))))),
        "solution" -> result.getOrElse(factory.nullNode()))
      spark.sparkContext.parallelize(Seq(header.toString), 1).saveAsTextFile(new Path(temporary, "header").toString)
      save(data.variables, "variables")(variable)
      save(data.constraints, "constraints")(row)
      save(data.coefficients, "coefficients")(c => obj("row" -> rowId(c.row), "variable" -> id(c.variable), "value" -> num(c.value)))
      save(data.objective.coefficients, "objective")(coefficient)
      save(data.diagonal, "diagonal")(coefficient)
      data.factors.foreach(f => save(f.coefficients, s"factor-${f.index}")(coefficient))
      solution.flatMap(_.values).foreach(values => save(values, "solution-values")(v =>
        obj("variable" -> id(v.variable), "value" -> num(v.value))))
      val context = FileContext.getFileContext(fs.getUri, configuration)
      var backup: Option[Path] = None

      def restoreBackup(): String = {
        backup.foreach { path =>
          if (!fs.exists(target)) try context.rename(path, target, Options.Rename.NONE)
          catch {
            case _: java.io.IOException => ()
          }
        }
        backup.filter(fs.exists).map(path => s"; previous destination retained at $path").getOrElse("")
      }

      try {
        if (overwrite && fs.exists(target)) {
          val path = new Path(target.toString + ".backup-" + java.util.UUID.randomUUID().toString)
          context.rename(target, path, Options.Rename.NONE)
          backup = Some(path)
        }
        context.rename(temporary, target, Options.Rename.NONE)
        backup.foreach(fs.delete(_, true))
      }
      catch {
        case _: FileAlreadyExistsException =>
          val retained = restoreBackup()
          if (!overwrite) throw new LpModelException(s"JSON destination already exists: $destination")
          throw new LpModelException("Cannot publish completed JSON directory" + retained)
        case _: java.io.IOException =>
          throw new LpModelException("Cannot publish completed JSON directory" + restoreBackup())
      }
    } finally {
      if (fs.exists(temporary)) fs.delete(temporary, true)
    }
  }

  /**
    * Reads a JSON model directory as distributed data.
    *
    * Call `document.model.toProblem()` to validate identities and construct an editable
    * [[LpProblem]].
    */
  def read(source: String)(implicit spark: SparkSession): LpJsonDocument = {
    def records(name: String): RDD[JsonNode] =
      spark.read.textFile(new Path(source, name).toString).rdd.map(line => mapper.readTree(line))

    // Parse the bounded header on the driver; older Jackson nodes are not serializable.
    val headers = spark.read.textFile(new Path(source, "header").toString).take(2)
    if (headers.length != 1) throw new LpModelException("JSON requires exactly one header record")
    val h = mapper.readTree(headers.head)
    if (integer(field(h, "schemaVersion")) != 1) throw new LpModelException("Unsupported JSON model schema")
    val sense = text(field(h, "sense")) match {
      case "Minimize" => Minimize
      case "Maximize" => Maximize
      case other => throw new LpModelException(s"Invalid JSON objective sense '$other'")
    }
    val declarations = elements(field(h, "declarations")).map(d => LpVariableDeclaration(integer(field(d, "id")),
      text(field(d, "name")), lower(field(d, "lower")), upper(field(d, "upper")), category(field(d, "category")), text(field(d, "domainKind"))))
    val variables = records("variables").map(v => LpExpandedVariable(variableId(field(v, "id")), text(field(v, "name")),
      lower(field(v, "lower")), upper(field(v, "upper")), category(field(v, "category")), elements(field(v, "keyParts")).map(text)))
    val rows = records("constraints").map(r => LpExpandedConstraint(constraintId(field(r, "id")), text(field(r, "name")),
      text(field(r, "sense")), number(field(r, "rhs")), elements(field(r, "group")).map(text)))
    val matrix = records("coefficients").map(c => LpMatrixCoefficient(constraintId(field(c, "row")),
      variableId(field(c, "variable")), number(field(c, "value"))))
    val factors = elements(field(h, "factors")).map(f => {
      val i = integer(field(f, "index"))
      LpQuadraticFactor(i, number(field(f, "weight")), number(field(f, "constant")), records(s"factor-$i").map(readCoefficient))
    })
    val groups = if (!h.has("sosGroups")) Vector.empty else elements(field(h, "sosGroups")).map { g =>
      val kind = text(field(g, "kind")) match {
        case "Sos1" => SosKind.Sos1
        case "Sos2" => SosKind.Sos2
        case other => throw new LpModelException(s"Unsupported SOS kind '$other'")
      }
      LpSosData(text(field(g, "name")), kind, elements(field(g, "members")).map(m =>
        LpSosMember(variableId(field(m, "variable")), number(field(m, "weight")))))
    }
    val data = LpPortableModel(1, text(field(h, "name")), sense, declarations, variables, rows, matrix,
      LpAffineData(records("objective").map(readCoefficient), number(field(h, "objectiveConstant"))),
      records("diagonal").map(readCoefficient), factors, groups)
    val rawSolution = field(h, "solution")
    val solution = if (rawSolution.isNull) None else {
      val statuses = Seq(LpStatus.Optimal, LpStatus.Stopped, LpStatus.IterationLimit, LpStatus.Infeasible,
        LpStatus.Unbounded, LpStatus.InfeasibleOrUnbounded).map(s => s.toString -> s).toMap
      val status = statuses.getOrElse(text(field(rawSolution, "status")), throw new LpModelException("Invalid JSON solution status"))
      val iteration = field(rawSolution, "iteration")
      val candidate = com.github.vbmacher.spark_lp.CandidateInfo(boolean(field(rawSolution, "available")),
        boolean(field(rawSolution, "feasible")), if (iteration.isNull) None else Some(integer(iteration)))
      val values = if (boolean(field(rawSolution, "hasValues"))) Some(records("solution-values").map(v =>
        LpCandidateValue(variableId(field(v, "variable")), number(field(v, "value"))))) else None
      val residualNode = field(rawSolution, "residuals")
      val residuals = if (residualNode.isNull) None else {
        val numbers = elements(residualNode).map(n => resultNumber(n).getOrElse(Double.NaN))
        if (numbers.size != 3) throw new LpModelException("Expected three JSON residuals")
        Some(LpResiduals(numbers(0), numbers(1), numbers(2)))
      }
      Some(LpSolutionData(status, resultNumber(field(rawSolution, "objective")), candidate, values, residuals))
    }
    LpJsonDocument(data, solution, source)
  }
}
