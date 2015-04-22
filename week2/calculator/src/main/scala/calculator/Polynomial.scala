package calculator

import scala.math._

object Polynomial {
  def computeDelta(a: Signal[Double], b: Signal[Double],
      c: Signal[Double]): Signal[Double] =
    Signal(pow(b(), 2) - 4 * a() * c())

  def computeSolutions(a: Signal[Double], b: Signal[Double],
      c: Signal[Double], delta: Signal[Double]): Signal[Set[Double]] = {

    def roots: Set[Double] = {
      val d = computeDelta(a, b, c)
      if (d() < 0) {
        Set()
      }
      if (d() == 0) {
        Set() + (-b() / (2 * a()))
      }
      else {
        Set() + (
          (-b() - sqrt(d())) / (2 * a()),
          (-b() + sqrt(d())) / (2 * a()))
      }
    }

    Signal(roots)
  }
}
