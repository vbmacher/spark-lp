import SparkLibs._
import xerial.sbt.Sonatype.sonatypeCentralHost

// https://github.com/sbt/sbt/issues/5849
Global / lintUnusedKeysOnLoad := false

ThisBuild / name := "spark-lp"
ThisBuild / organization := "com.github.vbmacher"
ThisBuild / homepage := Some(url("https://github.com/vbmacher/spark-lp"))
ThisBuild / versionScheme := Some("semver-spec")

// CHANGE VERSION HERE:
lazy val productVersion = "2.2.0"
ThisBuild / version := productVersion // needs to be defined at root, so isSnapshot setting is properly set

ThisBuild / scalaVersion := scala212
ThisBuild / autoAPIMappings := true

ThisBuild / description := "Linear, mixed-integer linear, and continuous convex quadratic programming using Apache Spark."
ThisBuild / licenses += "Apache-2.0" -> url("https://opensource.org/license/apache-2-0")

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/vbmacher/spark-lp"),
    "scm:git@github.com:vbmacher/spark-lp.git"))

ThisBuild / developers := List(
  Developer(
    id = "vbmacher",
    name = "Peter Jakubčo",
    email = "pjakubco@gmail.com",
    url = url("https://github.com/vbmacher")),

  Developer(
    id = "ehsanmok",
    name = "Ehsan Mohyedin Kermani",
    email = "ehsanmo1367@gmail.com",
    url = url("https://github.com/ehsanmok")))

ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / sonatypeCredentialHost := sonatypeCentralHost
ThisBuild / publishTo := sonatypePublishToBundle.value
ThisBuild / publishMavenStyle := true

credentials += Credentials(Path.userHome / ".sbt" / "sonatype.sbt")

lazy val forkedJvmOptions = Seq("-Xms4G", "-Xmx4G")
// sbt starts the test JVM directly, so apply the module opens used by the Spark launcher.
lazy val spark4TestModuleOptions = Seq(
  "java.base/java.lang", "java.base/java.lang.invoke", "java.base/java.lang.reflect",
  "java.base/java.io", "java.base/java.net", "java.base/java.nio", "java.base/java.util",
  "java.base/java.util.concurrent", "java.base/java.util.concurrent.atomic", "java.base/jdk.internal.ref",
  "java.base/sun.nio.ch", "java.base/sun.nio.cs", "java.base/sun.security.action",
  "java.base/sun.util.calendar", "java.security.jgss/sun.security.krb5"
).map(pkg => s"--add-opens=$pkg=ALL-UNNAMED")

lazy val sparkLpAssemblyMergeStrategy = (path: String) => path match {
  case PathList("META-INF", "services", _*) => MergeStrategy.concat
  case PathList("META-INF", _*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}

lazy val `spark-lp` = sparkVersions.foldLeft(projectMatrix
        .settings(
          scalacOptions ++= Seq("-target:jvm-1.8", "-Xlint:_", "-language:experimental.macros", "-feature"),
          javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint"),
          javaOptions ++= forkedJvmOptions,
          // Tiny fixtures need multiple partitions, but not one task per host core or 200 shuffle tasks.
          Test / javaOptions ++= Seq("-Dspark.default.parallelism=2", "-Dspark.sql.shuffle.partitions=2"),
          fork := true,
          assembly / assemblyMergeStrategy := sparkLpAssemblyMergeStrategy,
          libraryDependencies ++= Libs.scalaTestLibs ++ Seq(
            Libs.netlib,
            Libs.scalaLogging,
            Libs.log4jImpl % Test),
          Test / parallelExecution := false
        )) {

  case (matrix, spark) =>
    matrix.customRow(
      scalaVersions = Seq(spark.scalaVersion),
      axisValues = Seq(spark.axis, VirtualAxis.jvm),
      _.settings(
        libraryDependencies ++= spark.sparkLibs.flatMap(r => Seq(r % Test, r % Provided)) ++ spark.sparkTestLibs,
        Test / javaOptions ++= (if (spark.isSpark4) spark4TestModuleOptions else Nil),
        name := "spark-lp",
        version := s"${spark.sparkLine}_$productVersion",
      ))
}

lazy val examples = projectMatrix
        .dependsOn(`spark-lp`)
        .customRow(
          autoScalaLibrary = true,
          axisValues = Seq(spark35.axis, VirtualAxis.jvm),
          _.settings(
            name := "examples",
            fork := true,
            libraryDependencies ++= Libs.jOptimizer +: spark35.sparkLibs,
            publishArtifact := false))

lazy val benchmarks = projectMatrix
        .dependsOn(`spark-lp`)
        .customRow(
          scalaVersions = Seq(spark35.scalaVersion),
          axisValues = Seq(spark35.axis, VirtualAxis.jvm),
          _.settings(
            name := "benchmarks",
            fork := true,
            Test / baseDirectory := (LocalRootProject / baseDirectory).value,
            Test / parallelExecution := false,
            javaOptions ++= forkedJvmOptions,
            libraryDependencies ++= spark35.sparkLibs.flatMap(r => Seq(r % Provided, r % Test)) ++
              Seq(Libs.log4jImpl % Test) ++ Libs.scalaTestLibs,
            assembly / assemblyMergeStrategy := sparkLpAssemblyMergeStrategy,
            publish / skip := true,
            publishArtifact := false))

lazy val root = (project in file("."))
        .aggregate(`spark-lp`.projectRefs ++ examples.projectRefs ++ benchmarks.projectRefs: _*)
        .settings(publishArtifact := false)
