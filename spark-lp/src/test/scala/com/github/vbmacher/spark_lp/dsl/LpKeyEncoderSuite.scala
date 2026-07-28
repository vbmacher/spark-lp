package com.github.vbmacher.spark_lp.dsl

import org.scalatest.funsuite.AnyFunSuite

class LpKeyEncoderSuite extends AnyFunSuite {

  test("encoded parts are type-tagged so equal renderings of different types stay distinct") {
    val asString = KeyCodec.encodeParts(Seq("1"))
    val asInt = KeyCodec.encodeParts(Seq(1))
    val asLong = KeyCodec.encodeParts(Seq(1L))
    val asDouble = KeyCodec.encodeParts(Seq(1.0))
    val asBoolean = KeyCodec.encodeParts(Seq(true))

    assert(asString != asInt)
    assert(asInt != asDouble)
    assert(asString != asDouble)
    assert(asBoolean != asString)
    // integral types share a canonical decimal form
    assert(asInt == asLong)
  }

  test("doubles encode by IEEE-754 bit pattern, locale-independently") {
    assert(KeyCodec.encodePart(1.5) ==
      "d:" + java.lang.Long.toHexString(java.lang.Double.doubleToLongBits(1.5)))
    assert(KeyCodec.encodePart(1.5f) == KeyCodec.encodePart(1.5))
    assert(KeyCodec.encodePart(0.1) != KeyCodec.encodePart(0.1f)) // different values as doubles
  }

  test("multi-part keys join with the 0x1F separator in declaration order") {
    val encoded = KeyCodec.encodeParts(Seq("a", 1))
    assert(encoded == "s:a\u001Fi:1")
    // a single concatenated string must not collide with a two-part key
    assert(KeyCodec.encodeParts(Seq("a1")) != encoded)
    assert(KeyCodec.encodeParts(Seq("a", "1")) != encoded)
  }

  test("encoding is deterministic across invocations") {
    val parts: Seq[Any] = Seq("beef", 42, 1.25, true)
    assert(KeyCodec.encodeParts(parts) == KeyCodec.encodeParts(parts))
  }

  test("null key parts encode the whole key as null") {
    assert(KeyCodec.encodeParts(Seq("a", null)) == null)
    assert(KeyCodec.encodeParts(Seq(null)) == null)
    assert(KeyCodec.encodeValue(null) == null)
    assert(KeyCodec.encodeParts(Seq.empty) == null)
  }

  test("tuple LpKeyEncoder instances flatten parts in declaration order") {
    assert(implicitly[LpKeyEncoder[String]].parts("x") == Seq("x"))
    assert(implicitly[LpKeyEncoder[(String, Int)]].parts(("x", 3)) == Seq("x", 3))
    assert(implicitly[LpKeyEncoder[(String, Int, Boolean)]].parts(("x", 3, true)) == Seq("x", 3, true))
    assert(implicitly[LpKeyEncoder[(String, Int, Boolean, Double)]].parts(("x", 3, true, 0.5)) ==
      Seq("x", 3, true, 0.5))
  }

  test("custom LpKeyEncoder via instance") {
    case class PlantProduct(plant: String, product: Int)
    implicit val enc: LpKeyEncoder[PlantProduct] = LpKeyEncoder.instance(k => Seq(k.plant, k.product))
    assert(enc.parts(PlantProduct("p1", 7)) == Seq("p1", 7))
    assert(KeyCodec.encodeParts(enc.parts(PlantProduct("p1", 7))) == "s:p1\u001Fi:7")
  }

  test("display names are <setName>[<parts>] with , [ ] \\ escaped") {
    assert(KeyCodec.displayName("amount", Seq("beef")) == "amount[beef]")
    assert(KeyCodec.displayName("amount", Seq("a", "b")) == "amount[a,b]")
    assert(KeyCodec.displayName("v", Seq("a,b", "c[d]", "e\\f")) == "v[a\\,b,c\\[d\\],e\\\\f]")
    assert(KeyCodec.displayName("scalar", Seq.empty) == "scalar")
  }
}
