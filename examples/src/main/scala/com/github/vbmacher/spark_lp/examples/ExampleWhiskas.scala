package com.github.vbmacher.spark_lp.examples

import com.github.vbmacher.spark_lp.LP
import com.github.vbmacher.spark_lp.vectors.DVector
import org.apache.spark.mllib.linalg.{DenseVector, Vectors}
import org.apache.spark.sql.SparkSession

object ExampleWhiskas extends App {

  // This example is taken from PuLP
  // https://coin-or.github.io/pulp/CaseStudies/a_blending_problem.html

  // Whiskas cat food, shown above, is manufactured by Uncle Ben’s. Uncle Ben’s want to produce their cat food products
  // as cheaply as possible while ensuring they meet the stated nutritional analysis requirements shown on the cans.
  // Thus they want to vary the quantities of each ingredient used (the main ingredients being chicken, beef, mutton,
  // rice, wheat and gel) while still meeting their nutritional standards.

  // The costs of the chicken, beef, and mutton are $0.013, $0.008 and $0.010 respectively, while the costs of the rice,
  // wheat and gel are $0.002, $0.005 and $0.001 respectively. (All costs are per gram.) For this exercise we will ignore
  // the vitamin and mineral ingredients. (Any costs for these are likely to be very small anyway.)

  // Each ingredient contributes to the total weight of protein, fat, fibre and salt in the final product.
  // The contributions (in grams) per gram of ingredient are given in the table below.

  // Ingredient   Protein   Fat   Fibre   Salt
  // Chicken        0.100   0.080  0.001  0.002
  // Beef           0.200   0.100  0.005  0.005
  // Mutton         0.150   0.110  0.003  0.007
  // Rice           0.000   0.010  0.100  0.002
  // Wheat          0.040   0.010  0.150  0.008
  // Gel            0.000   0.000  0.000  0.000

  // # Identify the Decision Variables
  // Assume Whiskas want to make their cat food out of just two ingredients: Chicken and Beef. We will first define our
  // decision variables:
  //    x1 - percentage of Chicken in the cat food
  //    x2 - percentage of Beef in the cat food
  //
  // The limitations on these variables (greater than zero) must be noted but for the Python implementation, they are
  // not entered or listed separately or with the other constraints.

  // The objective function becomes:
  //   min 0.013*x1 + 0.008*x2

  // The constraints on the variables are that they must sum to 100 and that the nutritional requirements are met:
  //
  //   1.000*x1 + 1.000*x2 = 100.0  (sum of percentages)
  //   0.100*x1 + 0.200*x2 >= 8.0   (protein)
  //   0.080*x1 + 0.100*x2 >= 6.0   (fat)
  //   0.001*x1 + 0.005*x2 <= 2.0   (fibre)
  //   0.002*x1 + 0.005*x2 <= 0.4   (salt)

  // spark-lp is able to solve only linear programs in standard form:
  //    minimize c^T x
  //    subject to Ax=b and x >= 0
  //
  // Therefore, we need to introduce slack variables to support inequalities - replace the nutrition conditions with slacks:
  //
  //   0.100*x1 + 0.200*x2 - s1 = 8.0
  //   0.080*x1 + 0.100*x2 - s2 = 6.0
  //   0.001*x1 + 0.005*x2 + s3 = 2.0
  //   0.002*x1 + 0.005*x2 + s4 = 0.4

  // Now we will have 4 decision variables: x1, x2, s1, s2, s3, s4

  // Our matrix A will be:
  //   1.000  1.000  0.000  0.000  0.000  0.000
  //   0.100  0.200 -1.000  0.000  0.000  0.000
  //   0.080  0.100  0.000 -1.000  0.000  0.000
  //   0.001  0.005  0.000  0.000  1.000  0.000
  //   0.002  0.005  0.000  0.000  0.000  1.000

  // Our vector b will be: 100.0, 8.0, 6.0, 2.0, 0.4

  // Final version of the problem will be:

  // Minimize c^T x = [0.013, 0.008, 0.0, 0.0, 0.0, 0.0] dot x
  // Subject to:
  // [                                                  [           [
  //   1.000  1.000  0.000  0.000  0.000  0.000           x1          100.0
  //   0.100  0.200 -1.000  0.000  0.000  0.000           x2          8.0
  //   0.080  0.100  0.000 -1.000  0.000  0.000   dot     s1    =     6.0        , x >= 0
  //   0.001  0.005  0.000  0.000  1.000  0.000           s2          2.0
  //   0.002  0.005  0.000  0.000  0.000  1.000           s3          0.4
  // ]                                                    s4 ]      ]

  implicit val spark: SparkSession = SparkSession.builder
    .appName("ExampleRandomLP")
    .master("local[2]")
    .getOrCreate()

  val numPartitions = 2
  val cArray = Array(0.013, 0.008, 0.0, 0.0, 0.0, 0.0) // 0.013*x1 + 0.008*x2
  val ATArray = Array(
    Array(1.000, 0.100, 0.080, 0.001, 0.002),
    Array(1.000, 0.200, 0.100, 0.005, 0.005),
    Array(0.000, -1.000, 0.000, 0.000, 0.000),
    Array(0.000, 0.000, -1.000, 0.000, 0.000),
    Array(0.000, 0.000, 0.000, 1.000, 0.000),
    Array(0.000, 0.000, 0.000, 0.000, 1.000)
  )
  val bArray = Array(100.0, 8.0, 6.0, 2.0, 0.4)

  val c = spark.sparkContext.parallelize(cArray, numPartitions).glom.map(new DenseVector(_))
  val rows = spark.sparkContext.parallelize(ATArray, numPartitions).map(Vectors.dense)
  val b = new DenseVector(bArray)

  val (v, x): (Double, DVector) = LP.solve(c, rows, b)
  val xx = Vectors.dense(x.flatMap(_.toArray).collect())

  // Results from PuLP:
  //   Status: Optimal
  //   BeefPercent = 66.0
  //   ChickenPercent = 34.0
  //   Total Cost of Ingredients per can =  0.97

  val beefPercent = xx(1)
  val chickenPercent = xx(0)

  println(s"Beef percent: $beefPercent")
  println(s"Chicken percent: $chickenPercent")
  println(s"Total Cost: $v")
}
