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
  import OperationTimeout._
  import ReplicatedCollector._
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

  // keeps track of the Replicated responses via the ReplicatedCollector
  // reference (second in the pair). The first element in the value pair
  // is the reference of the originator for the operation.
  var notReplicated = Map.empty[Long, (ActorRef, ActorRef)]

  // keeps track of timeouts for the unacknowledged modify requests
  var timeouts = Map.empty[Long, ActorRef]

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

  private def cancelTimeout(id: Long) = {
    timeouts.get(id) match {
      case Some(t) => context.stop(t)
      case None =>
    }
    timeouts -= id
  }

  private def updateReplicas(rs: Set[ActorRef]) = {
    val keys = secondaries.keys
    for {
      r <- keys
    } yield {
      if (!rs.contains(r)) {
        secondaries.get(r) match {
          case Some(replicator) =>
            context.stop(replicator)
            secondaries -= r
          case None =>
        }
      }
    }

    for {
      r <- rs
    } yield {
      secondaries.get(r) match {
        case None =>
          secondaries += r -> context.actorOf(Replicator.props(r), s"replicator_${r.path.name}")
        case _ =>
      }
    }
  }

  private def replicateOperation(key: String, valOpt: Option[String], id: Long) =
    for {
      replicator <- secondaries.values
    } yield {
      replicator ! Replicate(key, valOpt, id)
    }

  private def doOperation(k: String, vOpt: Option[String], id: Long): Unit = {
    timeouts += id -> context.actorOf(OperationTimeout.props(id), s"timer_$id")
    notPersisted += id ->(sender(), Persist(k, vOpt, id))
    self ! Persist(k, vOpt, id)
    replicateOperation(k, vOpt, id)
    notReplicated += id ->(sender(), context.actorOf(ReplicatedCollector.props(id, replicators), s"collector_$id"))
  }

  /* TODO Behavior for  the leader role. */
  val leader: Receive = {
    // Operation types handling
    case Insert(k, v, id) =>
      kv += k -> v
      doOperation(k, Some(v), id)

    case Remove(k, id) =>
      kv -= k
      doOperation(k, None, id)

    case Get(k, id) =>
      lookup(k, id)

    case OperationTimedOut(id) =>
      if (notPersisted.get(id).isDefined)
        notPersisted.get(id) match {
          case Some(res) => res._1 ! OperationFailed(id)
          case None =>
        }
      else if (notReplicated.get(id).isDefined)
        notReplicated.get(id) match {
          case Some(res) => res._1 ! OperationFailed(id)
          case None =>
        }
      cancelTimeout(id)

    // Replication handling
    case Replicas(rs) =>
      updateReplicas(rs)

    case Replicated(k, id) =>
      notReplicated.get(id) match {
        case Some(x) => x._2 ! ReplicatorDone(sender())
        case _ =>
      }

    case ReplicationFinished(id) =>
      if (notPersisted.get(id).isEmpty)
        notReplicated.get(id) match {
          case None =>
          case Some(x) =>
            x._1 ! OperationAck(id)
            notReplicated -= id
            context.stop(sender())
            cancelTimeout(id)
        }

    // Persistence handling
    case ReceiveTimeout =>
      persistUnconfirmed()

    case Persisted(key, id) =>
      notPersisted.get(id) match {
        case Some(res) =>
          if (notReplicated.get(id).isEmpty) {
            res._1 ! OperationAck(id)
            cancelTimeout(id)
            notPersisted -= id
          }
        case None =>
      }
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

