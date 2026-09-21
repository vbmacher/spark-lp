package com.github.vbmacher.spark_lp.dsl

/** Local allocation in input order, with constant-time keyed access. No Spark action is performed. */
final class LpLocalVariables[K] private[dsl](val entries: Vector[(K, LpVariable)]) {
  private val byKey = entries.toMap

  def apply(key: K): LpVariable = byKey(key)

  def keys: Vector[K] = entries.map(_._1)

  def values: Vector[LpVariable] = entries.map(_._2)

  def size: Int = entries.size
}

private[dsl] object LpLocalVariables {
  def validated[K](keys: Iterable[K])(implicit encoder: LpKeyEncoder[K]): Vector[(K, String)] = {
    val entries = keys.iterator.map { key =>
      val encoded = if (key == null) null else KeyCodec.encodeParts(encoder.parts(key))
      require(encoded != null, "Local variable keys must not contain null or empty encoded keys")
      // Hex preserves type-tagged identity without delimiter ambiguities in scalar names.
      val name = encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        .iterator.map(b => f"${b & 0xff}%02x").mkString
      key -> name
    }.toVector
    require(entries.map(_._1).distinct.size == entries.size &&
      entries.map(_._2).distinct.size == entries.size, "Duplicate local variable key")
    entries
  }
}
