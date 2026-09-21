import sbt.*

/**
 * sbt project-axis identity for one supported Spark line.
 *
 * @param idSuffix suffix added to generated sbt project identifiers.
 * @param directorySuffix suffix used for axis-specific target directories.
 * @param sparkVersion complete Spark dependency version.
 */
case class SparkAxis(idSuffix: String, directorySuffix: String, sparkVersion: String) extends VirtualAxis.WeakAxis

/**
 * Dependency coordinates derived for one supported Spark version.
 *
 * @param sparkFull complete Spark dependency version.
 * @param sparkShort major-minor Spark line used in project names.
 * @param sparkTestingBase compatible spark-testing-base version suffix.
 */
case class SparkLibs(
  sparkFull: String,
  sparkShort: String,
  sparkTestingBase: String,
) {

  lazy val sparkLibs: Seq[ModuleID] = Seq(
    "org.apache.spark" %% "spark-core" % sparkFull,
    "org.apache.spark" %% "spark-sql" % sparkFull,
    "org.apache.spark" %% "spark-mllib" % sparkFull)

  lazy val sparkTestingBaseLib = ("com.holdenkarau" %% "spark-testing-base" % sparkTestingBase excludeAll ExclusionRule(organization = "org.apache.zookeeper") exclude("log4j", "*")) % Test
}

object SparkLibs {

  lazy val sparkVersions: Seq[SparkLibs] = Seq("2.4.8", "3.0.2", "3.1.3", "3.2.4", "3.3.2", "3.4.2", "3.5.3")
    .map(version => SparkLibs(
      sparkFull = version,
      sparkShort = version.split('.').take(2).mkString("."),
      sparkTestingBase = s"${version}_2.0.1"
    ))

  lazy val sparkAxes: Seq[(SparkLibs, SparkAxis)] = sparkVersions
    .map(v => (v, SparkAxis(
      idSuffix = "Spark_" + v.sparkShort.replace(".", "_"),
      directorySuffix = "spark_" + v.sparkShort,
      sparkVersion = v.sparkFull
    )))
}
