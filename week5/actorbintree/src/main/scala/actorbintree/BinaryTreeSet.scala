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

  /** Request to perform garbage collection */
  case object GC

  /** Garbage collection finished */
  case object GCFinished

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

  def createRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true, isRoot = true))

  var root = createRoot

  // optional
  var pendingQueue = Queue.empty[Operation]

  // optional
  def receive = normal

  // optional
  /** Accepts `Operation` and `GC` messages. */
  val normal: Receive = {
    case Insert(s, id, el) => root ! Insert(s, id, el)
    case Contains(s, id, el) => root ! Contains(s, id, el)
    case Remove(s, id, el) => root ! Remove(s, id, el)
    case GC =>
      val newRoot = createRoot
      context.become(garbageCollecting(createRoot))
      root ! CopyTo(newRoot)
  }

  // optional
  /** Handles messages while garbage collection is performed.
    * `newRoot` is the root of the new binary tree where we want to copy
    * all non-removed elements into.
    */
  def garbageCollecting(newRoot: ActorRef): Receive = {
    case GCFinished =>
      root = newRoot
      context.unbecome()
      for {op <- pendingQueue} yield newRoot ! op
      pendingQueue = Queue.empty[Operation]

    case op: Operation => pendingQueue :+= op
  }
}

object BinaryTreeNode {

  trait Position

  case object Left extends Position

  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)

  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean, isRoot: Boolean) = Props(classOf[BinaryTreeNode], elem, initiallyRemoved, isRoot)
}

class BinaryTreeNode(val elem: Int, initiallyRemoved: Boolean, isRoot: Boolean = false) extends Actor {

  import BinaryTreeNode._
  import BinaryTreeSet._

  val rootNode = isRoot
  var subtrees = Map[Position, ActorRef]()
  var removed = initiallyRemoved

  // optional
  def receive = normal

  def insert(key: Position, s: ActorRef, id: Int, el: Int) =
    subtrees.get(key) match {
      case None =>
        subtrees += (key -> context.actorOf(BinaryTreeNode.props(el, initiallyRemoved = false, isRoot = false)))
        s ! OperationFinished(id)
      case Some(act) => act ! Insert(s, id, el)
    }

  def contains(key: Position, s: ActorRef, id: Int, el: Int) =
    subtrees.get(key) match {
      case None => s ! ContainsResult(id, result = false)
      case Some(act) => act ! Contains(s, id, el)
    }

  def remove(key: Position, s: ActorRef, id: Int, el: Int) =
    subtrees.get(key) match {
      case None => s ! OperationFinished(id)
      case Some(acc) => acc ! Remove(s, id, el)
    }

  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive = {
    case Insert(s, id, el) =>
      if (el == elem) {
        if (removed) removed = false
        s ! OperationFinished(id)
      }
      else if (el < elem) insert(Left, s, id, el)
      else insert(Right, s, id, el)

    case Contains(s, id, el) =>
      if (el == elem) s ! ContainsResult(id, !removed)
      else if (el < elem) contains(Left, s, id, el)
      else contains(Right, s, id, el)

    case Remove(s, id, el) =>
      if (el == elem) {
        if (!removed) removed = true
        s ! OperationFinished(id)
      }
      else if (el < elem) remove(Left, s, id, el)
      else remove(Right, s, id, el)

    case CopyTo(newRoot) =>
      context.become(copying(subtrees.values.toSet, insertConfirmed = false))
      if (!removed) newRoot ! Insert(self, 0, elem)
      for {c: ActorRef <- subtrees.values.toSet} yield c ! CopyTo(newRoot)
  }

  // optional
  /** `expected` is the set of ActorRefs whose replies we are waiting for,
    * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
    */
  def copying(expected: Set[ActorRef], insertConfirmed: Boolean): Receive = {
    case CopyFinished =>
      if (expected == Set.empty[ActorRef]) {
        if (insertConfirmed) {
          notifyParent()
          context.stop(self)
        }
      }
      else {
        if ((expected - sender) == Set.empty)
          notifyParent()
        else
          context.become(copying(expected - sender, insertConfirmed))
      }

    case OperationFinished(id) =>
      if (expected == Set.empty) {
        notifyParent()
        context.stop(self)
      }
      else {
        context.become(copying(expected, insertConfirmed = true))
      }
  }

  def notifyParent(): Unit = {
    if (isRoot) context.parent ! GCFinished
    else context.parent ! CopyFinished
  }
}
