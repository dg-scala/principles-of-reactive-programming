package kvstore

import akka.actor._

object ReplicatedCollector {
  case class ReplicatorDone(r: ActorRef)
  case class ReplicationFinished(id: Long)

  def props(id: Long, rs: Set[ActorRef]): Props = Props(new ReplicatedCollector(id, rs))
}

class ReplicatedCollector(id: Long, replicators: Set[ActorRef]) extends Actor {
  import ReplicatedCollector._
  
  var unprocessed = Set.empty[ActorRef]
  
  override def preStart() = replicators foreach { r: ActorRef => unprocessed += r }
  
  def receive: Receive = {
    case ReplicatorDone(r) =>
      unprocessed -= r
      if (unprocessed.isEmpty)
        sender() ! ReplicationFinished(id)
  } 
}
