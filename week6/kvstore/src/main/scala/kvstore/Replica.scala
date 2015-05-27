package kvstore

import akka.actor.{OneForOneStrategy, Props, ActorRef, Actor}
import kvstore.Arbiter._
import scala.collection.immutable.Queue
import akka.actor.SupervisorStrategy.Restart
import scala.annotation.tailrec
import akka.pattern.{ask, pipe}
import akka.actor.Terminated
import scala.concurrent.duration._
import akka.actor.PoisonPill
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy
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

  // keeps track of the sender of the last unacknowledged message
  var lastUnacknowledgedClient = ActorRef.noSender

  def createPersistence() =
    persistence = context.actorOf(persistenceProps, s"persistence_${self.path.name}")

  override def preStart() = {
    arbiter ! Join
    createPersistence()
  }

  def receive = {
    case JoinedPrimary => context.become(leader)
    case JoinedSecondary => context.become(replica)
  }

  /* TODO Behavior for  the leader role. */
  val leader: Receive = {
    case Insert(k, v, id) =>
      kv += k -> v
      sender ! OperationAck(id)

    case Remove(k, id) =>
      kv -= k
      sender ! OperationAck(id)

    case Get(k, id) =>
      lookup(k, id)

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
        lastUnacknowledgedClient = sender()
        persistence ! Persist(k, vOpt, seq)
      }

    case Persisted(key, seq) =>
      nextExpectedSnapshot += 1L
      lastUnacknowledgedClient ! SnapshotAck(key, seq)

  }

  private def lookup(k: String, id: Long): Unit = {
    val res: Option[String] = kv.get(k)
    sender ! GetResult(k, res, id)
  }
}

