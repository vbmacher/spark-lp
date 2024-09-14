import SparkLibs._


// https://github.com/sbt/sbt/issues/5849
Global / lintUnusedKeysOnLoad := false

ThisBuild / organization := "com.github.vbmacher"
ThisBuild / version := "1.1-SNAPSHOT"

// Because of https://mvnrepository.com/artifact/io.github.spark-redshift-community/spark-redshift we can use only 2.12
// https://xebia.com/blog/using-scala-3-with-spark/
ThisBuild / scalaVersion := "2.12.18"
ThisBuild / name := "spark-lp"
ThisBuild / autoAPIMappings := true

lazy val emr = SPARK_3_5_0

lazy val root = project.in(file("."))
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
    libraryDependencies ++= emr.sparkLibs ++ Libs.scalaTestLibs ++ Seq(
      emr.sparkTestingBaseLib,
      Libs.jOptimizer,
      Libs.netlib,
      Libs.scalaLogging,
      Libs.log4jImpl),
    Test / parallelExecution := false
  )
