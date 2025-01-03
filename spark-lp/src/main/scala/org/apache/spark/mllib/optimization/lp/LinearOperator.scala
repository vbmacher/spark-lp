package org.apache.spark.mllib.optimization.lp

/**
  * A linear operator trait, with support for applying the operator and getting its adjoint.
  *
  * @tparam X Type representing an input vector.
  * @tparam Y Type representing an output vector.
  */
trait LinearOperator[X, Y] {

  /**
    * Apply the operator for an operand.
    *
    * @param x The vector on which to apply the operator.
    * @return The result of applying the operator on x.
    */
  def apply(x: X): Y
}
