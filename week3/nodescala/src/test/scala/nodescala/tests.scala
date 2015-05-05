package nodescala

import java.util.Calendar

import scala.language.postfixOps
import scala.util.{Try, Success, Failure}
import scala.collection._
import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.async.Async.{async, await}
import org.scalatest._
import NodeScala._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class NodeScalaSuite extends FunSuite {

  test("A Future should always be completed") {
    val always = Future.always(517)

    assert(Await.result(always, 0 nanos) == 517)
  }
  test("A Future should never be completed") {
    val never = Future.never[Int]

    try {
      Await.result(never, 1 second)
      assert(false)
    } catch {
      case t: TimeoutException => // ok!
    }
  }
  test("First future that completes from the list") {
    val fs = List(
      Future {
        Thread.sleep(2)
        1
      },
      Future {
        "successful"
      },
      Future {
        Thread.sleep(3)
        throw new Exception
      }
    )
    assert(Await.result(Future.any(fs), 0 nanos) == "successful")

    try {
      val dud = List()
      assert(Await.result(Future.any(dud), 0 nanos) == ???)
    } catch {
      case t: NoSuchElementException => // ok !
    }
  }
  test("All futures in a list complete") {
    val empty = List()
    assert(Await.result(Future.all(empty), 1 second) == Nil)

    val one = List(Future(1))
    assert(Await.result(Future.all(one), 1 second) == List(1))

    val two = List(Future(1), Future(2))
    assert(Await.result(Future.all(two), 1 second) == List(1, 2))

    val three = List(Future(1), Future(2), Future(3))
    assert(Await.result(Future.all(three), 1 second) == List(1, 2, 3))

    val fts = List(Future(1), Future(throw new Exception), Future(3))
    try {
      assert(Await.result(Future.all(fts), 1 second) == ???)
    } catch {
      case e: Exception => // ok !
    }
  }
  test("Continue with test") {
    def cont(ft: Future[Int]): String = {
      println("Helga the Wife")
      "a"
    }

    val f = Future {
      println("Haggar the Horrible")
      1
    }
    assert(Await.result(f.continueWith(cont), 1 seconds) == "a")

    val g = Future {
      println("Vikings will fail!")
      throw new Exception("Vikings")
    }
    try {
      assert(Await.result(g.continueWith(cont), 1 seconds) == ???)
    } catch {
      case e: Exception => assert(e.getMessage() == "Vikings")
    }
  }
  test("Continue test") {
    def cont(t: Try[Int]): String = {
      println("2nd")
      t match {
        case Success(x) => "a"
        case Failure(e) => "z"
      }
    }

    val f = Future {
      println("1st")
      1
    }
    assert(Await.result(f.continue(cont), 1 seconds) == "a")

    val g= Future {
      println("Failed but continue")
      throw new Exception
    }
    assert(Await.result(g.continue(cont), 1 seconds) == "z")
  }
  test("Run test") {
    val working = Future.run() { ct =>
      Future {
        while (ct.nonCancelled) {
          println(s"working ${Calendar.getInstance().getTime()}")
          Thread.sleep(1000)
        }
        println("done")
      }
    }
    Future.delay(5 seconds) onSuccess {
      case _ => working.unsubscribe()
    }
    Thread.sleep(6000)
  }

  class DummyExchange(val request: Request) extends Exchange {
    @volatile var response = ""
    val loaded = Promise[String]()

    def write(s: String) {
      response += s
    }

    def close() {
      loaded.success(response)
    }
  }

  class DummyListener(val port: Int, val relativePath: String) extends NodeScala.Listener {
    self =>

    @volatile private var started = false
    var handler: Exchange => Unit = null

    def createContext(h: Exchange => Unit) = this.synchronized {
      assert(started, "is server started?")
      handler = h
    }

    def removeContext() = this.synchronized {
      assert(started, "is server started?")
      handler = null
    }

    def start() = self.synchronized {
      started = true
      new Subscription {
        def unsubscribe() = self.synchronized {
          started = false
        }
      }
    }

    def emit(req: Request) = {
      val exchange = new DummyExchange(req)
      if (handler != null) handler(exchange)
      exchange
    }
  }

  class DummyServer(val port: Int) extends NodeScala {
    self =>
    val listeners = mutable.Map[String, DummyListener]()

    def createListener(relativePath: String) = {
      val l = new DummyListener(port, relativePath)
      listeners(relativePath) = l
      l
    }

    def emit(relativePath: String, req: Request) = this.synchronized {
      val l = listeners(relativePath)
      l.emit(req)
    }
  }

  test("Server should serve requests") {
    val dummy = new DummyServer(8191)
    val dummySubscription = dummy.start("/testDir") {
      request => for (kv <- request.iterator) yield (kv + "\n").toString
    }

    // wait until server is really installed
    Thread.sleep(500)

    def test(req: Request) {
      val webpage = dummy.emit("/testDir", req)
      val content = Await.result(webpage.loaded.future, 1 second)
      val expected = (for (kv <- req.iterator) yield (kv + "\n").toString).mkString
      assert(content == expected, s"'$content' vs. '$expected'")
    }

    test(immutable.Map("StrangeRequest" -> List("Does it work?")))
    test(immutable.Map("StrangeRequest" -> List("It works!")))
    test(immutable.Map("WorksForThree" -> List("Always works. Trust me.")))

    dummySubscription.unsubscribe()
  }

}




