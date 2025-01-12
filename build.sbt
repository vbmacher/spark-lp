import SparkLibs._

// https://github.com/sbt/sbt/issues/5849
Global / lintUnusedKeysOnLoad := false

ThisBuild / name := "spark-lp"
ThisBuild / organization := "com.github.vbmacher"
ThisBuild / homepage := Some(url("https://github.com/vbmacher/spark-lp"))
ThisBuild / versionScheme := Some("semver-spec")

// CHANGE VERSION HERE:
lazy val productVersion = "1.0.0"
ThisBuild / version := productVersion // needs to be defined at root, so isSnapshot setting is properly set

lazy val scalaLibVersion = "2.12.18"
ThisBuild / scalaVersion := scalaLibVersion
ThisBuild / autoAPIMappings := true

ThisBuild / description := "Library for solving large-scale linear programming using Apache Spark."
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
ThisBuild / sonatypeCredentialHost := "oss.sonatype.org"
ThisBuild / sonatypeRepository := "https://oss.sonatype.org/service/local"
ThisBuild / publishTo := sonatypePublishToBundle.value
ThisBuild / publishMavenStyle := true

credentials += Credentials(Path.userHome / ".sbt" / "sonatype.sbt")


lazy val `spark-lp` = sparkAxes.foldLeft(projectMatrix
        .settings(
          scalacOptions ++= Seq("-target:jvm-1.8", "-Xlint:_", "-language:experimental.macros", "-feature"),
          javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint"),
          javaOptions ++= Seq("-Xms4G", "-Xmx4G"),
          fork := true,
          assembly / assemblyMergeStrategy := {
            case PathList("META-INF", "services", _*) => MergeStrategy.concat
            case PathList("META-INF", _*) => MergeStrategy.discard
            case _ => MergeStrategy.first
          },
          libraryDependencies ++= Libs.scalaTestLibs ++ Seq(
            Libs.netlib,
            Libs.scalaLogging,
            Libs.log4jImpl % Test),
          Test / parallelExecution := false
        )) {

  case (matrix, ax) =>
    matrix.customRow(
      scalaVersions = Seq(scalaLibVersion),
      axisValues = Seq(ax._2, VirtualAxis.jvm),
      _.settings(
        libraryDependencies ++= ax._1.sparkLibs.flatMap(r => Seq(r % Test, r % Provided)) ++ Seq(ax._1.sparkTestingBaseLib),
        name := "spark-lp",
        version := {
          val sparkVersion = ax._2.sparkVersion
          s"${sparkVersion}_$productVersion"
        },
      ))
}

lazy val examples = projectMatrix
        .dependsOn(`spark-lp`)
        .customRow(
          autoScalaLibrary = true,
          axisValues = Seq(sparkAxes.last._2, VirtualAxis.jvm),
          _.settings(
            name := "examples",
            libraryDependencies ++= Libs.jOptimizer +: sparkAxes.last._1.sparkLibs,
            publishArtifact := false))

lazy val root = (project in file("."))
        .aggregate(`spark-lp`.projectRefs ++ examples.projectRefs: _*)
        .settings(publishArtifact := false)
