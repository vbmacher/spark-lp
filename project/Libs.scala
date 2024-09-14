import sbt.*

object Libs {

  val jOptimizer = "com.joptimizer" % "joptimizer" % "3.4.0" // 5.0.0
  val netlib = "com.github.fommil.netlib" % "all" % "1.1.2" pomOnly()

  // Logging
  val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5" exclude("log4j", "*")
  val log4jImpl = "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.23.1" % Test

  // Testing
  val scalaTestLibs: Seq[ModuleID] = Seq(
    "org.scalacheck" %% "scalacheck" % "1.18.0",
    "org.scalatest" %% "scalatest" % "3.2.19",
    "org.scalatest" %% "scalatest-funspec" % "3.2.19",
    "org.scalamock" %% "scalamock" % "6.0.0",
    "org.scalatestplus" %% "scalacheck-1-15" % "3.2.11.0",
    "org.scalatestplus" %% "junit-4-13" % "3.2.19.0",
  ).map(_ % Test)
}
