package quickcheck

import common._

import org.scalacheck._
import Arbitrary._
import Gen._
import Prop._

abstract class QuickCheckHeap extends Properties("Heap") with IntHeap {

  property("minEmpty") = forAll { a: Int =>
    throws(classOf[NoSuchElementException])(findMin(empty))
  }

  property("min1") = forAll { a: Int =>
    val h = insert(a, empty)
    findMin(h) == a
  }

  property("deleteMin") = forAll { a: Int =>
    val h = insert(a - 1, insert(a, empty))
    empty == deleteMin(deleteMin(h))
  }

  property("insertAndDeleteEmpty") = forAll { a: Int =>
    val h = insert(a, empty)
    empty == deleteMin(h)
  }

  property("addAndDelete") = forAll { a: Int =>
    empty == deleteMin(insert(a, empty))
    a == findMin(insert(a, deleteMin(insert(a, empty))))
  }

  property("min2largeThenSmall") = forAll { a: Int =>
    val h = insert(1234, insert(1212, empty))
    findMin(h) == 1212
    findMin(deleteMin(h)) == 1234
  }

  property("min2smallThenLarge") = forAll { a: Int =>
    val h = insert(2, insert(1, empty))
    findMin(h) == 1
    findMin(deleteMin(h)) == 2
  }

  property("heap3") = forAll { a: Int =>
    val h1 = insert(3, insert(2, insert(1, empty)))
    val h2 = insert(2, insert(3, insert(1, empty)))
    val h3 = insert(1, insert(3, insert(2, empty)))
    val h4 = insert(3, insert(1, insert(2, empty)))
    val h5 = insert(1, insert(2, insert(3, empty)))
    val h6 = insert(2, insert(1, insert(3, empty)))
    1 == findMin(h1) && 1 == findMin(h2) && 1 == findMin(h3) &&
      1 == findMin(h4) && 1 == findMin(h5) && 1 == findMin(h6)
  }

  property("min2same") = forAll { a: Int =>
    val h = insert(a, insert(a, empty))
    findMin(h) == findMin(deleteMin(h))
    findMin(h) == a
  }

  property("meldEmpties") = forAll { a: Int =>
    val h1 = empty
    val h2 = empty
    meld(h1, h2) == empty
  }

  property("meldWithEmpty") = forAll { a: Int =>
    val h = meld(insert(a, empty), empty)
    !isEmpty(h) && a == findMin(h)

    val h2 = meld(empty, insert(a, empty))
    !isEmpty(h2) && a == findMin(h2)
  }

  property("meldTwoSingleHeaps") = forAll { a: Int =>
    val h1 = insert(1, empty)
    val h2 = insert(2, empty)
    1 == findMin(meld(h1, h2))
    1 == findMin(meld(h2, h1))
    2 == findMin(deleteMin(meld(h1, h2)))
    2 == findMin(deleteMin(meld(h2, h1)))
  }

  property("meld2and1") = forAll { a: Int =>
    val h1 = insert(-2, insert(1, empty))
    val h2 = insert(3, insert(-4, empty))
    val h12 = meld(h1, h2)
    val h21 = meld(h2, h1)
    -4 == findMin(h12) && -4 == findMin(h21)
    -2 == findMin(deleteMin(h12)) && -2 == findMin(deleteMin(h21))
    1 == findMin(deleteMin(deleteMin(h12))) && 1 == findMin(deleteMin(deleteMin(h21)))
    3 == findMin(deleteMin(deleteMin(deleteMin(h12)))) && 3 == findMin(deleteMin(deleteMin(deleteMin(h21))))
  }

  property("twoRandomHeaps") = forAll { (h1: H, h2: H) =>
    val m1 = findMin(h1)
    val m2 = findMin(h2)
    m1 == findMin(meld(h1, h2)) || m2 == findMin(meld(h1, h2))
    m1 == findMin(meld(h2, h1)) || m2 == findMin(meld(h2, h1))
  }

  lazy val genHeap: Gen[H] = for {
    i <- arbitrary[Int]
    h <- oneOf(const(empty), genHeap)
  } yield insert(i, h)

  implicit lazy val arbHeap: Arbitrary[H] = Arbitrary(genHeap)
}
