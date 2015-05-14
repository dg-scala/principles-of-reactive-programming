package suggestions



import scala.collection._
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Try, Success, Failure}
import scala.swing.event.Event
import scala.swing.Reactions.Reaction
import rx.lang.scala._
import org.scalatest._
import gui._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SwingApiTest extends FunSuite {

  object swingApi extends SwingApi {
    class ValueChanged(val textField: TextField) extends Event

    object ValueChanged {
      def unapply(x: Event) = x match {
        case vc: ValueChanged => Some(vc.textField)
        case _ => None
      }
    }

    class ButtonClicked(val source: Button) extends Event

    object ButtonClicked {
      def unapply(x: Event) = x match {
        case bc: ButtonClicked => Some(bc.source)
        case _ => None
      }
    }

    class Component {
      private val subscriptions = mutable.Set[Reaction]()
      def subscribe(r: Reaction) {
        subscriptions add r
      }
      def unsubscribe(r: Reaction) {
        subscriptions remove r
      }
      def publish(e: Event) {
        for (r <- subscriptions) r(e)
      }
    }

    class TextField extends Component {
      private var _text = ""
      def text = _text
      def text_=(t: String) {
        _text = t
        publish(new ValueChanged(this))
      }
    }

    class Button extends Component {
      def click() {
        publish(new ButtonClicked(this))
      }
    }
  }

  import swingApi._
  
  test("SwingApi should emit text field values to the observable") {
    val textField = new swingApi.TextField
    val values = textField.textValues

    val observed = mutable.Buffer[String]()
    val sub = values subscribe {
      observed += _
    }

    // write some text now
    textField.text = "T"
    textField.text = "Tu"
    textField.text = "Tur"
    textField.text = "Turi"
    textField.text = "Turin"
    textField.text = "Turing"

    sub.unsubscribe()
    textField.text = "Turingx"

    assert(observed == Seq("T", "Tu", "Tur", "Turi", "Turin", "Turing"), observed)
  }

  test("SwingApi should emit text field values to the observable 2") {
    val textField = new swingApi.TextField
    val values = textField.textValues

    val observed = mutable.Buffer[String]()

    val sub = values subscribe {
      observed += _
    }

    // augmented: add a second subscription that records errors and completeion
    val observed2 = mutable.Buffer[String]()

    def onNext(s: String) = observed2 += s
    def onErr(t: Throwable) = observed2 += t.getMessage
    def onComp() = observed2 += "Completed!"

    // sub2 also writes errors and completions to its list of strings
    val sub2 = values subscribe(onNext, onErr, onComp)

    // write some text now
    textField.text = "T"
    textField.text = "Tu"
    textField.text = "Tur"
    textField.text = "Turi"
    textField.text = "Turin"
    textField.text = "Turing"

    sub.unsubscribe()
    sub2.unsubscribe()

    textField.text = "Turingx"

    Thread.sleep(1000)

    assert(observed == Seq("T", "Tu", "Tur", "Turi", "Turin", "Turing"), observed)
    assert(observed2 == Seq("T", "Tu", "Tur", "Turi", "Turin", "Turing", "Completed!"), observed2)
  }

}
