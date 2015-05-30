package kvstore

import akka.actor._
import scala.concurrent.duration._

object RequestTimer {
  sealed trait Operation {
    def id: Long
  }

  case class OperationTimedOut(id: Long) extends Operation

  def props(id: Long): Props = Props(new RequestTimer(id))
}

/**
 * RequestTimer Actor the sole purpose of which is to limit
 * the waiting time for a response to 1 second.
 *
 * @param id Identifier of the Operation for which the
 *           timer is intended.
 */

class RequestTimer(val id: Long) extends Actor {
  import RequestTimer._

  override def preStart = context.setReceiveTimeout(1.second)

  def receive = {
    case ReceiveTimeout =>
      context.parent ! OperationTimedOut(id)
  }
}
