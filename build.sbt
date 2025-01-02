import SparkLibs._

// https://github.com/sbt/sbt/issues/5849
Global / lintUnusedKeysOnLoad := false

ThisBuild / name := "spark-lp"
ThisBuild / organization := "com.github.ehsanmok"
ThisBuild / version := "spark_3.5-1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.12.18"
ThisBuild / autoAPIMappings := true

licenses += "Apache-2.0" -> url("http://opensource.org/licenses/Apache-2.0")

lazy val spark = SPARK_3_5_0

lazy val `spark-lp` = project
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
          libraryDependencies ++= spark.sparkLibs.flatMap(r => Seq(r % Test, r % Provided)) ++ Libs.scalaTestLibs ++ Seq(
            spark.sparkTestingBaseLib,
            Libs.netlib,
            Libs.scalaLogging,
            Libs.log4jImpl % Test),
          Test / parallelExecution := false
        )

lazy val examples = project
        .settings(
          libraryDependencies ++= spark.sparkLibs ++ Seq(Libs.jOptimizer))
        .dependsOn(`spark-lp`)

lazy val root = (project in file("."))
        .aggregate(`spark-lp`, examples)
