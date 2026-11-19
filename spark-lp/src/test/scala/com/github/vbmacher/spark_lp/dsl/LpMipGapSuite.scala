package com.github.vbmacher.spark_lp.dsl

import org.scalatest.funsuite.AnyFunSuite

class LpMipGapSuite extends AnyFunSuite {
  test("an absolute gap exactly at its tolerance is accepted") {
    val config = MipConfig(gapTolerance = 0.0, absoluteGapTolerance = 1.0)

    val accepted = MipGap.accepted(10.0, 9.0, 10.0, config)

    assert(accepted)
  }

  test("the relative gap uses the magnitude of a negative objective") {
    val config = MipConfig(gapTolerance = 0.01, absoluteGapTolerance = 0.0)

    val accepted = MipGap.accepted(10.0, 9.0, -100.0, config)

    assert(accepted)
  }

  test("a gap meeting both tolerances is accepted") {
    val config = MipConfig(gapTolerance = 0.01, absoluteGapTolerance = 1.0)

    val accepted = MipGap.accepted(10.0, 9.0, 100.0, config)

    assert(accepted)
  }

  test("a gap above both tolerances is rejected when the objective is zero") {
    val config = MipConfig(gapTolerance = 0.1, absoluteGapTolerance = 0.5)

    val accepted = MipGap.accepted(10.0, 9.0, 0.0, config)

    assert(!accepted)
  }

  test("an absolute tolerance does not accept a larger gap when relative tolerance is zero") {
    val config = MipConfig(gapTolerance = 0.0, absoluteGapTolerance = 1.0)

    val accepted = MipGap.accepted(100.0, 90.0, 100.0, config)

    assert(!accepted)
  }

  test("an unavailable bound cannot satisfy even a large absolute gap tolerance") {
    val unavailableBounds = Seq(Double.NaN, Double.NegativeInfinity, Double.PositiveInfinity)
    val config = MipConfig(absoluteGapTolerance = 1e9)

    unavailableBounds.foreach { bound =>
      val accepted = MipGap.accepted(1.0, bound, 1.0, config)

      assert(!accepted, s"unavailable bound $bound")
    }
  }

  test("absolute gap tolerance rejects negative and non-finite values") {
    val invalidTolerances = Seq(-1.0, Double.NaN, Double.PositiveInfinity)

    invalidTolerances.foreach { tolerance =>
      withClue(s"tolerance = $tolerance: ") {
        intercept[IllegalArgumentException](MipConfig(absoluteGapTolerance = tolerance))
      }
    }
  }
}
