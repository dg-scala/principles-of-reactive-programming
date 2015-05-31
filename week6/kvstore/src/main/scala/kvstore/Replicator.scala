package kvstore

import akka.actor.{ReceiveTimeout, Props, Actor, ActorRef}
import scala.concurrent.duration._

object Replicator {
  case class Replicate(key: String, valueOption: Option[String], id: Long)
  case class Replicated(key: String, id: Long)
  
  case class Snapshot(key: String, valueOption: Option[String], seq: Long)
  case class SnapshotAck(key: String, seq: Long)

  def props(replica: ActorRef): Props = Props(new Replicator(replica))
}

class Replicator(val replica: ActorRef) extends Actor {
  import Replicator._
  import Replica._
  import context.dispatcher
  
  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */

  // map from sequence number to pair of sender and request
  var acks = Map.empty[Long, (ActorRef, Replicate)]
  // a sequence of not-yet-sent snapshots (you can disregard this if not implementing batching)
  var pending = Vector.empty[Snapshot]
  
  var _seqCounter = 0L
  def nextSeq = {
    val ret = _seqCounter
    _seqCounter += 1
    ret
  }

  override def preStart() = context.setReceiveTimeout(100.millis)

  private def resendUnacknowledged() =
    for {
      (seq, req) <- acks
    } yield replica ! Snapshot(req._2.key, req._2.valueOption, seq)

  private def sendReplicatedForSeq(seq: Long) = {
    acks.get(seq) match {
      case None =>
      case Some(repl) =>
        repl._1 ! Replicated(repl._2.key, repl._2.id )
    }
    acks -= seq
  }

  /* Behavior for the Replicator. */
  def receive: Receive = {
    case Replicate(k, vOpt, id) =>
      val seq = nextSeq
      acks += seq -> (sender(), Replicate(k, vOpt, id))
      replica ! Snapshot(k, vOpt, seq)

    case ReceiveTimeout =>
      resendUnacknowledged()

    case SnapshotAck(key, seq) =>
      sendReplicatedForSeq(seq)
  }

}
