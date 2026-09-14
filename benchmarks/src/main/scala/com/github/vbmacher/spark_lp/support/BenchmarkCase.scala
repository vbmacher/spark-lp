package com.github.vbmacher.spark_lp.support

/** One campaign-independent mathematical case: m equality rows, n variables,
  * target nonzeros per row, family, seed, accuracy threshold and driver heap GiB.
  * Dense cases use m*n entries; dependent rows can exceed the target support width.
  *
  * @param id        unique, non-empty identifier for the case.
  * @param m         number of equality constraint rows; must be positive.
  * @param n         number of variables; must be greater than `m`.
  * @param width     target nonzeros per row (support width); must be in `1..n`.
  * @param family    case family, one of `well`, `wide`, `dependent`, `degenerate` or `dense`.
  * @param seed      seed used to make matrix generation deterministic.
  * @param tolerance accuracy threshold; must be a positive, finite number.
  * @param heapGiB   driver heap size in GiB; must be positive.
  */
final case class BenchmarkCase(
  id: String,
  m: Int,
  n: Int,
  width: Int,
  family: String,
  seed: Int,
  tolerance: Double,
  heapGiB: Int
) {
  require(id.nonEmpty && m > 0 && n > m && width > 0 && width <= n)
  require(Set("well", "wide", "dependent", "degenerate", "dense").contains(family))
  require(tolerance > 0 && !tolerance.isNaN && !tolerance.isInfinity && heapGiB > 0)
}

/** Parses the same small metadata CSV for local suites and EMR runs.
  * Matrix generation is delegated to DataGenerator; only case descriptions are read here.
  */
object CaseInventory {

  /** Expected header line of the metadata CSV; the columns map, in order, to the
    * constructor parameters of [[BenchmarkCase]].
    */
  val Header = "id,m,n,nonzeros_per_row,family,seed,tolerance,heap_gib"

  /** Reads and validates the metadata CSV at `path`.
    *
    * The first line must exactly match [[Header]]. Each remaining non-empty line
    * is split into its eight comma-separated columns and turned into a
    * [[BenchmarkCase]]; empty lines are skipped. Case IDs must be unique across
    * the file.
    *
    * @param path filesystem path to the metadata CSV.
    * @return the parsed cases in file order.
    * @throws IllegalArgumentException if the header is missing or wrong, a row
    *                                  does not have exactly eight columns, or a duplicate case ID is found.
    */
  def read(path: String): Vector[BenchmarkCase] = {
    val source = scala.io.Source.fromFile(path)
    try {
      val lines = source.getLines()
      require(lines.hasNext && lines.next() == Header, s"Expected CSV header: $Header")
      val cases = lines.filter(_.nonEmpty).map { line =>
        val f = line.split(",", -1)
        require(f.length == 8, s"Invalid case: $line")
        BenchmarkCase(f(0), f(1).toInt, f(2).toInt, f(3).toInt, f(4), f(5).toInt, f(6).toDouble, f(7).toInt)
      }.toVector
      require(cases.map(_.id).distinct.size == cases.size, "Duplicate case ID")
      cases
    } finally source.close()
  }
}
