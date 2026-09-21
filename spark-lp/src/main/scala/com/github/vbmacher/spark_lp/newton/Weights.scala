package com.github.vbmacher.spark_lp.newton

import com.github.vbmacher.spark_lp.vectors.DVector
import com.github.vbmacher.spark_lp.vectors.dvector.implicits._

/**
  * The scaling weights of one interior-point iteration, both partitioned consistently with the
  * constraint matrix. `sqrt` (the iteration's `D`) feeds the Cholesky path, which scales matrix
  * rows before aggregating the Gramian; `squared` (the iteration's `D2 = D^2`) feeds the
  * matrix-free path, which applies the diagonal between the two products. The solver derives
  * `sqrt` from the validated squared weights. CG uses `(S/X + Rp)^(-1)`; the direct
  * reference uses the historical inverse-slack cap.
  *
  * @param sqrt per-variable scaling vector used to scale constraint-matrix rows.
  * @param squared elementwise square of `sqrt`, used by matrix-free products and direction recovery.
  */
private[spark_lp] final case class Weights(sqrt: DVector, squared: DVector) {
  /**
    * Recovers the primal (`dx`) and slack (`ds`) search directions once the dual direction is
    * known, for the KKT system `A dx + Rd dy = -rb`, `A^T dy + ds - Rp dx = -rc`,
    * `S dx + X ds = q`, with `h = rc + q / x` and `W = (S/X + Rp)^(-1)`.
    *
    * @param h                    combined right-hand side `rc + q / x`.
    * @param rc                   dual-feasibility residual.
    * @param aty                  the product `A^T dy` of the already recovered dual direction.
    * @param primalRegularization the primal regularization `Rp` folded into the slack update.
    * @return the pair `(dx, ds)` of primal and slack directions.
    */
  def recoverDirections(h: DVector, rc: DVector, aty: DVector,
    primalRegularization: Double): (DVector, DVector) = {
    val dx = squared.entrywiseProd(aty.combine(1.0, 1.0, h))
    val ds = rc.combine(-1.0, -1.0, aty).combine(1.0, primalRegularization, dx)
    (dx, ds)
  }
}
