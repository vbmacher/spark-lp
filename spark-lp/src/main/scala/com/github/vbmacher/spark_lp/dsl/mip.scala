package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.LP
import com.github.vbmacher.spark_lp.dsl.LpCompiler.Compiled
import com.github.vbmacher.spark_lp.vectors.DVector
import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.sql.SparkSession

import scala.collection.mutable

/**
  * Branch-and-bound search over the integral columns of a compiled model.
  *
  * The model is compiled exactly once: every node reuses the shared distributed cost vector and
  * constraint matrix and differs only in the driver-local RHS. Tightening an integral column to
  * `[L, U]` folds into the equality form as an extra lower-bound shift (`b(r) -= a_rj * (L - L0)`
  * for every constraint row the column touches) plus a new RHS for the column's upper-bound row
  * (`U - L`). Nodes are explored best-first (smallest relaxation bound first) with
  * most-fractional branching.
  *
  * Status truthfulness:
  *   - a node is discarded as infeasible only on a Farkas certificate ([[LP.Termination.PrimalInfeasible]]);
  *   - `Optimal` is claimed only when the tree is exhausted, every node was resolved exactly, and
  *     no better solution can exist beyond `gapTolerance`;
  *   - `Unbounded` is claimed only for a node with a dual-infeasibility certificate whose iterate
  *     is primal-feasible with integral values;
  *   - anything unresolved (node budget, an unresolved node relaxation, a numerical failure in a
  *     non-root node) degrades the final status to `IterationLimit`; a numerical failure at the
  *     root propagates, matching the continuous path.
  */
private[dsl] final class BranchAndBound(
  compiler: LpCompiler,
  compiled: Compiled,
  config: SolveConfig)(implicit spark: SparkSession) {

  import BranchAndBound._

  private val intCols = compiled.intCols
  private val n = intCols.size
  private val rootLower: Array[Double] = intCols.map(_.rootLower).toArray
  private val intGSet = spark.sparkContext.broadcast(intCols.map(_.g).toSet)

  private var incumbent: Option[Candidate] = None
  private var unboundedProof: Option[Candidate] = None
  private var rootX: DVector = _
  private var rootSummary: LP.SolveSummary = _
  private var sawUnboundedNode = false
  // false once any node is left unresolved: Optimal / Infeasible can no longer be claimed
  private var exact = true
  private var solvedNodes = 0
  private var totalIterations = 0

  def solve(): LpSolution = {
    val open = mutable.PriorityQueue.empty[Node](Ordering.by[Node, Double](_.bound).reverse)
    open.enqueue(Node(
      lower = intCols.map(_.rootLower).toArray,
      upper = intCols.map(_.rootUpper).toArray,
      bound = Double.NegativeInfinity))

    while (open.nonEmpty && unboundedProof.isEmpty && solvedNodes < DefaultMaxNodes) {
      val node = open.dequeue()
      if (!prunable(node.bound)) {
        processNode(node, open)
      }
    }

    // outstanding nodes below the incumbent bound do not compromise optimality
    val searchComplete = unboundedProof.isEmpty && open.forall(node => prunable(node.bound))
    assemble(searchComplete)
  }

  private def prunable(bound: Double): Boolean = incumbent.exists { inc =>
    bound >= inc.objMin - GapTolerance * math.max(1.0, math.abs(inc.objMin))
  }

  private def processNode(node: Node, open: mutable.PriorityQueue[Node]): Unit = {
    val isRoot = solvedNodes == 0
    val summary =
      try {
        LP.solveSummary(
          c = compiled.c,
          AT = compiled.AT,
          b = nodeRhs(node),
          tolerance = config.tolerance,
          maxIter = config.maxIterations,
          etaIter = config.etaIteration,
          valueCap = config.valueCap,
          eps = config.epsilon,
          infeasibilityTolerance = config.infeasibilityTolerance,
          solver = config.resolvedNewtonSolver(compiled.numRows),
          cgTolerance = config.cgTolerance,
          cgMaxIterations = config.cgMaxIterations)
      } catch {
        case e: LpNumericalException =>
          if (isRoot) throw e
          exact = false // the node stays unresolved; a better solution may hide in it
          return
      }
    solvedNodes += 1
    totalIterations += summary.iterations
    if (isRoot) {
      rootX = summary.x
      rootSummary = summary
    }

    // full solver-form objective of the node iterate, comparable across nodes
    val objMin = summary.objectiveValue + shiftCost(node)

    summary.termination match {
      case LP.Termination.Converged =>
        if (!prunable(objMin)) {
          val vals = integerValues(node, summary.x)
          mostFractional(node, vals) match {
            case Some((j, v)) =>
              branch(node, j, v, objMin, open)
            case None =>
              roundedValues(node, vals) match {
                case Some(rounded) if incumbent.forall(objMin < _.objMin) =>
                  incumbent.foreach(old => release(old.x))
                  incumbent = Some(Candidate(objMin, summary.x, rounded, summary))
                case Some(_) => () // integral but not better; node is exhausted
                case None => exact = false // iterate neither fractional nor cleanly integral
              }
          }
        }
      case LP.Termination.PrimalInfeasible =>
        () // Farkas certificate: the node provably holds no feasible point
      case LP.Termination.DualInfeasible =>
        sawUnboundedNode = true
        val vals = integerValues(node, summary.x)
        if (summary.primalResidual < config.tolerance) {
          roundedValues(node, vals) match {
            case Some(rounded) =>
              // primal-feasible integral iterate + dual-infeasibility certificate: unbounded
              unboundedProof = Some(Candidate(Double.NegativeInfinity, summary.x, rounded, summary))
            case None => branchOrGiveUp(node, vals, open)
          }
        } else {
          branchOrGiveUp(node, vals, open)
        }
      case LP.Termination.IterationLimit =>
        branchOrGiveUp(node, integerValues(node, summary.x), open)
    }

    if ((summary.x ne rootX) && !incumbent.exists(_.x eq summary.x) && !unboundedProof.exists(_.x eq summary.x)) {
      release(summary.x)
    }
  }

  /** Driver-local RHS of the node: root RHS with the tightened integral bounds folded in. */
  private def nodeRhs(node: Node): DenseVector = {
    val b = compiled.b.values.clone()
    var j = 0
    while (j < n) {
      val col = intCols(j)
      val deltaL = node.lower(j) - rootLower(j)
      if (deltaL != 0.0) {
        col.rowCoeffs.foreach { case (r, a) => b(r) -= a * deltaL }
      }
      b(col.boundRow) = node.upper(j) - node.lower(j)
      j += 1
    }
    new DenseVector(b)
  }

  /** Solver-form objective contribution of the node's extra lower-bound shifts. */
  private def shiftCost(node: Node): Double = {
    var acc = 0.0
    var j = 0
    while (j < n) {
      acc += intCols(j).cost * (node.lower(j) - rootLower(j))
      j += 1
    }
    acc
  }

  /** Solver values of the integral columns, keyed by global column index (one collect per node). */
  private def integerValues(node: Node, x: DVector): Map[Long, Double] = {
    val gSet = intGSet
    compiled.sortedCols.zipPartitions(x) { (colsIt, xIt) =>
      if (!xIt.hasNext) Iterator.empty
      else {
        val values = xIt.next().values
        colsIt.zipWithIndex.collect { case (((g, _), i)) if gSet.value.contains(g) => (g, values(i)) }
      }
    }.collect().toMap
  }

  /** User-unit value of integral column `j` at the node, clamped into the node's bounds. */
  private def userValue(node: Node, vals: Map[Long, Double], j: Int): Double = {
    val raw = node.lower(j) + vals(intCols(j).g)
    math.min(math.max(raw, node.lower(j)), node.upper(j))
  }

  /** The unfixed column whose value is farthest from an integer, if any exceeds the tolerance. */
  private def mostFractional(node: Node, vals: Map[Long, Double]): Option[(Int, Double)] = {
    var best = -1
    var bestFrac = IntegralityTolerance
    var bestV = 0.0
    var j = 0
    while (j < n) {
      if (node.lower(j) < node.upper(j)) {
        val v = userValue(node, vals, j)
        val frac = math.abs(v - math.rint(v))
        if (frac > bestFrac) {
          best = j
          bestFrac = frac
          bestV = v
        }
      }
      j += 1
    }
    if (best >= 0) Some((best, bestV)) else None
  }

  /**
    * Exact user-unit integers of the node iterate, or `None` when any column is not integral
    * within the tolerance. Keys are global column indices, matching `buildSolution` overrides.
    */
  private def roundedValues(node: Node, vals: Map[Long, Double]): Option[Map[Long, Double]] = {
    val out = new Array[Double](n)
    var j = 0
    while (j < n) {
      val raw = node.lower(j) + vals(intCols(j).g)
      val r = math.rint(raw)
      if (math.abs(raw - r) > IntegralityTolerance || r < node.lower(j) || r > node.upper(j)) {
        return None
      }
      out(j) = r
      j += 1
    }
    Some(intCols.indices.map(j => intCols(j).g -> out(j)).toMap)
  }

  /** Splits the node on column `j` around the fractional value `v` (both children are non-empty). */
  private def branch(node: Node, j: Int, v: Double, childBound: Double, open: mutable.PriorityQueue[Node]): Unit = {
    val upper = node.upper.clone()
    upper(j) = math.floor(v)
    val lower = node.lower.clone()
    lower(j) = math.ceil(v)
    open.enqueue(Node(node.lower, upper, childBound))
    open.enqueue(Node(lower, node.upper, childBound))
  }

  /**
    * Progress on a node whose relaxation was not resolved (iteration limit, or a
    * dual-infeasibility certificate without a usable primal point): branch on a fractional column,
    * fall back to a midpoint split of the first unfixed column, or — with everything fixed —
    * leave the node unresolved. Children inherit the parent's bound: the iterate proves nothing.
    */
  private def branchOrGiveUp(node: Node, vals: Map[Long, Double], open: mutable.PriorityQueue[Node]): Unit = {
    mostFractional(node, vals) match {
      case Some((j, v)) => branch(node, j, v, node.bound, open)
      case None =>
        val j = node.lower.indices.find(j => node.lower(j) < node.upper(j))
        j match {
          case Some(k) =>
            val mid = math.floor((node.lower(k) + node.upper(k)) / 2.0)
            branch(node, k, mid + 0.5, node.bound, open)
          case None => exact = false // fully fixed and still unresolved
        }
    }
  }

  private def release(x: DVector): Unit = {
    if (x ne rootX) x.unpersist(blocking = false)
  }

  private def assemble(searchComplete: Boolean): LpSolution = {
    unboundedProof match {
      case Some(proof) =>
        compiler.buildSolution(
          compiled,
          x = proof.x,
          status = LpStatus.Unbounded,
          // the solver minimizes, so an unbounded objective diverges to -inf in solver form
          objectiveValue = compiled.senseMult * Double.NegativeInfinity,
          iterations = totalIterations,
          residuals = residualsOf(proof.summary),
          integerOverrides = proof.values)
      case None =>
        incumbent match {
          case Some(inc) =>
            val status = if (searchComplete && exact) LpStatus.Optimal else LpStatus.IterationLimit
            compiler.buildSolution(
              compiled,
              x = inc.x,
              status = status,
              objectiveValue = compiled.senseMult * inc.objMin + compiled.objConstant,
              iterations = totalIterations,
              residuals = residualsOf(inc.summary),
              integerOverrides = inc.values)
          case None =>
            val status =
              if (searchComplete && exact) {
                if (sawUnboundedNode) LpStatus.InfeasibleOrUnbounded else LpStatus.Infeasible
              } else {
                LpStatus.IterationLimit
              }
            // no integer-feasible point exists to report; expose the root relaxation iterate
            compiler.buildSolution(
              compiled,
              x = rootX,
              status = status,
              objectiveValue = Double.NaN,
              iterations = totalIterations,
              residuals = residualsOf(rootSummary),
              integerOverrides = Map.empty)
        }
    }
  }

  private def residualsOf(summary: LP.SolveSummary): LpResiduals =
    LpResiduals(summary.primalResidual, summary.dualResidual, summary.dualityGap)
}

private[dsl] object BranchAndBound {

  private[dsl] val DefaultMaxNodes = 1000
  private[dsl] val IntegralityTolerance = 1e-6
  private[dsl] val GapTolerance = 1e-9

  /** One open subproblem: integral bounds per column (aligned with `intCols`) and its best bound. */
  private final case class Node(lower: Array[Double], upper: Array[Double], bound: Double)

  /** A retained iterate: solver-form objective, iterate, exact integer overrides and diagnostics. */
  private final case class Candidate(
    objMin: Double,
    x: DVector,
    values: Map[Long, Double],
    summary: LP.SolveSummary)
}
