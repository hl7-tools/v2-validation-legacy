package hl7.v2.validation
package content

import expression.EvalResult.{Fail, Inconclusive, Pass, Reason}
import hl7.v2.instance._
import hl7.v2.validation.content.PredicateUsage.{R, X}
import hl7.v2.validation.report._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait DefaultValidator extends Validator with expression.Evaluator {

  /**
    * Check the message against the constraints defined
    * in the constraint manager and returns the report.
    * @param m - The message to be checked
    * @return The report
    */
  def checkContent(m: Message): Future[Seq[CEntry]] = Future {
    implicit val separators = m.separators
    implicit val dtz = m.defaultTimeZone
    check(m.asGroup)
  }

  /**
    * Checks the element and its descendants against the constraints
    * defined in the constraint manager and returns the report.
    * @param e - The element to be checked
    * @return The report
    */
  private def check(e: Element)
                   (implicit s: Separators, dtz: Option[TimeZone]): List[CEntry] = {
    val cl: List[Constraint] = conformanceContext.constraintsFor(e)
    val pl: List[Predicate]  = conformanceContext.predicatesFor(e)

    val r = pl.foldLeft( cl map { check(e, _) } ){ (acc, p) =>
      check(e, p) ::: acc
    }

    e match {
      case c: Complex => c.children.foldLeft(r){ (acc, cc) => acc ::: check(cc) }
      case _          => r
    }
  }

  /**
    * Checks the element against the constraint and returns a CEntry.
    * @param e - The element to be checked
    * @param c - The constraint
    * @return A CEntry
    */
  private def check(e: Element, c: Constraint)
                   (implicit s: Separators, dtz: Option[TimeZone]): CEntry =
    eval(c.assertion, e) match {
      case Pass                => Success(e, c)
      case Fail(stack)         => Failure(e, c, stack)
      case Inconclusive(trace) => SpecError(e, c, trace)
    }

  private def check(e: Element, p: Predicate)
                   (implicit s: Separators, dtz: Option[TimeZone]): List[CEntry] =
    eval(p.condition, e) match {
      case Pass            => checkUsage(e, p, p.trueUsage)
      case Fail(stack)     => checkUsage(e, p, p.falseUsage)
      case Inconclusive(t) => PredicateSpecError(p, t.reasons) :: Nil
    }

  /**
    * Checks if the target path of the predicate satisfy the usage 'u'
    */
  private def checkUsage(e: Element, p: Predicate, u: PredicateUsage): List[CEntry] =
    try {
      val(contexts, path) = resolve(e, p.target)
      if( contexts.isEmpty ) PredicateSuccess(p) :: Nil //Nothing to do the parent is missing
      else {
        lazy val l = contexts flatMap { c => Query.query(c, path).get }
        u match {
          case R if l.isEmpty  =>
            contexts map { c =>
              val dl = Utils.defaultLocation(c.asInstanceOf[Complex], path).getOrElse(c.location)
              PredicateFailure(p, RUsage(dl))
            }
          case X if l.nonEmpty => l map {x => PredicateFailure(p, XUsage(x.location))}
          case _ => PredicateSuccess(p) :: Nil
        }
      }
    } catch { case f: Throwable =>
      val reasons = Reason(e.location, f.getMessage) :: Nil
      PredicateSpecError(p, reasons) :: Nil
    }

  @throws
  private def resolve(context: Element, target: String) : (List[Element], String) =
    target.lastIndexOf('.') match {
      case -1 => (context :: Nil, target)
      case i  =>
        val parentPath = target.take( i )
        val path = target.drop( i + 1)
        (Query.query(context, parentPath).get, path )
    }
}
