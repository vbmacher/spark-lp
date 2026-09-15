package com.github.vbmacher.spark_lp.dsl

import com.github.vbmacher.spark_lp.dsl.implicits._
import scala.util.control.NonFatal

/** Allowed degradation is absoluteTolerance + relativeTolerance * abs(optimum), including constants. */
final case class LpPriority(expression: LpExpr, absoluteTolerance: Double = 0.0,
                            relativeTolerance: Double = 0.0) {
  require(Seq(absoluteTolerance, relativeTolerance).forall(t => !t.isNaN && !t.isInfinite && t >= 0.0),
    "Priority tolerances must be finite and nonnegative")
}

/** A failed numerical stage has an error and no solution; solver statuses remain on their solution. */
final case class LpPriorityStage(index: Int, solution: Option[LpSolution], error: Option[LpNumericalException])

/** Owns every stage result. Variables in a stage are accessed through copied.variable/variables. */
final class LpPriorityResult private[dsl](val copied: LpModelCopy,
                                        val stages: Vector[LpPriorityStage], val complete: Boolean)
    extends AutoCloseable {
  override def close(): Unit = stages.foreach(_.solution.foreach(_.close()))
}

private[dsl] object LpPriorities {
  def solve(source: LpProblem, priorities: Seq[LpPriority], config: SolveConfig): LpPriorityResult = {
    require(priorities.nonEmpty, "At least one priority is required")
    val copied = source.copy()
    // Translate all expressions before the first solve, including ownership validation.
    val translated = priorities.map(p => (copied.expression(p.expression), p))
    var stages = Vector.empty[LpPriorityStage]
    try {
      val iterator = translated.iterator.zipWithIndex
      var complete = true
      while (iterator.hasNext && complete) {
        val ((expression, priority), index) = iterator.next()
        copied.model.setObjective(expression)
        val stage = try LpPriorityStage(index, Some(copied.model.solve(config)), None)
        catch { case e: LpNumericalException => LpPriorityStage(index, None, Some(e)) }
        stages :+= stage
        complete = stage.solution.exists(s => s.status == LpStatus.Optimal && s.candidate.feasible)
        if (complete && iterator.hasNext) {
          val optimum = stage.solution.get.objectiveValue
          val allowance = priority.absoluteTolerance + priority.relativeTolerance * math.abs(optimum)
          val limit = if (source.sense == Minimize) optimum + allowance else optimum - allowance
          if (limit.isNaN || limit.isInfinite)
            throw new LpModelException("Priority preservation limit is not finite")
          val constraint = if (source.sense == Minimize) expression <= limit else expression >= limit
          // Unnamed rows receive the compiler's collision-checked automatic names.
          copied.model += constraint
        }
      }
      new LpPriorityResult(copied, stages, complete)
    } catch {
      case NonFatal(e) =>
        stages.foreach(_.solution.foreach(_.close()))
        throw e
    }
  }
}
