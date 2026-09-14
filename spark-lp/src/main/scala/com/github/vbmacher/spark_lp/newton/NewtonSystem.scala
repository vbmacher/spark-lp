package com.github.vbmacher.spark_lp.newton

import org.apache.spark.mllib.linalg.DenseVector

/** A prepared solver for one normal-equations matrix, reusable for several right-hand sides. */
private[spark_lp] trait NewtonSystem {

  /** Solves the system for `rhs`, leaving `rhs` unmodified. */
  final def solve(rhs: DenseVector): DenseVector = solve(rhs, 0.0)

  /**
    * Solves the system for `rhs`, leaving `rhs` unmodified. `absTolerance` is the absolute
    * residual norm targeted by an iterative implementation (`0.0` uses only its relative
    * target); direct implementations ignore it. CgFactory documents its inexact fallback.
    */
  def solve(rhs: DenseVector, absTolerance: Double): DenseVector

  /** Frees any resources (e.g. broadcasts) held by the prepared system. */
  def release(): Unit
}
