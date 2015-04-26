package calculator

import scala.math._

object Polynomial {
  def computeDelta(a: Signal[Double], b: Signal[Double],
                   c: Signal[Double]): Signal[Double] =
    Signal(pow(b(), 2) - 4 * a() * c())

  def computeSolutions(a: Signal[Double], b: Signal[Double],
                       c: Signal[Double], delta: Signal[Double]): Signal[Set[Double]] = {

    def roots(): Set[Double] = {
      val _d = delta()
      val _a = a()
      val _b = b()

      if (_d == 0) {
        Set() + (-_b / (2 * _a))
      }
      else if (_d > 0) {
        Set() +(
          (-_b - sqrt(_d)) / (2 * _a),
          (-_b + sqrt(_d)) / (2 * _a))
      }
      else {
        Set()
      }
    }

    Signal(roots())
  }
}
