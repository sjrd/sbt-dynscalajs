package be.doeraene.sbtdynscalajs.test

import utest._

object SimpleTest extends TestSuite {
  val tests = Tests {
    'trivial - {
      assert(1 == 1)
      1.0.toString()
    }
  }
}
