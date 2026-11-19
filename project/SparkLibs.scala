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
 */
case class SparkLibs(
  sparkFull: String,
  sparkLine: String,
  sparkTestingBase: String,
) {

  lazy val sparkLibs: Seq[ModuleID] = Seq(
    "org.apache.spark" %% "spark-core" % sparkFull,
    "org.apache.spark" %% "spark-sql" % sparkFull,
    "org.apache.spark" %% "spark-mllib" % sparkFull)

  lazy val sparkTestingBaseLib = ("com.holdenkarau" %% "spark-testing-base" % sparkTestingBase excludeAll ExclusionRule(organization = "org.apache.zookeeper") exclude("log4j", "*")) % Test
}

object SparkLibs {

  private def spark(line: String, patch: Int, testingBaseRelease: String = "2.0.1"): SparkLibs = {
    val version = s"$line.$patch"
    SparkLibs(version, line, s"${version}_$testingBaseRelease")
  }

  lazy val sparkVersions: Seq[SparkLibs] = Seq(
    spark("2.4", 8),
    spark("3.0", 2),
    spark("3.1", 3),
    spark("3.2", 4),
    spark("3.3", 2),
    spark("3.4", 2),
    spark("3.5", 6, testingBaseRelease = "2.1.3"))

  lazy val sparkAxes: Seq[(SparkLibs, SparkAxis)] = sparkVersions
    .map(v => (v, SparkAxis(
      idSuffix = "Spark_" + v.sparkLine.replace(".", "_"),
      directorySuffix = "spark_" + v.sparkLine
    )))
}
