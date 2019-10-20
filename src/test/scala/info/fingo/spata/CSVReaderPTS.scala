package info.fingo.spata

import info.fingo.spata.CSVReader.CSVCallback
import org.scalatest.FunSuite

import scala.io.Source

class CSVReaderPTS extends FunSuite {
  val amount = 10_000
  test("Reader should handle large data streams") {
    val separator = ','
    val source = new TestSource(separator)
    val reader = new CSVReader(separator)
    var count = 0
    val cb: CSVCallback = _ => {
      count += 1
      true
    }
    reader.read(source, cb)
    assert(count == amount)
  }

  class TestSource(separator: Char) extends Source {
    def csvStream(sep: Char, lines: Int): LazyList[Char] = {
      val cols = 10
      val header = ((1 to cols).mkString(""+sep) + "\n").to(LazyList)
      val rows = LazyList.fill(lines)(s"lorem ipsum$sep" * (cols-1) + "lorem ipsum\n").flatMap(_.toCharArray)
      header #::: rows
    }
    override val iter: Iterator[Char] = csvStream(separator, amount).iterator
  }
}
