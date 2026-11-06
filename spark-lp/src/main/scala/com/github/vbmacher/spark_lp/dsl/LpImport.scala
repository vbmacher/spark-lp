package com.github.vbmacher.spark_lp.dsl

import java.io.{FilterInputStream, InputStreamReader}
import java.nio.charset.{CharacterCodingException, StandardCharsets}
import java.nio.file.{Files, Path}
import java.util.Locale
import org.apache.spark.sql.SparkSession
import scala.collection.mutable
import com.github.vbmacher.spark_lp.dsl.implicits._

/** Limits driver-side LP parsing before Spark model declarations are created.
  *
  * @param maxBytes maximum UTF-8 input size in bytes, including comments.
  * @param maxVariables maximum number of distinct variable names.
  * @param maxRows maximum number of constraint declarations.
  * @param maxCoefficients maximum number of variable terms read, including repeated and zero terms.
  */
final case class LpReadOptions(
  maxBytes: Long = 64L * 1024 * 1024,
  maxVariables: Int = 100000,
  maxRows: Int = 10000,
  maxCoefficients: Int = 1000000
) {
  require(maxBytes > 0 && maxVariables > 0 && maxRows > 0 && maxCoefficients > 0,
    "LP parsing limits must be positive")
}

/** An imported linear model and handles indexed by source names.
  *
  * @param model editable model named after the input file, including its extension.
  * @param variables source variable names mapped to scalar variable handles.
  * @param constraints source row names mapped to constraints; unnamed rows use `__row_N` names.
  */
final case class LpFileModel(
  model: LpProblem,
  variables: Map[String, LpVariable],
  constraints: Map[String, LpConstraint]
)

/** Reads the linear CPLEX-style LP subset documented in the usage guide. Does not invoke a solver. */
object LpImport {
  /** Reads a local UTF-8 file on the driver. Invalid or unsupported syntax raises `LpModelException`.
    *
    * @param path local driver file to read.
    * @param options positive limits on input size and parsed declarations.
    * @param spark session used to construct the imported model.
    * @return independently editable model and source-name mappings.
    */
  def read(path: Path, options: LpReadOptions = LpReadOptions())(
    implicit spark: SparkSession): LpFileModel = {
    def fail(line: Int, message: String): Nothing =
      throw new LpModelException(s"LP $path:$line: $message")

    if (Files.size(path) > options.maxBytes) fail(1, s"Exceeded maxBytes=${options.maxBytes}")
    // Enforce the byte limit while reading too, in case the file grows after the size check.
    val input = new FilterInputStream(Files.newInputStream(path)) {
      private var remaining = options.maxBytes
      override def read(bytes: Array[Byte], offset: Int, length: Int): Int = {
        val allowed = if (remaining >= length.toLong) length else (remaining + 1L).toInt
        val count = super.read(bytes, offset, allowed)
        if (count > 0) {
          remaining -= count
          if (remaining < 0) fail(1, s"Exceeded maxBytes=${options.maxBytes}")
        }
        count
      }
    }
    val reader = new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder())
    try new Parser(reader, path, options).parse()
    catch { case _: CharacterCodingException => fail(1, "Invalid UTF-8 input") }
    finally reader.close()
  }

  /** One lexical token with its source location.
    * @param text exact token spelling, or the empty string at end of input.
    * @param line one-based source line.
    */
  private final case class Token(text: String, line: Int) {
    def lower: String = text.toLowerCase(Locale.ROOT)
  }

  /** Mutable variable declaration accumulated before model construction.
    * @param lower inclusive lower bound, initially zero.
    * @param upper inclusive upper bound, absent when unbounded above.
    * @param category continuous, integer or binary domain.
    */
  private final case class Variable(
    var lower: Double = 0.0,
    var upper: Option[Double] = None,
    var category: VariableCategory = Continuous)

  /** Parsed expression containing no Spark objects.
    * @param terms summed coefficients indexed by variable name.
    * @param constant finite constant term.
    */
  private final case class Expression(terms: Map[String, Double], constant: Double)

  /** One parsed constraint before construction of the public model.
    * @param name source name or generated unnamed-row name.
    * @param expression original left-hand side.
    * @param sense comparison operator.
    * @param rhs finite right-hand side.
    */
  private final case class Row(name: String, expression: Expression, sense: String, rhs: Double)

  private final class Parser(reader: InputStreamReader, path: Path, options: LpReadOptions)(
    implicit spark: SparkSession) {
    private var line = 1
    private var previousLine = 0
    private var ch = reader.read()
    private val lookahead = mutable.Queue.empty[Token]
    private val variables = mutable.LinkedHashMap.empty[String, Variable]
    private val rows = mutable.LinkedHashMap.empty[String, Row]
    private var coefficientCount = 0
    private val numberPattern = "(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?".r
    private val namePattern = "[A-Za-z_][A-Za-z0-9_.$]*".r
    private val objectives = Set("minimize", "minimum", "min", "maximize", "maximum", "max")
    private val rowHeaders = Set("subject", "such", "st", "s.t.", "st.")
    private val generalHeaders = Set("general", "generals", "integer", "integers")
    private val binaryHeaders = Set("binary", "binaries")
    private val unsupported = Set("sos", "semi", "semis", "semi-continuous", "semicontinuous",
      "semi-cont", "pwl", "lazy", "user", "multi-objectives")
    private val headers = objectives ++ rowHeaders ++ generalHeaders ++ binaryHeaders ++ unsupported ++ Set("bounds", "end")

    private def fail(token: Token, message: String): Nothing =
      throw new LpModelException(s"LP $path:${token.line}: $message")
    private def advance(): Unit = { if (ch == '\n') line += 1; ch = reader.read() }
    private def lex(): Token = {
      while (ch >= 0 && (ch.toChar.isWhitespace || ch == '\\')) {
        if (ch == '\\') while (ch >= 0 && ch != '\n') advance()
        else advance()
      }
      val start = line
      if (ch < 0) Token("", start)
      else {
        val text = new StringBuilder
        if ("+-:<>=[]^*/".contains(ch.toChar)) {
          text.append(ch.toChar); advance()
          if ((text.toString == "<" || text.toString == ">") && ch == '=') {
            text.append('='); advance()
          }
        } else {
          val numericStart = ch.toChar.isDigit || ch == '.'
          while (ch >= 0 && !ch.toChar.isWhitespace && !"\\+-:<>=[]^*/".contains(ch.toChar)) {
            val exponent = numericStart && (ch == 'e' || ch == 'E')
            text.append(ch.toChar); advance()
            // The sign of a numeric exponent belongs to the number, not to the next term.
            if (exponent && (ch == '+' || ch == '-')) {
              text.append(ch.toChar); advance()
            }
          }
        }
        Token(text.toString, start)
      }
    }
    private def peek(n: Int = 0): Token = {
      while (lookahead.size <= n) lookahead.enqueue(lex())
      lookahead(n)
    }
    private def take(): Token = {
      val token = peek(); lookahead.dequeue(); previousLine = token.line; token
    }
    private val canonicalHeaders = Set("minimize", "maximize", "subject", "bounds", "generals", "binaries", "end")
    private def isHeader(token: Token): Boolean = {
      val knownVariableAlias = variables.contains(token.text) && !canonicalHeaders(token.lower)
      val boundRecord = peek(1).line == token.line &&
        (peek(1).lower == "free" || Set("<=", ">=", "=")(peek(1).text))
      headers(token.lower) && !knownVariableAlias && !boundRecord && token.line > previousLine &&
        (unsupported(token.lower) || peek(1).text.isEmpty || peek(1).line > token.line ||
          Set("subject", "such")(token.lower))
    }
    private def isName(token: Token): Boolean = namePattern.pattern.matcher(token.text).matches() &&
      !Set("free", "inf", "infinity")(token.lower)
    private def name(): Token = {
      val token = take()
      if (!isName(token)) fail(token, s"Expected a variable or row name, found '${token.text}'")
      token
    }
    private def finite(token: Token): Double = {
      if (!numberPattern.pattern.matcher(token.text).matches()) fail(token, s"Expected a number, found '${token.text}'")
      val value = token.text.toDouble
      if (!java.lang.Double.isFinite(value)) fail(token, "Non-finite numeric value")
      value
    }
    private def checked(value: Double, token: Token): Double = {
      if (!java.lang.Double.isFinite(value)) fail(token, "Numeric sum overflow")
      value
    }
    private def variable(token: Token): Variable = variables.getOrElseUpdate(token.text, {
      if (variables.size >= options.maxVariables) fail(token, s"Exceeded maxVariables=${options.maxVariables}")
      Variable()
    })
    private def expression(stop: Token => Boolean): Expression = {
      val terms = mutable.LinkedHashMap.empty[String, Double]
      var constant = 0.0
      var first = true
      while (!stop(peek()) && peek().text.nonEmpty) {
        val sign = if (peek().text == "+") { take(); 1.0 }
        else if (peek().text == "-") { take(); -1.0 }
        else if (first) 1.0 else fail(peek(), "Expected '+' or '-' between linear terms")
        val token = take()
        val numeric = numberPattern.pattern.matcher(token.text).matches()
        val value = if (numeric) sign * finite(token) else sign
        val term = if (numeric && isName(peek()) && !isHeader(peek())) Some(take())
        else if (!numeric && isName(token)) Some(token) else None
        term match {
          case Some(v) =>
            variable(v)
            if (coefficientCount >= options.maxCoefficients) fail(v, s"Exceeded maxCoefficients=${options.maxCoefficients}")
            coefficientCount += 1
            terms(v.text) = checked(terms.getOrElse(v.text, 0.0) + value, v)
          case None if numeric => constant = checked(constant + value, token)
          case _ => fail(token, s"Unsupported linear term '${token.text}'")
        }
        first = false
      }
      if (first) fail(peek(), "Expected a linear expression")
      Expression(terms.toMap, constant)
    }
    private def scalar(allowInfinity: Boolean): Double = {
      val sign = if (peek().text == "-") { take(); -1.0 }
      else if (peek().text == "+") { take(); 1.0 } else 1.0
      val token = take()
      if (allowInfinity && Set("inf", "infinity")(token.lower)) sign * Double.PositiveInfinity
      else sign * finite(token)
    }
    private def comparison(): Token = {
      val token = take()
      if (!Set("<=", ">=", "=")(token.text)) fail(token, "Expected '<=', '>=' or '='")
      token
    }
    private def setBound(v: Variable, sense: String, value: Double, token: Token): Unit = sense match {
      case ">=" =>
        if (value.isPosInfinity) fail(token, "Lower bound cannot be positive infinity")
        v.lower = value
      case "<=" =>
        if (value.isNegInfinity) fail(token, "Upper bound cannot be negative infinity")
        v.upper = if (value.isPosInfinity) None else Some(value)
      case "=" =>
        if (!java.lang.Double.isFinite(value)) fail(token, "Fixed bound must be finite")
        v.lower = value; v.upper = Some(value)
    }
    private def bound(): Unit = {
      if (isName(peek())) {
        val token = name(); val v = variable(token)
        if (peek().lower == "free") { take(); v.lower = Double.NegativeInfinity; v.upper = None }
        else { val sense = comparison(); setBound(v, sense.text, scalar(allowInfinity = true), token) }
      } else {
        val value = scalar(allowInfinity = true)
        val sense = comparison()
        val token = name(); val v = variable(token)
        val reversed = if (sense.text == "<=") ">=" else if (sense.text == ">=") "<=" else "="
        setBound(v, reversed, value, token)
        if (Set("<=", ">=", "=")(peek().text)) {
          val other = comparison()
          if (sense.text == "=" || other.text != sense.text) fail(other, "Expected a consistently ordered bound interval")
          setBound(v, other.text, scalar(allowInfinity = true), token)
        }
      }
    }

    def parse(): LpFileModel = {
      val start = take()
      if (!objectives(start.lower)) fail(start, "Expected Minimize or Maximize")
      val sense = if (start.lower.startsWith("max")) Maximize else Minimize
      if (peek(1).text == ":") { name(); take() }
      val objective = expression(isHeader)
      var stage = 0
      var ended = false
      val seen = mutable.Set.empty[String]
      while (!ended && peek().text.nonEmpty) {
        val header = take()
        val section = if (rowHeaders(header.lower)) "rows"
        else if (generalHeaders(header.lower)) "generals"
        else if (binaryHeaders(header.lower)) "binaries" else header.lower
        val nextStage = section match {
          case "rows" => 1
          case "bounds" => 2
          case "generals" | "binaries" => 3
          case "end" => 4
          case _ => fail(header, s"Unsupported section '${header.text}'")
        }
        if (seen(section) || nextStage < stage) fail(header, s"Repeated or out-of-order section '${header.text}'")
        seen += section; stage = nextStage
        section match {
          case "rows" =>
            if (header.lower == "subject" || header.lower == "such") {
              val second = take()
              val expected = if (header.lower == "subject") "to" else "that"
              if (second.lower != expected) fail(second, s"Expected '$expected'")
            }
            while (peek().text.nonEmpty && !isHeader(peek())) {
              val token = peek()
              if (rows.size >= options.maxRows) fail(token, s"Exceeded maxRows=${options.maxRows}")
              val rowName = if (peek(1).text == ":") { val n = name(); take(); n.text } else s"__row_${rows.size + 1}"
              if (rows.contains(rowName)) fail(token, s"Duplicate constraint name '$rowName'")
              val lhs = expression(t => Set("<=", ">=", "=")(t.text) || isHeader(t))
              val relation = comparison()
              val rhs = scalar(allowInfinity = false)
              if (peek().text.nonEmpty && !isHeader(peek()) &&
                (peek().line <= previousLine || Set("+", "-")(peek().text)))
                fail(peek(), "Expected a new constraint line; RHS expressions and ranged constraints are unsupported")
              checked(rhs - lhs.constant, token)
              rows(rowName) = Row(rowName, lhs, relation.text, rhs)
            }
          case "bounds" =>
            while (peek().text.nonEmpty && !isHeader(peek())) {
              bound()
              if (peek().text.nonEmpty && !isHeader(peek()) && peek().line <= previousLine)
                fail(peek(), "Expected a new bound line; bound expressions are unsupported")
            }
          case "generals" | "binaries" =>
            while (peek().text.nonEmpty && !isHeader(peek())) {
              val token = name(); val v = variable(token)
              val category = if (section == "generals") Integer else Binary
              if (v.category != Continuous && v.category != category) fail(token, "Conflicting variable type declarations")
              v.category = category
            }
          case "end" => ended = true
        }
      }
      if (!ended) fail(peek(), "Required End section is missing")
      if (peek().text.nonEmpty) fail(peek(), "Unexpected content after End")
      variables.foreach { case (name, v) =>
        if (v.category == Binary) {
          v.lower = math.max(0.0, v.lower); v.upper = Some(math.min(1.0, v.upper.getOrElse(1.0)))
        }
        if (v.upper.exists(_ < v.lower)) fail(Token(name, line), s"Inconsistent bounds for '$name'")
      }
      val model = LpProblem(path.getFileName.toString, sense)
      val handles = variables.map { case (name, v) => name -> model.variable(name, v.lower, v.upper, v.category) }.toMap
      def build(e: Expression): LpExpr = e.terms.foldLeft(LpExpr.zero + e.constant) {
        case (sum, (name, value)) => sum + value * handles(name)
      }
      model.setObjective(build(objective))
      val constraints = rows.map { case (name, row) =>
        val lhs = build(row.expression)
        val constraint = (row.sense match {
          case "<=" => lhs <= row.rhs
          case ">=" => lhs >= row.rhs
          case "=" => lhs === row.rhs
        }).named(name)
        model += constraint
        name -> constraint
      }.toMap
      LpFileModel(model, handles, constraints)
    }
  }
}
