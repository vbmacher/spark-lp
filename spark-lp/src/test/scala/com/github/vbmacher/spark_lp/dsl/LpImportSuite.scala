package com.github.vbmacher.spark_lp.dsl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import com.github.vbmacher.spark_lp.dsl.implicits._
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.SparkSession
import org.scalatest.funsuite.AnyFunSuite

class LpImportSuite extends AnyFunSuite with DataFrameSuiteBase {
  private def fixture(text: String)(run: Path => Unit): Unit = {
    val path = Files.createTempFile("spark-lp-import-", ".lp")
    Files.write(path, text.stripMargin.getBytes(StandardCharsets.UTF_8))
    try run(path) finally Files.deleteIfExists(path)
  }

  private val continuous = """\ A handwritten model with a wrapped objective and constraint.
    |mInImIzE
    | cost: + 1e0 amount
    |       + 2E+0 substitute + 7
    |sUbJeCt To
    | demand: amount
    |         + substitute + 1 >= 5
    | cap: amount <= 3
    |Bounds
    | 0 <= amount <= 4
    | substitute >= 0
    |End
    |"""

  test("handwritten continuous LP preserves names, objective constants and constraint constants") {
    implicit val ss: SparkSession = spark
    fixture(continuous) { path =>
      val imported = LpImport.read(path)

      assert(imported.model.sense == Minimize)
      assert(imported.variables.keySet == Set("amount", "substitute"))
      assert(imported.constraints.keySet == Set("demand", "cap"))
      assert(imported.model.inspect.objectiveConstant == 7.0)

      val result = imported.model.solve()
      try {
        assert(result.status == LpStatus.Optimal)
        assert(math.abs(result.objectiveValue - 12.0) < 1e-6)
        assert(math.abs(result.value(imported.variables("amount")) - 3.0) < 1e-6)
        assert(math.abs(result.value(imported.variables("substitute")) - 1.0) < 1e-6)
      } finally result.close()

      imported.model += (imported.variables("amount") <= 2.0).named("new_cap")

      assert(imported.model.inspect.constraintDeclarations.exists(_.name == "new_cap"))
    }
  }

  test("handwritten mixed-integer LP solves with general and binary declarations") {
    implicit val ss: SparkSession = spark
    fixture("""Maximize
      | profit: 3 amount - choose + 7
      |Subject To
      | capacity: amount <= 1.5
      | required: choose >= 0.2
      |Bounds
      | 0 <= amount <= 3
      |Generals
      | amount
      |Binaries
      | choose
      |End
      |""") { path =>
      val imported = LpImport.read(path)
      val declarations = imported.model.inspect.variableDeclarations.map(v => v.name -> v).toMap

      assert(imported.model.sense == Maximize)
      assert(declarations("amount").category == Integer)
      assert(declarations("choose").category == Binary)
      assert(declarations("choose").lower == 0.0)
      assert(declarations("choose").upper.contains(1.0))

      val result = imported.model.solve()
      try {
        assert(result.status == LpStatus.Optimal)
        assert(math.abs(result.objectiveValue - 9.0) < 1e-7)
        assert(result.value(imported.variables("amount")) == 1.0)
        assert(result.value(imported.variables("choose")) == 1.0)
      } finally result.close()
    }
  }

  test("implicit coefficients, repeated terms and signed scientific constants retain their algebra") {
    implicit val ss: SparkSession = spark
    fixture("""Minimum
      | obj: x - y + x + 2.5e-1 y - 1E+1
      |Such That
      | sum: x + y = 4
      |Bounds
      | x = 3
      | y = 1
      |End
      |""") { path =>
      val imported = LpImport.read(path)
      val coefficients = imported.model.inspect.objectiveCoefficients.collect()
        .map(c => c.variable -> c.value).toMap

      assert(coefficients.size == 2)
      assert(imported.model.inspect.objectiveConstant == -10.0)

      val result = imported.model.solve()
      try {
        assert(result.status == LpStatus.Optimal)
        assert(math.abs(result.objectiveValue + 4.75) < 1e-8)
      } finally result.close()
    }
  }

  test("LP writer output round trips free, fixed, negative and mixed-integer domains") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("roundtrip", Maximize)
    val upperOnly = model.variable("upperOnly", Double.NegativeInfinity, Some(-1.0))
    val freeValue = model.variable("freeValue", Double.NegativeInfinity)
    val fixed = model.variable("fixed", -2.0, Some(-2.0))
    val integer = model.variable("integerValue", upperBound = Some(3.0), category = Integer)
    val binary = model.variable("binaryValue", category = Binary)
    model += upperOnly + freeValue + fixed + 3.0 * integer - binary + 7.0
    model += (freeValue === -1.0).named("freeEquation")
    model += (integer <= 1.5).named("capacity")
    model += (binary >= 0.2).named("required")
    val directory = Files.createTempDirectory("spark-lp-lp-roundtrip-")
    val path = directory.resolve("model.lp")
    val mapping = LpExport.lp(model.inspect, path, ExportNaming.Original)

    try {
      val imported = LpImport.read(path)
      val declarations = imported.model.inspect.variableDeclarations.map(v => v.name -> v).toMap

      assert(imported.model.sense == Maximize)
      assert(imported.model.inspect.objectiveConstant == 7.0)
      assert(imported.variables.keySet == Set("upperOnly", "freeValue", "fixed", "integerValue", "binaryValue"))
      assert(imported.constraints.keySet == Set("freeEquation", "capacity", "required"))
      assert(declarations("upperOnly").lower == Double.NegativeInfinity)
      assert(declarations("upperOnly").upper.contains(-1.0))
      assert(declarations("freeValue").lower == Double.NegativeInfinity)
      assert(declarations("freeValue").upper.isEmpty)
      assert(declarations("fixed").lower == -2.0 && declarations("fixed").upper.contains(-2.0))
      assert(declarations("integerValue").category == Integer)
      assert(declarations("binaryValue").category == Binary)

      val result = imported.model.solve()
      try {
        assert(result.status == LpStatus.Optimal)
        assert(math.abs(result.objectiveValue - 5.0) < 1e-6)
      } finally result.close()
    } finally {
      mapping.close()
      Files.deleteIfExists(path)
      Files.deleteIfExists(directory)
    }
  }

  test("continuous variables default to nonnegative and bounds may introduce unused variables") {
    implicit val ss: SparkSession = spark
    fixture("""Minimize
      | x
      |Subject To
      | row: x >= 1
      |Bounds
      | unused free
      | fixed = -2
      | upper <= 5
      | lower >= -3
      | -4 <= wrapped
      |     <= 2
      |End
      |""") { path =>
      val declarations = LpImport.read(path).model.inspect.variableDeclarations.map(v => v.name -> v).toMap

      assert(declarations("x").lower == 0.0 && declarations("x").upper.isEmpty)
      assert(declarations("x").category == Continuous)
      assert(declarations("unused").lower == Double.NegativeInfinity && declarations("unused").upper.isEmpty)
      assert(declarations("fixed").lower == -2.0 && declarations("fixed").upper.contains(-2.0))
      assert(declarations("upper").lower == 0.0 && declarations("upper").upper.contains(5.0))
      assert(declarations("lower").lower == -3.0 && declarations("lower").upper.isEmpty)
      assert(declarations("wrapped").lower == -4.0 && declarations("wrapped").upper.contains(2.0))
    }
  }

  test("binary declarations intersect explicit bounds and introduce variables absent from expressions") {
    implicit val ss: SparkSession = spark
    fixture("""Max
      | x
      |ST
      | cap: x <= 1
      |Bounds
      | -2 <= x <= 3
      | 0.2 <= chosen <= 1
      |General
      | unusedInteger
      |Binary
      | x chosen unusedBinary
      |End
      |""") { path =>
      val declarations = LpImport.read(path).model.inspect.variableDeclarations.map(v => v.name -> v).toMap

      assert(declarations("x").category == Binary)
      assert(declarations("x").lower == 0.0 && declarations("x").upper.contains(1.0))
      assert(declarations("chosen").lower == 0.2 && declarations("chosen").upper.contains(1.0))
      assert(declarations("unusedInteger").category == Integer && declarations("unusedInteger").upper.isEmpty)
      assert(declarations("unusedBinary").category == Binary && declarations("unusedBinary").upper.contains(1.0))
    }
  }

  test("section aliases, inline comments and legal identifier punctuation are accepted") {
    implicit val ss: SparkSession = spark
    val aliases = Seq("Min" -> Minimize, "Minimum" -> Minimize, "Maximum" -> Maximize)
    aliases.foreach { case (objective, sense) =>
      fixture(s"""$objective
        | objective: amount.1 + region$$value \\ ignored + garbage
        |S.T.
        | row_1: amount.1 + region$$value >= 2
        |Bounds
        | amount.1 <= 3
        | region$$value <= 3
        |Integers
        | amount.1
        |End
        |""") { path =>
        val imported = LpImport.read(path)

        assert(imported.model.sense == sense)
        assert(imported.variables.keySet == Set("amount.1", "region$value"))
        assert(imported.constraints.keySet == Set("row_1"))
        assert(imported.model.inspect.variableDeclarations.find(_.name == "amount.1").exists(_.category == Integer))
      }
    }
  }

  test("malformed and unsupported LP input reports the path and source line") {
    implicit val ss: SparkSession = spark
    val invalid = Seq(
      continuous.replace("End", ""),
      continuous + "trailing content\n",
      continuous.replace("cap: amount <= 3", "demand: amount <= 3"),
      continuous.replace("cap: amount <= 3", "cap: amount < 3"),
      continuous.replace("cap: amount <= 3", "cap: amount <="),
      continuous.replace("cap: amount <= 3", "cap: amount <= 3 <= 4"),
      continuous.replace("+ 1e0 amount", "+ 1e999 amount"),
      continuous.replace("+ 1e0 amount", "+ NaN amount"),
      continuous.replace("+ 1e0 amount", "+ 1e amount"),
      continuous.replace("+ 1e0 amount", "+ [ amount ^ 2 ]"),
      continuous.replace("+ 1e0 amount", "+ amount * substitute"),
      continuous.replace("End", "SOS\n group: S1:: amount:1 substitute:2\nEnd"),
      continuous.replace("End", "Semi-continuous\n amount\nEnd"),
      continuous.replace("0 <= amount <= 4", "5 <= amount <= 3"),
      continuous.replace("cost: + 1e0 amount", "cost: + 1e308 amount + 1e308 amount"))

    invalid.foreach { text => fixture(text) { path =>
      val error = intercept[LpModelException](LpImport.read(path))

      assert(error.getMessage.contains(path.toString))
      assert(error.getMessage.matches("(?s).*:[0-9]+.*"))
    } }
  }

  test("byte, variable, constraint and coefficient limits reject excess input") {
    implicit val ss: SparkSession = spark
    fixture(continuous) { path =>
      val limits = Seq(
        LpReadOptions(maxBytes = 1L),
        LpReadOptions(maxVariables = 1),
        LpReadOptions(maxRows = 1),
        LpReadOptions(maxCoefficients = 1))

      limits.foreach { options =>
        val error = intercept[LpModelException](LpImport.read(path, options))

        assert(error.getMessage.contains(path.toString))
      }
    }
  }

  test("input exactly at resource limits remains accepted") {
    implicit val ss: SparkSession = spark
    fixture("Minimize\n x\nSubject To\n only: x <= 1\nEnd\n") { path =>
      val options = LpReadOptions(maxBytes = Files.size(path), maxVariables = 1, maxRows = 1, maxCoefficients = 2)

      val imported = LpImport.read(path, options)

      assert(imported.variables.keySet == Set("x"))
      assert(imported.constraints.keySet == Set("only"))
    }
  }

  test("the largest byte limit accepts a small file without overflow") {
    implicit val ss: SparkSession = spark
    fixture(continuous) { path =>
      val options = LpReadOptions(maxBytes = Long.MaxValue)

      val imported = LpImport.read(path, options)

      assert(imported.variables.keySet == Set("amount", "substitute"))
      assert(imported.constraints.keySet == Set("demand", "cap"))
    }
  }

  test("right-hand expressions cannot silently become extra constraints") {
    implicit val ss: SparkSession = spark
    val invalidRows = Seq("c: x <= 1 + y <= 2", "c: x <= 1\n + y <= 2")
    invalidRows.foreach { row =>
      fixture(s"Minimize\n x + y\nSubject To\n $row\nEnd\n") { path =>
        val error = intercept[LpModelException](LpImport.read(path))

        assert(error.getMessage.contains(path.toString))
        assert(error.getMessage.matches("(?s).*:[0-9]+.*"))
      }
    }
  }

  test("original-name exports preserve identifiers that resemble section aliases or numeric values") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("keyword names")
    val names = Vector("max", "st", "integer", "nan")
    val variables = names.zipWithIndex.map { case (name, index) =>
      model.variable(name, index.toDouble, Some(index.toDouble))
    }
    model += lpSum(variables.map(v => 1.0 * v))
    val directory = Files.createTempDirectory("spark-lp-keyword-roundtrip-")
    val path = directory.resolve("model.lp")
    val mapping = LpExport.lp(model.inspect, path, ExportNaming.Original)

    try {
      val imported = LpImport.read(path)
      val declarations = imported.model.inspect.variableDeclarations.map(v => v.name -> v).toMap

      assert(imported.variables.keySet == names.toSet)
      names.zipWithIndex.foreach { case (name, index) =>
        assert(declarations(name).lower == index.toDouble)
        assert(declarations(name).upper.contains(index.toDouble))
      }
    } finally {
      mapping.close()
      Files.deleteIfExists(path)
      Files.deleteIfExists(directory)
    }
  }

  test("unnamed constraints on separate lines receive distinct generated names") {
    implicit val ss: SparkSession = spark
    fixture("""Minimize
      | x + y
      |Subject To
      | x <= 3
      | y >= 1
      | x + y <= 4
      |End
      |""") { path =>
      val imported = LpImport.read(path)
      val rows = imported.model.inspect.constraints.collect().map(r => r.name -> r.rhs).toMap

      assert(imported.constraints.keySet == Set("__row_1", "__row_2", "__row_3"))
      assert(rows == Map("__row_1" -> 3.0, "__row_2" -> 1.0, "__row_3" -> 4.0))
    }
  }

  test("reversed bound intervals and explicit infinities preserve declared domains") {
    implicit val ss: SparkSession = spark
    fixture("""Minimize
      | x
      |Subject To
      | row: x <= 3
      |Bounds
      | 5 >= x >= -2
      | -inf <= open <= +inf
      | ceiling <= +infinity
      | floor >= -infinity
      |End
      |""") { path =>
      val declarations = LpImport.read(path).model.inspect.variableDeclarations.map(v => v.name -> v).toMap

      assert(declarations("x").lower == -2.0 && declarations("x").upper.contains(5.0))
      assert(declarations("open").lower == Double.NegativeInfinity && declarations("open").upper.isEmpty)
      assert(declarations("ceiling").lower == 0.0 && declarations("ceiling").upper.isEmpty)
      assert(declarations("floor").lower == Double.NegativeInfinity && declarations("floor").upper.isEmpty)
    }
  }

  test("parsing limits must be positive") {
    val invalid = Seq[() => LpReadOptions](
      () => LpReadOptions(maxBytes = 0L),
      () => LpReadOptions(maxVariables = 0),
      () => LpReadOptions(maxRows = 0),
      () => LpReadOptions(maxCoefficients = 0))

    invalid.foreach { options =>
      intercept[IllegalArgumentException](options())
    }
  }

  test("bound arithmetic cannot silently become another bound record") {
    implicit val ss: SparkSession = spark
    fixture("Minimize\n x + y\nBounds\n x <= 1 + 2 <= y\nEnd\n") { path =>
      val error = intercept[LpModelException](LpImport.read(path))

      assert(error.getMessage.contains(s"$path:4:"))
      assert(error.getMessage.contains("bound expressions are unsupported"))
    }
  }

  test("unused free variables with unsupported-section names round trip through LP export") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("unused keyword names")
    val names = Set("sos", "semi", "pwl", "lazy", "user")
    names.foreach(name => model.variable(name, Double.NegativeInfinity))
    model += LpExpr.zero
    val directory = Files.createTempDirectory("spark-lp-unused-roundtrip-")
    val path = directory.resolve("model.lp")
    val mapping = LpExport.lp(model.inspect, path, ExportNaming.Original)

    try {
      val imported = LpImport.read(path)

      assert(imported.variables.keySet == names)
      assert(imported.model.inspect.variableDeclarations.forall(v => v.lower.isNegInfinity && v.upper.isEmpty))
    } finally {
      mapping.close()
      Files.deleteIfExists(path)
      Files.deleteIfExists(directory)
    }
  }

  test("invalid UTF-8 input reports a model error with file context") {
    implicit val ss: SparkSession = spark
    val path = Files.createTempFile("spark-lp-invalid-utf8-", ".lp")
    Files.write(path, Array(0xc3.toByte, 0x28.toByte))

    try {
      val error = intercept[LpModelException](LpImport.read(path))

      assert(error.getMessage.contains(path.toString))
      assert(error.getMessage.contains("Invalid UTF-8"))
    } finally Files.deleteIfExists(path)
  }
}
