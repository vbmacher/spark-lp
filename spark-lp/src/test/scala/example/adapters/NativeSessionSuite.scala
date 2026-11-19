package example.adapters

import com.github.vbmacher.spark_lp.dsl._
import com.github.vbmacher.spark_lp.dsl.implicits._
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.SparkSession
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._

class NativeSessionSuite extends AnyFunSuite with DataFrameSuiteBase {
  private def available(): Unit = {
    val probe = new ProcessBuilder("python3", "-c",
      "import importlib.util; assert importlib.util.find_spec('highspy') or importlib.util.find_spec('scipy')").start()
    try {
      if (!probe.waitFor(10, TimeUnit.SECONDS) || probe.exitValue() != 0)
        cancel("Optional HiGHS Python dependency unavailable")
    } finally {
      if (probe.isAlive) {
        probe.destroyForcibly()
        probe.waitFor(5, TimeUnit.SECONDS)
      }
    }
  }
  private def empty(path: Path): Boolean = { val stream = Files.list(path); try !stream.findAny().isPresent finally stream.close() }
  private def remove(path: Path): Unit = {
    val stream = Files.walk(path)
    try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists) finally stream.close()
  }

  test("native parameters, callbacks, original mappings, sensitivity, files and revisions") {
    available()
    implicit val ss: SparkSession = spark
    val model = LpProblem("native", Maximize)
    val x = model.variable("original x", Double.NegativeInfinity, Some(2.0))
    val y = model.variable("original y", upperBound = Some(3.0))
    model += 2.0 * x + y + 7.0
    model += (x + y <= 4.0).named("original capacity")
    val root = Files.createTempDirectory("native session ")
    val session = model.prepareNative(new HighsPythonAdapter(artifacts = LpCommandOptions(temporaryRoot = Some(root))),
      LpAdapterOptions(requireDuals = true)).right.get
    val output = root.resolve("solution file.txt")
    val driverThread = Thread.currentThread()
    var callbackCount = 0
    try {
      assert(session.setParameter("presolve", "off").isRight)
      assert(session.setParameter("solver", "simplex").isRight)
      assert(session.parameter("presolve") == Right("off"))
      assert(session.setParameter("not_a_parameter", "1").isLeft)
      assert(session.parameter("not_a_parameter").isLeft)
      assert(session.setParameter("log_to_console", "true").isLeft)
      assert(session.mapping.variables.collect().map(_.originalName).toSet == Set("original x", "original y"))
      assert(session.mapping.constraints.first().originalName == "original capacity")
      assert(session.callback { event =>
        assert(Thread.currentThread() eq driverThread)
        callbackCount += 1
      }.isRight)

      intercept[LpModelException](x.setBounds(0.0, Some(1.0)))

      val result = session.solve()

      assert(result.status == LpStatus.Optimal)
      assert(math.abs(result.objectiveValue - 13.0) < 1e-8)
      assert(result.candidate.feasible)
      assert(callbackCount > 0)
      assert(session.isCurrent(result))
      assert(math.abs(result.constraints.first().getAs[Double]("dual") - 1.0) < 1e-8)
      assert(math.abs(result.reducedCost(x).get - 1.0) < 1e-8)
      assert(session.information("simplex_iteration_count").right.get.toInt > 0)
      assert(session.writeSolution(output).isRight)
      assert(Files.size(output) > 0)
      val revision = session.modelRevision

      assert(session.readSolution(output).isRight)
      assert(session.modelRevision > revision)
      assert(session.latestSolution.isEmpty)
      assert(!session.isCurrent(result))
      intercept[IllegalStateException](session.writeSolution(root.resolve("stale.txt")))

      val again = session.solve()

      assert(again.objectiveValue == result.objectiveValue)
      assert(session.isCurrent(again))
      assert(!session.isCurrent(result))
      val failure = new java.util.concurrent.atomic.AtomicReference[Throwable]()
      val thread = new Thread(new Runnable {
        override def run(): Unit = { try session.parameter("presolve") catch { case e: Throwable => failure.set(e) }; () }
      })
      // The session is confined to its driver thread; bound the rejected access.
      thread.start()
      thread.join(5000L)
      assert(!thread.isAlive, "non-driver access did not finish within five seconds")

      assert(failure.get().isInstanceOf[IllegalStateException])
      val bytes = new java.io.ByteArrayOutputStream()
      val serialization = new java.io.ObjectOutputStream(bytes)
      try intercept[java.io.NotSerializableException](serialization.writeObject(session)) finally serialization.close()
      session.close()

      intercept[IllegalStateException](session.parameter("presolve"))
      intercept[LpModelException](again.value(x))
      x.setBounds(0.0, Some(1.0))
    } finally { session.close(); remove(root) }
  }

  test("native MIP mapping, generic optional solving and unsupported session access") {
    available()
    implicit val ss: SparkSession = spark
    val model = LpProblem("native MIP", Maximize)
    val x = model.variable("x", category = Binary)
    val y = model.variable("y", upperBound = Some(3.0), category = Integer)
    model += 2.0 * x + y + 5.0
    model += (x + y <= 2.5)

    assert(model.prepareNative(new BoxAdapter).isLeft)

    val result = model.solve(new HighsPythonAdapter)
    try {
      assert(result.status == LpStatus.Optimal)
      assert(result.objectiveValue == 8.0)
      assert(result.value(x) == 1.0)
      assert(result.value(y) == 1.0)
      assert(result.backend.get.bestBound.contains(8.0))
      assert(result.reducedCost(x).isEmpty)
    } finally result.close()
    val sos = LpProblem("unsupported SOS")
    val z = sos.variable("z", upperBound = Some(1.0))
    sos.addSos1("one", Seq(z -> 1.0))

    intercept[LpModelException](sos.solve(new HighsPythonAdapter))
    intercept[LpModelException](model.solve(new BoxAdapter, LpAdapterOptions(requireDuals = true)))
  }

  test("a missing native executable leaves no artifacts and the model remains editable") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("missing native executable")
    val x = model.variable("x", upperBound = Some(2.0))
    model += x
    val root = Files.createTempDirectory("native cleanup ")
    try {
      val adapter = new HighsPythonAdapter(python = "/missing/python",
        artifacts = LpCommandOptions(temporaryRoot = Some(root)))

      intercept[java.io.IOException](model.prepareNative(adapter))

      assert(empty(root))

      // A failed preparation must release the edit lock.
      x.rename("still editable")

      assert(model.inspect.variableDeclarations.exists(_.name == "still editable"))
    } finally remove(root)
  }

  test("a callback failure closes the native session and releases the model edit lock") {
    available()
    implicit val ss: SparkSession = spark
    val model = LpProblem("native callback cleanup")
    val x = model.variable("x", upperBound = Some(2.0))
    model += x
    val root = Files.createTempDirectory("native cleanup ")
    try {
      val adapter = new HighsPythonAdapter(artifacts = LpCommandOptions(temporaryRoot = Some(root)))
      val session = model.prepareNative(adapter).fold(error => throw new IllegalStateException(error.toString), identity)
      val callbackFailure = new IllegalStateException("callback failure")
      try {
        session.callback(_ => throw callbackFailure)

        val error = intercept[IllegalStateException](session.solve())

        assert(error.getMessage.contains("callback failure"))
        assert(empty(root))
        intercept[IllegalStateException](session.latestSolution)

        x.rename("editable after exception")

        assert(model.inspect.variableDeclarations.exists(_.name == "editable after exception"))
      } finally session.close()
    } finally remove(root)
  }

  test("native cancellation has no invented candidate and unsupported operations remain explicit") {
    available()
    implicit val ss: SparkSession = spark
    val model = LpProblem("cancel native")
    val x = model.variable("x", upperBound = Some(1.0))
    model += x
    val root = Files.createTempDirectory("native cancellation ")
    val session = model.prepareNative(new HighsPythonAdapter(artifacts = LpCommandOptions(temporaryRoot = Some(root))),
      LpAdapterOptions(shouldStop = () => true)).right.get
    try {
      val result = session.solve()

      assert(result.status == LpStatus.Stopped)
      assert(!result.candidate.available)
      assert(result.backend.get.diagnostics("termination") == "UserStop")
    } finally session.close()

    assert(empty(root))
    Files.delete(root)
    val noOperations = new LpNativeAccess {
      override def mapping: LpExportMapping = throw new UnsupportedOperationException
      override def setParameter(name: String, value: String): Either[LpUnsupported, Unit] = Left(LpUnsupported(name, "unsupported"))
      override def parameter(name: String): Either[LpUnsupported, String] = Left(LpUnsupported(name, "unsupported"))
      override def information(name: String): Either[LpUnsupported, String] = Left(LpUnsupported(name, "unsupported"))
    }

    assert(noOperations.callback(_ => ()).isLeft)
    assert(noOperations.readSolution(java.nio.file.Paths.get("unused")).isLeft)
    assert(noOperations.writeSolution(java.nio.file.Paths.get("unused")).isLeft)
  }

  test("rejected native operations and callbacks preserve the current solution") {
    implicit val ss: SparkSession = spark
    val model = LpProblem("native revision fixture")
    val x = model.variable("x", upperBound = Some(1.0))
    model += x
    val access = new LpNativeAccess {
      override def mapping: LpExportMapping = throw new UnsupportedOperationException
      override def setParameter(name: String, value: String): Either[LpUnsupported, Unit] =
        Left(LpUnsupported(name, "unsupported"))
      override def parameter(name: String): Either[LpUnsupported, String] = Left(LpUnsupported(name, "unsupported"))
      override def information(name: String): Either[LpUnsupported, String] = Left(LpUnsupported(name, "unsupported"))
      override def callback(handler: LpNativeEvent => Unit): Either[LpUnsupported, Unit] = Right(())
    }
    val adapter = new LpSolverAdapter {
      override val name = "native-revision-fixture"
      override val capabilities = LpSolverCapabilities(nativeSession = true)
      override def prepare(view: LpModelView, options: LpAdapterOptions): LpAdapterSession = new LpAdapterSession {
        override def solve(): LpAdapterResult =
          LpAdapterResult(LpStatus.Optimal, Some(model.candidateValues(Seq(x -> 0.0))))
        override def nativeAccess: Option[LpNativeAccess] = Some(access)
        override def close(): Unit = ()
      }
    }
    val session = model.prepareNative(adapter).right.get
    try {
      val result = session.solve()
      val revision = session.modelRevision

      assert(session.setParameter("unsupported", "1").isLeft)
      assert(session.readSolution(java.nio.file.Paths.get("unused")).isLeft)
      assert(session.callback(_ => ()).isRight)
      assert(session.modelRevision == revision)
      assert(session.isCurrent(result))
    } finally session.close()
  }
}
