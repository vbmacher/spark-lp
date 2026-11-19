import sbt.*

/**
 * sbt project-axis identity for one supported Spark line.
 *
 * @param idSuffix suffix added to generated sbt project identifiers.
 * @param directorySuffix suffix used for axis-specific target directories.
 */
case class SparkAxis(idSuffix: String, directorySuffix: String) extends VirtualAxis.WeakAxis

/**
 * Dependency coordinates for one supported Spark line.
 *
 * @param sparkFull complete Spark dependency version.
 * @param sparkLine major.minor Spark line used in project and artifact names.
 * @param sparkTestingBase compatible spark-testing-base version.
 * @param scalaVersion Scala compiler version for this Spark line.
 */
case class SparkLibs(
  sparkFull: String,
  sparkLine: String,
  sparkTestingBase: String,
  scalaVersion: String,
) {

  def isSpark4: Boolean = sparkLine.startsWith("4.")

  lazy val axis: SparkAxis = SparkAxis(
    idSuffix = "Spark_" + sparkLine.replace(".", "_"),
    directorySuffix = "spark_" + sparkLine)

  lazy val sparkLibs: Seq[ModuleID] = Seq(
    "org.apache.spark" %% "spark-core" % sparkFull,
    "org.apache.spark" %% "spark-sql" % sparkFull,
    "org.apache.spark" %% "spark-mllib" % sparkFull)

  lazy val sparkTestLibs: Seq[ModuleID] = {
    val fixture = ("com.holdenkarau" %% "spark-testing-base" % sparkTestingBase)
      .exclude("org.apache.zookeeper", "*").exclude("log4j", "*") % Test
    if (isSpark4) Seq(
      fixture.exclude("org.apache.spark", "*"),
      "org.apache.spark" %% "spark-hive" % sparkFull % Test)
    else Seq(fixture)
  }
}

object SparkLibs {

  val scala212 = "2.12.20"
  val scala213 = "2.13.18"

  private def spark(line: String, patch: Int, testingBaseRelease: String = "2.0.1"): SparkLibs = {
    val version = s"$line.$patch"
    SparkLibs(version, line, s"${version}_$testingBaseRelease", scala212)
  }

  // Reuse the Spark 4 test fixture while resolving Spark from each build row.
  private def spark4(line: String, patch: Int): SparkLibs =
    SparkLibs(s"$line.$patch", line, "4.0.1_2.1.3", scala213)

  lazy val spark35: SparkLibs = spark("3.5", 6, testingBaseRelease = "2.1.3")

  lazy val sparkVersions: Seq[SparkLibs] = Seq(
    spark("2.4", 8),
    spark("3.0", 2),
    spark("3.1", 3),
    spark("3.2", 4),
    spark("3.3", 2),
    spark("3.4", 2),
    spark35,
    spark4("4.0", 5),
    spark4("4.1", 4),
    spark4("4.2", 1),
    spark4("4.3", 0))
}
