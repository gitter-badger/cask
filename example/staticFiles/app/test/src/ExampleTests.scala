package app
import io.undertow.Undertow

import utest._

object ExampleTests extends TestSuite{
  def test[T](example: cask.main.BaseMain)(f: String => T): T = {
    val server = Undertow.builder
      .addHttpListener(8080, "localhost")
      .setHandler(example.defaultHandler)
      .build
    server.start()
    val res =
      try f("http://localhost:8080")
      finally server.stop()
    res
  }

  val tests = Tests{

    'StaticFiles - test(StaticFiles){ host =>
      requests.get(s"$host/static/example.txt").text() ==>
        "the quick brown fox jumps over the lazy dog"
    }

  }
}
