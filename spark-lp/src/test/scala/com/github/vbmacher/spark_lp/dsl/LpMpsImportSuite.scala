package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.dsl.implicits._
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.SparkSession
import org.scalatest.funsuite.AnyFunSuite
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

class LpMpsImportSuite extends AnyFunSuite with DataFrameSuiteBase {
  private def fixture(text: String)(run: Path => Unit): Unit = {
    val path = Files.createTempFile("spark-lp-import-", ".mps")
    try {
      Files.write(path, text.stripMargin.getBytes(StandardCharsets.UTF_8))
      run(path)
    } finally Files.deleteIfExists(path)
  }
  private val mip = """NAME HAND_AUTHORED
    |OBJSENSE
    | MAX
    |ROWS
    | N PROFIT
    | L CAPACITY
    | G PICK
    |COLUMNS
    | MARK0 'MARKER' 'INTORG'
    | amount PROFIT 3 CAPACITY 1
    | choose PROFIT -1 PICK 1
    | MARK1 'MARKER' 'INTEND'
    |RHS
    | primary CAPACITY 1.5 PICK 0.2
    | primary PROFIT -7
    | alternate CAPACITY 2.5 PICK 0
    |BOUNDS
    | UP standard amount 3
    | BV standard choose
    | UP alternate amount 1
    | BV alternate choose
    |ENDATA
    |"""
  test("a handwritten MPS retains its mixed-integer optimum and editable variable handles") {
    implicit val ss: SparkSession = spark
    fixture(mip) { path =>
      val imported = LpMpsImport.read(path)

      assert(imported.model.name == "HAND_AUTHORED")
      assert(imported.model.sense == Maximize)
      assert(imported.variables.keySet == Set("amount", "choose"))
      assert(imported.model.inspect.variableDeclarations.map(_.category).toSet == Set(Integer, Binary))

      val solution = imported.model.solve()
      try {
        assert(solution.status == LpStatus.Optimal)
        assert(math.abs(solution.objectiveValue - 9.0) < 1e-7)
        assert(solution.value(imported.variables("amount")) == 1.0)
      } finally solution.close()

      // The imported handle can be used to add a constraint to the same model.
      val amount = imported.variables("amount")

      imported.model += (amount <= 0.0).named("extension")

      assert(imported.model.inspect.constraintDeclarations.exists(_.name == "extension"))
    }
  }

  test("an MPS round trip preserves original variable names and the optimum") {
    implicit val ss: SparkSession = spark
    fixture(mip) { path =>
      val imported = LpMpsImport.read(path)
      val directory = Files.createTempDirectory("spark-lp-roundtrip-")
      val out = directory.resolve("model.mps")
      try {
        val names = LpMps.write(imported.model.inspect, out, ExportNaming.Original)
        try {
          val restored = LpMpsImport.read(out)
          if (HighsInterop.available) assert(HighsInterop.inspect(out).get("objective").asDouble() == 9.0)

          val result = restored.model.solve()

          try {
            assert(math.abs(result.objectiveValue - 9.0) < 1e-7)
            assert(restored.variables.keySet == Set("amount", "choose"))
          } finally result.close()
        } finally names.close()
      } finally {
        Files.deleteIfExists(out)
        Files.deleteIfExists(directory)
      }
    }
  }

  test("selecting alternate MPS RHS and bound sets changes the optimum") {
    implicit val ss: SparkSession = spark
    fixture(mip) { path =>
      val options = MpsReadOptions(rhsSet = Some("alternate"), boundSet = Some("alternate"))

      val selected = LpMpsImport.read(path, options)
      val result = selected.model.solve()

      try assert(math.abs(result.objectiveValue - 3.0) < 1e-7)
      finally result.close()
    }
  }

  test("range signs, free, fixed and upper-only bounds retain continuous original algebra") {
    implicit val ss: SparkSession = spark
    fixture("""NAME RANGES_EXAMPLE
      |ROWS
      | N OBJ
      | L LROW
      | G GROW
      | E POSITIVE
      | E NEGATIVE
      | E FIXFREE
      |COLUMNS
      | x OBJ 1 LROW 1
      | x GROW 1 POSITIVE 1
      | x NEGATIVE 1
      | free OBJ 1 FIXFREE 1
      | fixed OBJ 1
      | upper OBJ -1
      |RHS
      | rhs LROW 4 GROW 1
      | rhs POSITIVE 2 NEGATIVE 3
      | rhs FIXFREE -1 OBJ -5
      |RANGES
      | ranges LROW -3 GROW -3
      | ranges POSITIVE 1 NEGATIVE -1
      |BOUNDS
      | FR bounds free
      | FX bounds fixed 2
      | MI bounds upper
      | UP bounds upper 2
      |ENDATA
      |""") { path =>
      val imported = LpMpsImport.read(path)

      assert(imported.constraints("LROW").size == 2)
      assert(imported.constraints("FIXFREE").size == 1)
      val rows = imported.model.inspect.constraints.collect().map(r => r.name -> r.rhs).toMap

      assert(rows("LROW__lower") == 1.0)
      assert(rows("LROW__upper") == 4.0)
      assert(rows("GROW__lower") == 1.0)
      assert(rows("GROW__upper") == 4.0)
      assert(rows("POSITIVE__lower") == 2.0)
      assert(rows("NEGATIVE__lower") == 2.0)

      val result = imported.model.solve()
      try {
        assert(result.status == LpStatus.Optimal)
        assert(math.abs(result.objectiveValue - 6.0) < 1e-6)
        assert(math.abs(result.value(imported.variables("x")) - 2.0) < 1e-6)
        if (HighsInterop.available) assert(math.abs(HighsInterop.inspect(path).get("objective").asDouble() - 6.0) < 1e-8)
      } finally result.close()
    }
  }

  test("integer defaults distinguish INTORG markers from LI bounds") {
    implicit val ss: SparkSession = spark
    fixture("""NAME INTEGER_DEFAULTS
      |ROWS
      | N OBJ
      |COLUMNS
      | MARK0 'MARKER' 'INTORG'
      | marker OBJ 1
      | MARK1 'MARKER' 'INTEND'
      | bounded OBJ 1
      |BOUNDS
      | LI bounds bounded 5
      |ENDATA
      |""") { path =>
      val declarations = LpMpsImport.read(path).model.inspect.variableDeclarations.map(v => v.name -> v).toMap

      assert(declarations("marker").category == Integer)
      assert(declarations("marker").lower == 0.0)
      assert(declarations("marker").upper.contains(1.0))
      assert(declarations("bounded").category == Integer)
      assert(declarations("bounded").lower == 5.0)
      assert(declarations("bounded").upper.isEmpty)
    }
  }

  test("negative upper-only bounds infer a free lower side for continuous and integer variables") {
    implicit val ss: SparkSession = spark
    fixture("""NAME NEGATIVE_UPPER
      |ROWS
      | N OBJ
      |COLUMNS
      | continuous OBJ 1
      | integer OBJ 1
      | zero OBJ 1
      | zeroInteger OBJ 1
      | MARK0 'MARKER' 'INTORG'
      | marker OBJ 1
      | MARK1 'MARKER' 'INTEND'
      |BOUNDS
      | UP bounds continuous -1
      | UI bounds integer -2
      | UP bounds marker -3
      | UP bounds zero 0
      | UI bounds zeroInteger 0
      |ENDATA
      |""") { path =>
      val declarations = LpMpsImport.read(path).model.inspect.variableDeclarations.map(v => v.name -> v).toMap

      Seq("continuous" -> -1.0, "integer" -> -2.0, "marker" -> -3.0).foreach { case (name, upper) =>
        assert(declarations(name).lower == Double.NegativeInfinity)
        assert(declarations(name).upper.contains(upper))
      }
      assert(declarations("continuous").category == Continuous)
      assert(declarations("integer").category == Integer && declarations("marker").category == Integer)
      Seq("zero", "zeroInteger").foreach { name =>
        assert(declarations(name).lower == 0.0 && declarations(name).upper.contains(0.0))
      }
    }
  }

  test("explicit lower bounds override negative-upper defaults independently of record order") {
    implicit val ss: SparkSession = spark
    for (lowerType <- Seq("LO", "LI"); lowerFirst <- Seq(true, false)) {
      val records = Seq(s"$lowerType bounds x -4", "UP bounds x -1")
      val ordered = if (lowerFirst) records else records.reverse
      val text = s"NAME EXPLICIT_LOWER\nROWS\n N OBJ\nCOLUMNS\n x OBJ 1\nBOUNDS\n ${ordered.mkString("\n ")}\nENDATA\n"

      fixture(text) { path =>
        val declaration = LpMpsImport.read(path).model.inspect.variableDeclarations.head

        assert(declaration.lower == -4.0 && declaration.upper.contains(-1.0))
        assert(declaration.category == (if (lowerType == "LI") Integer else Continuous))
      }
    }
  }

  test("explicit lower bounds inconsistent with negative upper bounds are rejected independently of record order") {
    implicit val ss: SparkSession = spark
    for (lowerType <- Seq("LO", "LI"); lowerFirst <- Seq(true, false)) {
      val records = Seq(s"$lowerType bounds x 0", "UP bounds x -1")
      val ordered = if (lowerFirst) records else records.reverse
      val text = s"NAME INCONSISTENT_LOWER\nROWS\n N OBJ\nCOLUMNS\n x OBJ 1\nBOUNDS\n ${ordered.mkString("\n ")}\nENDATA\n"

      fixture(text) { path =>
        val error = intercept[LpModelException](LpMpsImport.read(path))

        assert(error.getMessage.contains("Inconsistent bounds for 'x'"))
      }
    }
  }

  test("negative upper-only defaults apply only to the selected bound set") {
    implicit val ss: SparkSession = spark
    fixture("""NAME SELECTED_BOUNDS
      |ROWS
      | N OBJ
      |COLUMNS
      | x OBJ 1
      |BOUNDS
      | UP positive x 2
      | UP negative x -1
      |ENDATA
      |""") { path =>
      val positive = LpMpsImport.read(path).model.inspect.variableDeclarations.head
      val negative = LpMpsImport.read(path, MpsReadOptions(boundSet = Some("negative")))
        .model.inspect.variableDeclarations.head

      assert(positive.lower == 0.0 && positive.upper.contains(2.0))
      assert(negative.lower == Double.NegativeInfinity && negative.upper.contains(-1.0))
    }
  }

  test("malformed or unsupported MPS records report their source file and section") {
    implicit val ss: SparkSession = spark
    val invalidInputs = Seq(
      "semi-continuous bound" -> mip.replace("BV standard choose", "SC standard choose 2"),
      "SOS section" -> mip.replace("ENDATA", "SOS\n S1 GROUP\nENDATA"),
      "nested integer marker" -> mip.replace("INTEND", "INTORG"),
      "unknown RHS row" -> mip.replace("CAPACITY 1.5", "UNKNOWN 1.5"),
      "missing ENDATA" -> mip.replace("ENDATA", ""),
      "NaN objective constant" -> mip.replace("PROFIT -7", "PROFIT NaN"))

    invalidInputs.foreach { case (scenario, input) =>
      withClue(s"$scenario: ") {
        fixture(input) { path =>
          val error = intercept[LpModelException](LpMpsImport.read(path))

          assert(error.getMessage.contains(path.toString))
          assert(error.getMessage.contains("["))
        }
      }
    }
  }

  test("requesting an unknown RHS set reports its name") {
    implicit val ss: SparkSession = spark
    fixture(mip) { path =>
      val options = MpsReadOptions(rhsSet = Some("missing"))

      val error = intercept[LpModelException](LpMpsImport.read(path, options))

      assert(error.getMessage.contains("missing"))
    }
  }

  test("MPS imports report the input limit that was exceeded") {
    implicit val ss: SparkSession = spark
    val limits = Seq(
      "maxVariables=1" -> MpsReadOptions(maxVariables = 1),
      "maxBytes=1" -> MpsReadOptions(maxBytes = 1))

    fixture(mip) { path =>
      limits.foreach { case (limit, options) =>
        withClue(s"$limit: ") {
          val error = intercept[LpModelException](LpMpsImport.read(path, options))

          assert(error.getMessage.contains(limit))
        }
      }
    }
  }

  test("the selected MPS bounds set supplies the upper endpoint") {
    implicit val ss: SparkSession = spark
    fixture(mip) { path =>
      val options = MpsReadOptions(boundSet = Some("standard"))

      val imported = LpMpsImport.read(path, options)
      val amount = imported.model.inspect.variableDeclarations.find(_.name == "amount")

      assert(amount.exists(_.upper.contains(3.0)))
    }
  }

  test("a PL bound leaves the MPS variable without an upper endpoint") {
    implicit val ss: SparkSession = spark
    fixture(mip.replace("UP standard amount 3", "PL standard amount")) { path =>
      val imported = LpMpsImport.read(path)
      val amount = imported.model.inspect.variableDeclarations.find(_.name == "amount")

      assert(amount.exists(_.upper.isEmpty))
    }
  }
}
