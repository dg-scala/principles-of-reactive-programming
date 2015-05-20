/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package actorbintree

import akka.actor._
import scala.collection.immutable.Queue

object BinaryTreeSet {

  trait Operation {
    def requester: ActorRef
    def id: Int
    def elem: Int
  }

  trait OperationReply {
    def id: Int
  }

  /** Request with identifier `id` to insert an element `elem` into the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Insert(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to check whether an element `elem` is present
    * in the tree. The actor at reference `requester` should be notified when
    * this operation is completed.
    */
  case class Contains(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to remove the element `elem` from the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Remove(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request to perform garbage collection*/
  case object GC

  /** Holds the answer to the Contains request with identifier `id`.
    * `result` is true if and only if the element is present in the tree.
    */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply
  
  /** Message to signal successful completion of an insert or remove operation. */
  case class OperationFinished(id: Int) extends OperationReply

}


class BinaryTreeSet extends Actor {
  import BinaryTreeSet._
  import BinaryTreeNode._

  def createRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))

  var root = createRoot

  // optional
  var pendingQueue = Queue.empty[Operation]

  // optional
  def receive = normal

  // optional
  /** Accepts `Operation` and `GC` messages. */
  val normal: Receive = { case _ => ??? }

  // optional
  /** Handles messages while garbage collection is performed.
    * `newRoot` is the root of the new binary tree where we want to copy
    * all non-removed elements into.
    */
  def garbageCollecting(newRoot: ActorRef): Receive = ???

}

object BinaryTreeNode {
  trait Position

  case object Left extends Position
  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)
  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean) = Props(classOf[BinaryTreeNode],  elem, initiallyRemoved)
}

class BinaryTreeNode(val elem: Int, initiallyRemoved: Boolean) extends Actor {
  import BinaryTreeNode._
  import BinaryTreeSet._

  var subtrees = Map[Position, ActorRef]()
  var removed = initiallyRemoved

  // optional
  def receive = normal

  def insert(key: Position, id: Int, el: Int) =
    subtrees.get(key) match {
      case None =>
        subtrees += (key -> context.actorOf(BinaryTreeNode.props(el, false)))
        sender ! OperationFinished(id)
      case Some(act) => act ! Insert(sender, id, el)
    }
  
  def contains(key: Position, id: Int, el: Int) =
    subtrees.get(key) match {
      case None => sender ! ContainsResult(id, false)
      case Some(act) => act ! Contains(sender, id, el)
    }
  
  def remove(key: Position, id: Int, el: Int) =
    subtrees.get(key) match {
      case None => sender ! OperationFinished(id)
      case Some(acc) => acc ! Remove(sender, id, el)
    }
  
  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive = { 
    case Insert(s, id, el) =>
      if (el == elem) {
        if (removed) removed = false
        s ! OperationFinished(id)
      }
      else if (el < elem) insert(Left, id, el)
      else insert(Right, id, el)
      
    case Contains(s, id, el) =>
      if (el == elem)
        removed match {
          case true =>  s ! ContainsResult(id, false)
          case false => s ! ContainsResult(id, true)
        }
      else if (el < elem) contains(Left, id, el)
      else contains(Right, id, el)
      
    case Remove(s, id, el) =>
      if (el == elem) {
        if (!removed) removed = true
        s ! OperationFinished(id)
      }
      else if (el < elem) remove(Left, id, el)
      else remove(Right, id, el)      
  }

  // optional
  /** `expected` is the set of ActorRefs whose replies we are waiting for,
    * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
    */
  def copying(expected: Set[ActorRef], insertConfirmed: Boolean): Receive = ???


}
