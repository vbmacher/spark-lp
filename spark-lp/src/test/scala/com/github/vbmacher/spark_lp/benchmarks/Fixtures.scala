package com.github.vbmacher.spark_lp.benchmarks

import java.io.{DataOutputStream, OutputStream}
import java.security.{DigestOutputStream, MessageDigest}
import org.apache.spark.mllib.linalg.{SparseVector, Vectors}

/** Versioned original LP data and independent primal-dual witnesses; no solver helpers. */
object Fixtures {
  final case class Case(id: String, m: Int, multiplier: Int, width: Int, family: String,
    seed: Int, tolerance: Double, heapGiB: Int) {
    val n: Int = Math.multiplyExact(m, multiplier)
    require(m > 0 && multiplier >= 2 && width > 0 && width <= n)
    require(Set("well", "wide", "dependent", "degenerate", "dense").contains(family))
  }
  def read(path: String): Vector[Case] = {
    val source = scala.io.Source.fromFile(path)
    try source.getLines().drop(1).filter(_.nonEmpty).map { line =>
      val f = line.split(",")
      require(f.length == 8, s"Invalid case: $line")
      Case(f(0), f(1).toInt, f(2).toInt, f(3).toInt, f(4), f(5).toInt, f(6).toDouble, f(7).toInt)
    }.toVector finally source.close()
  }
  final case class Data(spec: Case, columns: Array[SparseVector], b: Array[Double], c: Array[Double],
    x: Array[Double], y: Array[Double], s: Array[Double], hash: String) {
    val nnz: Long = columns.map(_.indices.length.toLong).sum
    val objective: Double = b.zip(y).map { case (a, v) => a * v }.sum
    def residuals(actualX: Array[Double], actualY: Array[Double], actualS: Array[Double]): Map[String, Double] = {
      require(actualX.length == spec.n && actualS.length == spec.n && actualY.length == spec.m)
      val ax = Array.fill(spec.m)(0.0)
      val rc = new Array[Double](spec.n)
      var primalObjective = 0.0
      columns.indices.foreach { j =>
        val col = columns(j)
        var aty = 0.0
        col.indices.indices.foreach { k =>
          val i = col.indices(k)
          ax(i) += col.values(k) * actualX(j)
          aty += col.values(k) * actualY(i)
        }
        rc(j) = aty + actualS(j) - c(j)
        primalObjective += c(j) * actualX(j)
      }
      def norm(v: Array[Double]): Double = math.sqrt(v.map(a => a * a).sum)
      val dualObjective = b.zip(actualY).map { case (a, v) => a * v }.sum
      Map("primal" -> (norm(ax.zip(b).map { case (a, v) => a - v }) / (1.0 + norm(b))),
        "dual" -> (norm(rc) / (1.0 + norm(c))),
        "gap" -> (math.abs(primalObjective - dualObjective) / (1.0 + math.abs(dualObjective))),
        "objective" -> primalObjective, "dual_objective" -> dualObjective,
        "objective_error" -> (math.abs(primalObjective - objective) / (1.0 + math.abs(objective))),
        "min_x" -> actualX.min, "min_s" -> actualS.min)
    }
  }
  def passes(r: Map[String, Double], tolerance: Double): Boolean =
    r.values.forall(v => !v.isNaN && !v.isInfinite) &&
      Seq("primal", "dual", "gap", "objective_error").forall(k => r.get(k).exists(_ < tolerance)) &&
      Seq("min_x", "min_s").forall(k => r.get(k).exists(_ >= -tolerance))

  def generate(spec: Case): Data = {
    val random = new scala.util.Random(spec.seed)
    val rows = Array.tabulate(spec.m) { i =>
      val entries = scala.collection.mutable.Map(i -> 1.0)
      if (spec.family == "dense") {
        (0 until spec.n).filter(_ != i).foreach(j => entries(j) = (0.01 + random.nextDouble() * 0.04) / spec.n)
      } else {
        // A cyclic, seed-dependent offset gives distinct nonbasis columns per row, with
        // overlapping support between rows. The first m columns remain a diagonal basis.
        val offset = random.nextInt(spec.n - spec.m)
        (0 until math.min(spec.width - 1, spec.n - spec.m)).foreach { k =>
          entries(spec.m + (offset + k) % (spec.n - spec.m)) = 0.1 + random.nextDouble() * 0.4
        }
      }
      entries.toMap
    }
    val buffers = Array.fill(spec.n)(scala.collection.mutable.ArrayBuffer.empty[(Int, Double)])
    rows.indices.foreach { i =>
      val row = if (spec.family == "dependent" && i % 2 == 1) {
        val merged = scala.collection.mutable.Map.empty[Int, Double] ++ rows(i - 1)
        rows(i).foreach { case (j, v) => merged(j) = merged.getOrElse(j, 0.0) + 1e-4 * v }
        merged.toMap
      } else rows(i)
      val scale = if (spec.family == "wide") math.pow(1e-6, i.toDouble / math.max(1, spec.m - 1)) else 1.0
      row.toSeq.sortBy(_._1).foreach { case (j, v) => buffers(j) += i -> (scale * v) }
    }
    val columns = buffers.map(entries => Vectors.sparse(spec.m, entries.toSeq).asInstanceOf[SparseVector])
    val x = Array.tabulate(spec.n)(j => if (j >= spec.m || (spec.family == "degenerate" && j % 5 == 0)) 0.0 else 0.5 + random.nextDouble())
    val y = Array.fill(spec.m)(2.0 * random.nextDouble() - 1.0)
    val s = Array.tabulate(spec.n)(j => if (j < spec.m) 0.0 else 0.5 + random.nextDouble())
    val b = Array.fill(spec.m)(0.0)
    val c = s.clone()
    columns.indices.foreach { j =>
      columns(j).indices.indices.foreach { k =>
        val i = columns(j).indices(k)
        val v = columns(j).values(k)
        b(i) += v * x(j)
        c(j) += v * y(i)
      }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    val sink = new DataOutputStream(new DigestOutputStream(new OutputStream {
      override def write(value: Int): Unit = ()
    }, digest))
    sink.writeInt(spec.m); sink.writeInt(spec.n)
    columns.foreach { col =>
      sink.writeInt(col.indices.length)
      col.indices.indices.foreach { k => sink.writeInt(col.indices(k)); sink.writeDouble(col.values(k)) }
    }
    Seq(b, c, x, y, s).foreach(_.foreach(sink.writeDouble))
    sink.close()
    Data(spec, columns, b, c, x, y, s, digest.digest().map(b => f"${b & 0xff}%02x").mkString)
  }
}
