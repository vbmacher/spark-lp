package support

/** One campaign-independent mathematical case: m equality rows, n variables,
  * target nonzeros per row, family, seed, accuracy threshold and driver heap GiB.
  * Dense cases use m*n entries; dependent rows can exceed the target support width.
  */
final case class BenchmarkCase(id: String, m: Int, n: Int, width: Int, family: String,
                               seed: Int, tolerance: Double, heapGiB: Int) {
  require(id.nonEmpty && m > 0 && n > m && width > 0 && width <= n)
  require(Set("well", "wide", "dependent", "degenerate", "dense").contains(family))
  require(tolerance > 0 && !tolerance.isNaN && !tolerance.isInfinity && heapGiB > 0)
}

/** Parses the same small metadata CSV for local suites and EMR runs.
  * Matrix generation is delegated to DataGenerator; only case descriptions are read here.
  */
object CaseInventory {
  val Header = "id,m,n,nonzeros_per_row,family,seed,tolerance,heap_gib"
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
