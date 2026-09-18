package com.github.vbmacher.spark_lp.dsl

final case class LpBounds(lower: Double, upper: Option[Double])
private[dsl] final case class VariableMetadata(name: String, bounds: LpBounds,
  members: Map[String, LpBounds], names: Map[String, String]) {
  def at(key: String): LpBounds = members.getOrElse(key, bounds)
  def display(key: String, parts: Seq[String]): String = names.getOrElse(key, KeyCodec.displayName(name, parts))
  def envelope: LpBounds = envelopeFor(None)
  def envelopeFor(count: Option[Long]): LpBounds = {
    val all = if (members.nonEmpty && count.contains(members.size.toLong)) members.values.toVector else bounds +: members.values.toVector
    LpBounds(all.map(_.lower).min, if (all.exists(_.upper.isEmpty)) None else Some(all.flatMap(_.upper).max))
  }
}

private[dsl] object LpVariableEditing {
  def validate(bounds: LpBounds, category: VariableCategory): Unit = {
    val lo = bounds.lower
    if (lo.isNaN || lo.isPosInfinity)
      throw new LpModelException("The lower bound must not be NaN or +infinity")
    if (bounds.upper.exists(u => !java.lang.Double.isFinite(u)))
      throw new LpModelException("The upper bound must be finite when defined")
    if (bounds.upper.exists(_ < lo))
      throw new LpModelException("lowerBound must not exceed upperBound")
    val (lower, upper) = VariableCategory.domainBoundsFinite(category, lo, bounds.upper)
    if (category != Continuous && math.ceil(lower) > math.floor(upper))
      throw new LpModelException("Bounds contain no integral values in the variable's integer/binary domain")
  }

  def bounds(h: VarSetHandle, key: Option[String], value: LpBounds): Unit = {
    h.problem.requireEditable(); validate(value, h.category)
    key match {
      case None => h.metadata = h.metadata.copy(bounds = value, members = Map.empty); h.fixedMembers = Map.empty; h.fixedFamily = None
      case Some(k) => h.metadata = h.metadata.copy(members = h.metadata.members.updated(k, value)); h.fixedMembers -= k
    }
  }

  def fix(h: VarSetHandle, key: Option[String], value: Double): Unit = {
    h.problem.requireEditable()
    if (!java.lang.Double.isFinite(value)) throw new LpModelException("A fixed value must be finite")
    val fixed = LpBounds(value, Some(value)); validate(fixed, h.category)
    key match {
      case None =>
        if (h.fixedFamily.isEmpty) h.fixedFamily = Some((h.metadata.bounds, h.metadata.members, h.fixedMembers))
        h.metadata = h.metadata.copy(bounds = fixed, members = Map.empty); h.fixedMembers = Map.empty
      case Some(k) =>
        if (!h.fixedMembers.contains(k)) h.fixedMembers += k -> h.metadata.members.get(k)
        h.metadata = h.metadata.copy(members = h.metadata.members.updated(k, fixed))
    }
  }

  def unfix(h: VarSetHandle, key: Option[String]): Unit = {
    h.problem.requireEditable()
    key match {
      case None => h.fixedFamily.foreach { case (bounds, members, saved) =>
        h.metadata = h.metadata.copy(bounds = bounds, members = members); h.fixedMembers = saved; h.fixedFamily = None
      }
      case Some(k) => h.fixedMembers.get(k).foreach { saved =>
        h.metadata = h.metadata.copy(members = saved.fold(h.metadata.members - k)(b => h.metadata.members.updated(k, b)))
        h.fixedMembers -= k
      }
    }
  }

  def rename(h: VarSetHandle, key: Option[String], name: String): Unit = {
    h.problem.requireEditable()
    if (name == null || name.trim.isEmpty) throw new LpModelException("Variable names must be nonempty")
    if (h.problem.handles.exists(other => (other ne h) && other.name == name ||
      other.metadata.names.exists { case (k, n) => n == name && ((other ne h) || !key.contains(k)) }))
      throw new LpModelException(s"Variable name '$name' is already used")
    h.metadata = key match {
      case None => h.metadata.copy(name = name)
      case Some(k) => h.metadata.copy(names = h.metadata.names.updated(k, name))
    }
  }
}
