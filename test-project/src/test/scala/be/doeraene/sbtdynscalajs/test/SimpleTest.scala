package be.doeraene.sbtdynscalajs.test

import org.junit.Test
import org.junit.Assert._

class SimpleTest {
  @Test def trivial(): Unit = {
    assertEquals(1, 1)
  }

  @Test def platformDependent(): Unit = {
    assertEquals(Platform.expectedDouble1toString, 1.0.toString())
  }
}
