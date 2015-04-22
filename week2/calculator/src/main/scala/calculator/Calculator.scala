package calculator

sealed abstract class Expr
final case class Literal(v: Double) extends Expr
final case class Ref(name: String) extends Expr
final case class Plus(a: Expr, b: Expr) extends Expr
final case class Minus(a: Expr, b: Expr) extends Expr
final case class Times(a: Expr, b: Expr) extends Expr
final case class Divide(a: Expr, b: Expr) extends Expr

object Calculator {
  def computeValues(
      namedExpressions: Map[String, Signal[Expr]]): Map[String, Signal[Double]] = {
    namedExpressions.mapValues(v => Signal(eval(v(), namedExpressions)))
  }

  def eval(expr: Expr, references: Map[String, Signal[Expr]]): Double = {
    def evaluate(e: Expr): Double = eval(e, references)

    expr match {
      case Literal(v) => v
      case Ref(name) => eval(getReferenceExpr(name, references), references - name)
      case Plus(a, b) => evaluate(a) + evaluate(b)
      case Minus(a, b) => evaluate(a) - evaluate(b)
      case Times(a, b) => evaluate(a) * evaluate(b)
      case Divide(a, b) => evaluate(a) / evaluate(b)
    }
  }

  /** Get the Expr for a referenced variables.
   *  If the variable is not known, returns a literal NaN.
   */
  private def getReferenceExpr(name: String,
      references: Map[String, Signal[Expr]]) = {
    references.get(name).fold[Expr] {
      Literal(Double.NaN)
    } { exprSignal =>
      exprSignal()
    }
  }
}
