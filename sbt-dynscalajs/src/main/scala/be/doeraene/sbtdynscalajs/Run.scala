package be.doeraene.sbtdynscalajs

import org.scalajs.jsenv._

import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

// Copied from Run.scala upstream
private[sbtdynscalajs] object Run {
  /** Starts and waits for a run on the given [[JSEnv]] interruptibly.
   *
   *  Interruption can be triggered by typing anything into stdin.
   */
  def runInterruptible(jsEnv: JSEnv, input: Input, config: RunConfig): Unit = {
    val readPromise = Promise[Unit]()
    val readThread = new Thread {
      override def run(): Unit = {
        try {
          while (System.in.available() == 0) {
            Thread.sleep(50)
          }

          System.in.read()
        } catch {
          case _: InterruptedException =>
        } finally {
          readPromise.success(())
        }
      }
    }

    val run = jsEnv.start(input, config)
    try {
      readThread.start()

      val fut = Future.firstCompletedOf(List(readPromise.future, run.future))
      Await.result(fut, Duration.Inf)
    } finally {
      run.close()
    }

    readThread.interrupt()
    readThread.join()
    Await.result(run.future, Duration.Inf)
  }
}
