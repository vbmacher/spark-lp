package com.github.vbmacher.spark_lp.dsl

import org.apache.spark.sql.Row

/**
  * Canonical encoded form of a typed variable key.
  *
  * The encoded key — not the display string — is the identity used for sorting, deduplication,
  * joining `weightedBy` sources, and `lpSumBy` group matching. Instances are provided for `String`,
  * numeric primitives, `Boolean`, and tuples of these; a case-class key can supply one via
  * [[LpKeyEncoder.instance]].
  */
trait LpKeyEncoder[K] extends Serializable {

  /** The ordered key parts; each part must be a `String`, a numeric primitive, or a `Boolean`. */
  def parts(key: K): Seq[Any]
}

object LpKeyEncoder {

  def instance[K](f: K => Seq[Any]): LpKeyEncoder[K] = (key: K) => f(key)

  implicit val stringKey: LpKeyEncoder[String] = instance(k => Seq(k))
  implicit val intKey: LpKeyEncoder[Int] = instance(k => Seq(k))
  implicit val longKey: LpKeyEncoder[Long] = instance(k => Seq(k))
  implicit val shortKey: LpKeyEncoder[Short] = instance(k => Seq(k))
  implicit val byteKey: LpKeyEncoder[Byte] = instance(k => Seq(k))
  implicit val doubleKey: LpKeyEncoder[Double] = instance(k => Seq(k))
  implicit val floatKey: LpKeyEncoder[Float] = instance(k => Seq(k))
  implicit val booleanKey: LpKeyEncoder[Boolean] = instance(k => Seq(k))

  implicit def tuple2Key[A, B](implicit a: LpKeyEncoder[A], b: LpKeyEncoder[B]): LpKeyEncoder[(A, B)] =
    instance(k => a.parts(k._1) ++ b.parts(k._2))

  implicit def tuple3Key[A, B, C](
    implicit a: LpKeyEncoder[A], b: LpKeyEncoder[B], c: LpKeyEncoder[C]): LpKeyEncoder[(A, B, C)] =
    instance(k => a.parts(k._1) ++ b.parts(k._2) ++ c.parts(k._3))

  implicit def tuple4Key[A, B, C, D](
    implicit a: LpKeyEncoder[A], b: LpKeyEncoder[B], c: LpKeyEncoder[C], d: LpKeyEncoder[D]): LpKeyEncoder[(A, B, C, D)] =
    instance(k => a.parts(k._1) ++ b.parts(k._2) ++ c.parts(k._3) ++ d.parts(k._4))
}

/**
  * Canonical key encoding: each key part is rendered in a type-tagged, locale-independent form
  * (UTF-8 for strings, IEEE-754 bit pattern for doubles, decimal for integral types, `true`/`false`
  * for booleans), parts joined in declaration order with a `0x1F` separator. A `null` part encodes
  * the whole key as `null`, which validation rejects.
  *
  * Display names are `<setName>[<part1>,<part2>,...]` and are presentation only: internal variable
  * identity never parses or depends on the display string.
  */
private[dsl] object KeyCodec {

  private val Separator = "\u001F"

  /** Flattens nested structs into their leaf parts. */
  def flatParts(value: Any): Seq[Any] = value match {
    case row: Row => row.toSeq.flatMap(flatParts)
    case other => Seq(other)
  }

  def encodePart(part: Any): String = part match {
    case null => null
    case s: String => "s:" + s.replace("\\", "\\\\").replace(Separator, "\\u001f")
    case d: Double => "d:" + java.lang.Long.toHexString(java.lang.Double.doubleToLongBits(d))
    case f: Float => "d:" + java.lang.Long.toHexString(java.lang.Double.doubleToLongBits(f.toDouble))
    case i: Int => "i:" + i.toString
    case l: Long => "i:" + l.toString
    case s: Short => "i:" + s.toString
    case b: Byte => "i:" + b.toString
    case b: Boolean => "b:" + b.toString
    case other => "s:" + String.valueOf(other)
  }

  /** Encodes a sequence of (already flattened or flattenable) parts; `null` if any part is null. */
  def encodeParts(parts: Seq[Any]): String = {
    val flat = parts.flatMap(flatParts)
    if (flat.isEmpty || flat.contains(null)) null
    else flat.map(encodePart).mkString(Separator)
  }

  /** Encodes one column value (may be a struct for multi-part keys); `null` if any part is null. */
  def encodeValue(value: Any): String = encodeParts(Seq(value))

  def displayParts(value: Any): Seq[String] = flatParts(value).map(String.valueOf)

  /** Escapes `,`, `[`, `]` and `\` inside a display part with `\`. */
  def escapePart(part: String): String = part.flatMap {
    case c @ (',' | '[' | ']' | '\\') => Seq('\\', c)
    case c => Seq(c)
  }

  def displayName(setName: String, parts: Seq[String]): String =
    if (parts.isEmpty) setName
    else setName + "[" + parts.map(escapePart).mkString(",") + "]"
}
