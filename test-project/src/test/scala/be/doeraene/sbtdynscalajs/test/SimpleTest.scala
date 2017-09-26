package be.doeraene.sbtdynscalajs.test

import utest._

object SimpleTest extends TestSuite {
  val tests = Tests {
    'trivial - {
      assert(1 == 1)
    }
    "platform-dependant" - {
      val incoming = 1.0.toString()
      val expected = Platform.expectedDouble1toString
      assert(incoming == expected)
    }
  }
}
