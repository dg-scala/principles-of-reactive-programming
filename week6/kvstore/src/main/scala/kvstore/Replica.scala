package kvstore

import akka.actor._
import kvstore.Arbiter._
import scala.collection.immutable.Queue
import akka.actor.SupervisorStrategy.Restart
import scala.annotation.tailrec
import akka.pattern.{ask, pipe}
import scala.concurrent.duration._
import akka.util.Timeout
import scala.Nothing

object Replica {

  sealed trait Operation {
    def key: String

    def id: Long
  }

  case class Insert(key: String, value: String, id: Long) extends Operation

  case class Remove(key: String, id: Long) extends Operation

  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply

  case class OperationAck(id: Long) extends OperationReply

  case class OperationFailed(id: Long) extends OperationReply

  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor {

  import Replica._
  import Replicator._
  import Persistence._
  import RequestTimer._
  import context.dispatcher

  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */

  var kv = Map.empty[String, String]
  // a map from secondary replicas to replicators
  var secondaries = Map.empty[ActorRef, ActorRef]
  // the current set of replicators
  var replicators = Set.empty[ActorRef]

  // DeathWatch on the persistence Actor which can fail intermittently
  override val supervisorStrategy = OneForOneStrategy() {
    case _: PersistenceException => Restart
  }

  // persistence actor for this Replica
  var persistence = ActorRef.noSender

  // keeps track of the message not yet persisted and its originator
  var notPersisted = Map.empty[Long, (ActorRef, Persist)]

  def createPersistence() =
    persistence = context.actorOf(persistenceProps, s"persistence_${self.path.name}")

  override def preStart() = {
    arbiter ! Join
    createPersistence()
    context.setReceiveTimeout(100.millis)
  }

  def receive = {
    case JoinedPrimary => context.become(leader)
    case JoinedSecondary => context.become(replica)
  }

  private def persistUnconfirmed() =
    for {
      (seq, req) <- notPersisted
    } yield {
      persistence ! req._2
    }

  /* TODO Behavior for  the leader role. */
  val leader: Receive = {
    case Insert(k, v, id) =>
      kv += k -> v
      val timer = context.actorOf(RequestTimer.props(id), s"timer_$id")
      notPersisted += id -> (sender(), Persist(k, Some(v), id))
      self ! Persist(k, Some(v), id)

    case Remove(k, id) =>
      kv -= k
      val timer = context.actorOf(RequestTimer.props(id), s"timer_$id")
      notPersisted += id -> (sender(), Persist(k, None, id))
      self ! Persist(k, None, id)

    case Get(k, id) =>
      lookup(k, id)

    case OperationTimedOut(id) =>
      context.stop(sender())
      notPersisted.get(id) match {
        case Some(res) => res._1 ! OperationFailed(id)
        case None =>
      }

    case ReceiveTimeout =>
      persistUnconfirmed()

    case Persisted(key, id) =>
      notPersisted.get(id) match {
        case Some(res) =>
          res._1 ! OperationAck(id)
        case None =>
      }
      notPersisted -= id
  }

  // the sequence number of the next expected snapshot
  var nextExpectedSnapshot = 0L

  /* TODO Behavior for the replica role. */
  val replica: Receive = {
    case Get(k, id) =>
      lookup(k, id)

    case Snapshot(k, vOpt, seq) =>
      if (seq < nextExpectedSnapshot)
        sender ! SnapshotAck(k, seq)
      else if (seq == nextExpectedSnapshot) {
        vOpt match {
          case None => kv -= k
          case Some(v) => kv += k -> v
        }
        notPersisted += seq ->(sender(), Persist(k, vOpt, seq))
        self ! Persist(k, vOpt, seq)
      }

    case ReceiveTimeout =>
      persistUnconfirmed()

    case Persisted(key, seq) =>
      nextExpectedSnapshot += 1L
      notPersisted.get(seq) match {
        case Some(req) => req._1 ! SnapshotAck(req._2.key, seq)
        case None =>
      }
      notPersisted -= seq

  }

  private def lookup(k: String, id: Long): Unit = {
    val res: Option[String] = kv.get(k)
    sender ! GetResult(k, res, id)
  }
}

