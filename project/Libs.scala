import sbt.*

object Libs {

  val jOptimizer = "com.joptimizer" % "joptimizer" % "5.0.0"
  val netlib = "com.github.fommil.netlib" % "all" % "1.1.2" pomOnly()

  // Logging
  val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5" exclude("log4j", "*")
  val log4jImpl = "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.24.3"

  // Testing
  val scalaTestLibs: Seq[ModuleID] = Seq(
    "org.scalatest" %% "scalatest" % "3.2.19",
    "org.scalatestplus" %% "junit-4-13" % "3.2.19.0",
  ).map(_ % Test)
}
