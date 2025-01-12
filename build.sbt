import SparkLibs._

// https://github.com/sbt/sbt/issues/5849
Global / lintUnusedKeysOnLoad := false

ThisBuild / name := "spark-lp"
ThisBuild / organization := "com.github.vbmacher"

ThisBuild / scalaVersion := "2.12.18"
ThisBuild / autoAPIMappings := true

licenses += "Apache-2.0" -> url("https://opensource.org/license/apache-2-0")

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
      autoScalaLibrary = true,
      axisValues = Seq(ax._2, VirtualAxis.jvm),
      _.settings(
        libraryDependencies ++= ax._1.sparkLibs.flatMap(r => Seq(r % Test, r % Provided)) ++ Seq(ax._1.sparkTestingBaseLib),
        name := "spark-lp",
        version := {
          val sparkVersion = ax._2.directorySuffix
          s"${sparkVersion}_1.0-SNAPSHOT"
        }
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
          )
        )

lazy val root = (project in file("."))
        .aggregate(`spark-lp`.projectRefs ++ examples.projectRefs: _*)
