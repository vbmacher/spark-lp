package com.github.vbmacher.spark_lp.dsl

sealed trait SosKind
object SosKind {
  case object Sos1 extends SosKind
  case object Sos2 extends SosKind
}
final case class LpSosMember(variable: LpVariableId, weight: Double)
final case class LpSosData(name: String, kind: SosKind, members: Vector[LpSosMember])
final class LpSosGroup private[dsl](val name: String, val kind: SosKind,
  val members: Vector[(LpVariable, Double)]) {
  def data: LpSosData = LpSosData(name, kind, members.map { case (v, weight) =>
    LpSosMember(LpVariableId(v.handle.setIndex, v.selectedKey.getOrElse("")), weight)
  })
}

private[dsl] object LpSos extends Serializable {
  def add(model: LpProblem, name: String, kind: SosKind, members: Seq[(LpVariable, Double)]): LpSosGroup = {
    model.requireEditable()
    if (kind == null) throw new LpModelException("SOS kind must be specified")
    if (name == null || name.trim.isEmpty || model.sosGroups.exists(_.name == name))
      throw new LpModelException("SOS names must be nonempty and unique")
    if (members.isEmpty) throw new LpModelException("An SOS group must have at least one member")
    members.foreach { case (variable, weight) =>
      if (variable == null || (variable.handle.problem ne model)) throw new LpModelException("SOS member belongs to a different model")
      if (!java.lang.Double.isFinite(weight)) throw new LpModelException("SOS weights must be finite")
    }
    val ids = members.map { case (v, _) => (v.handle.setIndex, v.selectedKey.getOrElse("")) }
    if (ids.distinct.size != ids.size) throw new LpModelException("Duplicate member in SOS group")
    if (kind == SosKind.Sos2 && members.map(_._2).distinct.size != members.size)
      throw new LpModelException("SOS2 weights must be distinct to define an unambiguous order")
    val group = new LpSosGroup(name, kind, members.toVector.sortBy { case (v, w) => (w, v.handle.setIndex, v.selectedKey.getOrElse("")) })
    model.sosGroups += group
    group
  }

  def valid(group: LpSosData, active: Vector[Int]): Boolean = group.kind match {
    case SosKind.Sos1 => active.size <= 1
    case SosKind.Sos2 => active.size <= 1 || active.size == 2 && math.abs(active(0) - active(1)) == 1
  }

  /** An exact finite-bound selector formulation, with selectors owned only by the compiler. */
  def lower(model: LpProblem, firstIndex: Int): (Vector[VarSetHandle], Vector[Either[LpConstraint, LpConstraintSet]]) = {
    val handles = Vector.newBuilder[VarSetHandle]
    val constraints = Vector.newBuilder[Either[LpConstraint, LpConstraintSet]]
    var next = firstIndex
    model.sosGroups.zipWithIndex.foreach { case (group, gi) =>
      val n = group.members.size
      val selectorCount = if (group.kind == SosKind.Sos1) n else math.max(1, n - 1)
      val selectors = Vector.tabulate(selectorCount) { i =>
        val h = new VarSetHandle(model, next, s"__sos_${gi}_selector_$i", 0.0, Some(1.0), Binary, new ScalarDomain(model.spark))
        next += 1; handles += h; h
      }
      def add(expression: LpExpr, sense: LpSense, rhs: Double, suffix: String): Unit =
        constraints += Left(expression.compare(sense, rhs).withName(s"__sos_${gi}_$suffix"))
      add(selectors.foldLeft(LpExpr.zero)((sum, h) => sum.plus(h.toExpr(1.0))), LpSense.Le, 1.0, "selection")
      group.members.zipWithIndex.foreach { case ((v, _), i) =>
        val (lower, upper) = VariableCategory.domainBounds(v.handle.category, v.lowerBound, v.upperBound)
        if (!java.lang.Double.isFinite(lower) || upper.isEmpty || !java.lang.Double.isFinite(upper.get))
          throw new LpModelException(s"SOS '${group.name}' requires finite declared bounds for member '${v.name}'")
        val active = if (group.kind == SosKind.Sos1) selectors(i).toExpr(1.0)
          else if (n == 1) selectors.head.toExpr(1.0)
          else selectors.slice(math.max(0, i - 1), math.min(n - 1, i + 1))
            .foldLeft(LpExpr.zero)((sum, h) => sum.plus(h.toExpr(1.0)))
        if (upper.get != 0.0)
          add(v.toExpr(1.0).plus(active.scaledBy(-upper.get)), LpSense.Le, 0.0, s"upper_$i")
        if (lower != 0.0)
          add(v.toExpr(1.0).plus(active.scaledBy(-lower)), LpSense.Ge, 0.0, s"lower_$i")
      }
    }
    (handles.result(), constraints.result())
  }
}
