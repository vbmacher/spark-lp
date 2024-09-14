import SparkLibs._

name := "spark-lp"
organization := "com.github.vbmacher"
version := "1.1-SNAPSHOT"
licenses += "Apache-2.0" -> url("http://opensource.org/licenses/Apache-2.0")

scalaVersion := "2.12.18"
autoAPIMappings := true

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
          libraryDependencies ++= spark.sparkLibs ++ Libs.scalaTestLibs ++ Seq(
            spark.sparkTestingBaseLib,
            Libs.netlib,
            Libs.scalaLogging,
            Libs.log4jImpl),
          Test / parallelExecution := false
        )

lazy val examples = project
        .settings(
          libraryDependencies ++= spark.sparkLibs ++ Seq(Libs.jOptimizer))
        .dependsOn(`spark-lp`)

lazy val root = (project in file("."))
        .aggregate(`spark-lp`, examples)
