import sbt.*


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

  lazy val SPARK_3_3_0: SparkLibs = SparkLibs(
    sparkFull = "3.3.0",
    sparkShort = "3.3",
    sparkTestingBase = "3.3.0_1.4.3")

  lazy val SPARK_3_3_2: SparkLibs = SparkLibs(
    sparkFull = "3.3.2",
    sparkShort = "3.3",
    sparkTestingBase = "3.3.2_1.4.3")

  lazy val SPARK_3_4_1: SparkLibs = SparkLibs(
    sparkFull = "3.4.1",
    sparkShort = "3.4",
    sparkTestingBase = "3.4.1_1.4.7")

  lazy val SPARK_3_5_0: SparkLibs = SparkLibs(
    sparkFull = "3.5.0",
    sparkShort = "3.5",
    sparkTestingBase = "3.5.0_1.4.7")
}

