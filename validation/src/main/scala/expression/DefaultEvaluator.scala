package expression

import expression.EvalResult._
import hl7.v2.instance.Query._
import hl7.v2.instance._
import hl7.v2.validation.vs.{Validator, ValueSetLibrary}
import Validator.checkValueSet
import gov.nist.validation.report.Entry

import scala.util.{Failure, Success, Try}

trait DefaultEvaluator extends Evaluator with EscapeSeqHandler {

  /**
    * Evaluates the expression within the specified context
    * and returns the result
    * @param e - The expression to be evaluated
    * @param c - The context node
    * @param s - The message separators
    * @param t - The default time zone
    * @return The evaluation result
    */
  def eval(e: Expression, c: Element)(implicit l: ValueSetLibrary, s: Separators,
                                      t: Option[TimeZone]): EvalResult = e match {
    case x: Presence    => presence(x, c)
    case x: PlainText   => plainText(x, c)
    case x: Format      => format(x, c)
    case x: NumberList  => numberList(x, c)
    case x: StringList  => stringList(x, c)
    case x: SimpleValue => simpleValue(x, c)
    case x: PathValue   => pathValue(x, c)
    case x: AND         => and(x, c)
    case x: OR          => or(x, c)
    case x: NOT         => not(x, c)
    case x: XOR         => xor(x, c)
    case x: IMPLY       => imply(x, c)
    case x: EXIST       => exist(x, c)
    case x: FORALL      => forall(x, c)
    case x: Plugin      => plugin(x, c)
    case x: SetId       => setId(x, c)
    case x: ValueSet    => valueSet(x, c)
  }

  /**
    * Evaluates the presence expression and returns the result
    * @param p       - The presence expression
    * @param context - The context
    * @return The evaluation result
    */
  def presence(p: Presence, context: Element): EvalResult =
    query(context, p.path) match {
      case Success(Nil) => Failures.presence(context, p)
      case Success(_)   => Pass
      case Failure(e)   => inconclusive(p, context.location, e)
    }

  /**
    * Evaluates the plain text expression and returns the result
    * @param p       - The plain text expression
    * @param context - The context
    * @return The evaluation result
    */
  def plainText(p: PlainText, context: Element)
               (implicit s: Separators): EvalResult =
    queryAsSimple(context, p.path) match {
      case Success(ls)  =>
        ls filter( x => notEqual(x, p.text, p.ignoreCase) ) match {
          case Nil => Pass
          case xs  => Failures.plainText(p, xs)
        }
      case Failure(e) => inconclusive(p, context.location, e)
    }

  /**
    * Evaluates the format expression and returns the result
    * @param f       - The format expression
    * @param context - The context
    * @return The evaluation result
    */
  def format(f: Format, context: Element)(implicit s: Separators): EvalResult =
    queryAsSimple(context, f.path) match {
      case Success(ls)  =>
        ls filter( x => notMatch(x, f.pattern) ) match {
          case Nil => Pass
          case xs  => Failures.format(f, xs)
        }
      case Failure(e) => inconclusive(f, context.location, e)
    }

  /**
    * Evaluates the string list expression and returns the result
    * @param sl      - The string list expression
    * @param context - The context
    * @return The evaluation result
    */
  def stringList(sl: StringList, context: Element)
                (implicit s: Separators): EvalResult =
    queryAsSimple(context, sl.path) match {
      case Success(ls)  =>
        ls filter( x => notInList(x.value.raw, sl.csv) ) match {
          case Nil => Pass
          case xs  => Failures.stringList(sl, xs)
        }
      case Failure(e) => inconclusive(sl, context.location, e)
    }

  /**
    * Evaluates the number list expression and returns the result
    * @param nl      - The number list expression
    * @param context - The context
    * @return The evaluation result
    */
  def numberList(nl: NumberList, context: Element)
                (implicit s: Separators): EvalResult =
    queryAsSimple(context, nl.path) match {
      case Success(ls)  =>
        val(l1, l2) = ls partition( x => convertibleToDouble( x.value.raw ))
        l2 match {
          case Nil =>
            l1 filter( x => notInList(x.value.raw.toDouble, nl.csv) ) match {
              case Nil => Pass
              case xs  => Failures.numberList(nl, xs)
            }
          case xs => Failures.numberListNaN(nl, xs)
        }
      case Failure(e) => inconclusive(nl, context.location, e)
    }

  /**
    * Evaluates the simple value expression and returns the result
    * @param sv      - The simple value expression
    * @param context - The context
    * @return The evaluation result
    */
  def simpleValue(sv: SimpleValue, context: Element)
                 (implicit dtz: Option[TimeZone]): EvalResult =
    queryAsSimple(context, sv.path) match {
      case Success(ls) =>
        val evs = ls map { s => s -> sv.operator.eval( s.value, sv.value ) }
        evs partition { _._2.isFailure  } match {
          case (Nil, xs) =>
            xs filter { _._2 == Success(false) } match {
              case Nil => Pass
              case ys  => Failures.simpleValue( sv, ys map { x => x._1 } )
            }
          case (xs, _) => inconclusive(sv, xs)
        }
      case Failure(e)  => inconclusive(sv, context.location, e)
    }

  /**
    * Evaluates the path value expression and returns the result
    * @param pv      - The path value expression
    * @param context - The context
    * @return The evaluation result
    */
  def pathValue(pv: PathValue, context: Element)
               (implicit dtz: Option[TimeZone]): EvalResult =
    (queryAsSimple(context, pv.path1), queryAsSimple(context, pv.path2)) match {
      case (Success(  Nil  ), Success(  Nil  )) => Pass
      case (Success(x::Nil), Success(Nil)) => Failures.pathValue(pv, x, pv.path2)
      case (Success(Nil), Success(x::Nil)) => Failures.pathValue(pv, x, pv.path1)
      case (Success(x1::Nil), Success(x2::Nil)) =>
        pv.operator.eval( x1.value, x2.value ) match {
          case Success(true)  => Pass
          case Success(false) => Failures.pathValue(pv, x1, x2)
          case Failure(e)     => inconclusive(pv, context.location, e)
        }
      case (Success(xs1), Success(xs2)) => inconclusive(pv, context, xs1, xs2)
      case ( Failure(e), _ )            => inconclusive(pv, context.location, e)
      case ( _, Failure(e) )            => inconclusive(pv, context.location, e)
    }

  /**
    * Evaluates the value set expression and returns the result
    * @param vs      - The value set expression
    * @param context - The context
    * @return The result of the evaluation
    */
  def valueSet(vs: ValueSet, context: Element)
              (implicit l: ValueSetLibrary): EvalResult =
    query(context, vs.path) match {
      case Failure(e)   => inconclusive(vs, context.location, e)
      case Success(Nil) => Pass
      case Success(x::Nil)  =>
        val r = checkValueSet(x, vs.spec, l)
        if( isVSViolated(r) ) Failures.valueSet(vs, r) else Pass
      case Success(xs) =>
        val msg = "Path resolution returned more than one element"
        inconclusive(vs, context.location, msg)
    }

  /**
    * Evaluates the AND expression and returns the result
    * @param and     - The AND expression
    * @param context - The context
    * @return The evaluation result
    */
  def and(and: AND, context: Element)(implicit l: ValueSetLibrary, s: Separators,
                                      dtz: Option[TimeZone]): EvalResult =
    eval(and.exp1, context) match {
      case i: Inconclusive => i
      case f: Fail         => Failures.and(and, context, f)
      case Pass            =>
        eval( and.exp2, context ) match {
          case f: Fail => Failures.and(and, context, f)
          case x       => x
        }
    }

  /**
    * Evaluates the OR expression and returns the result
    * @param or      - The OR expression
    * @param context - The context
    * @return The evaluation result
    */
  def or(or: OR, context: Element)(implicit l: ValueSetLibrary, s: Separators,
                                   dtz: Option[TimeZone]): EvalResult =
    eval( or.exp1, context ) match {
      case f1: Fail =>
        eval(or.exp2, context) match {
          case f2: Fail => Failures.or(or, context, f1, f2)
          case x        => x
        }
      case x => x
    }

  /**
    * Evaluates the NOT expression and returns the result
    * @param not     - The NOT expression
    * @param context - The context
    * @return The evaluation result
    */
  def not(not: NOT, context: Element)(implicit l: ValueSetLibrary, s: Separators,
                                      dtz: Option[TimeZone]): EvalResult =
    eval( not.exp, context ) match {
      case Pass    => Failures.not( not, context)
      case f: Fail => Pass
      case i: Inconclusive => i
    }

  def xor(xor: XOR, context: Element)(implicit l: ValueSetLibrary, s: Separators,
                                      dtz: Option[TimeZone]): EvalResult = ??? //FIXME

  def imply(e: IMPLY, context: Element)(implicit l: ValueSetLibrary, s: Separators,
                                        dtz: Option[TimeZone]): EvalResult = ??? //FIXME

  def exist(e: EXIST, context: Element)(implicit l: ValueSetLibrary, s: Separators,
                                        dtz: Option[TimeZone]): EvalResult = ??? //FIXME

  def forall(e: FORALL, context: Element)(implicit l: ValueSetLibrary, s: Separators,
                                          dtz: Option[TimeZone]): EvalResult = ??? //FIXME

  def setId(e: SetId, context: Element) =
    queryAsSimple(context, e.path) match {
      case Success( x:: Nil) =>
        if( context.instance.toString == x.value.raw ) Pass
        else Failures.seqId(e, context, x)
      case Success(xs) =>
        val m = s"$e Path resolution returned more than one element"
        inconclusive(e, context.location, m)
      case Failure(f)  => inconclusive(e, context.location, f)
    }

  /**
    * Evaluates the plugin expression and returns the result
    * @param e       - The plugin expression
    * @param context - The context
    * @return The evaluation result
    */
  def plugin(e: Plugin, context: Element)(implicit s: Separators): EvalResult =
    try {
      val clazz  = Class.forName(e.clazz)
      val method = clazz.getDeclaredMethod("assertion", classOf[Element])
      method.invoke(clazz.newInstance(), context).asInstanceOf[Boolean] match {
        case true  => Pass
        case false => Fail(Nil)
      }
    } catch { case f: Throwable => inconclusive(e, context.location, f) }

  /**
    * Returns true if the unescaped value of 's' is not
    * equal to the unescaped 'text' depending on the case
    */
  private def notEqual(s: Simple, text: String, cs: Boolean)
                      (implicit separators: Separators): Boolean =
    if( cs ) ! unescape(s.value.raw).equalsIgnoreCase( text )
    else unescape(s.value.raw) != text

  /**
    * Returns true if the unescaped value of 's'
    * don't match the regular expression 'regex'
    */
  private def notMatch(s: Simple, regex: String)
                      (implicit separators: Separators): Boolean =
    !regex.r.pattern.matcher( unescape(s.value.raw) ).matches

  /**
    * Returns true if the list does not contain 's'.
    */
  private def notInList(s: String, list: List[String])
                          (implicit separators: Separators): Boolean =
    !list.contains(unescape(s))

  /**
    * Returns true if the list does not contain 'd'
    */
  private def notInList(d: Double, list: List[Double]): Boolean = !list.contains(d)

  /**
    * Returns true if s can be converted to a Double
    */
  private def convertibleToDouble(s: String): Boolean =
    try { s.toDouble; true } catch { case e: Throwable => false }

  /**
    * Creates an inconclusive result from a message
    */
  private def inconclusive(e: Expression, l: Location, m: String): Inconclusive =
    Inconclusive( Trace( e, Reason( l, m) :: Nil ) )

  /**
    * Creates an inconclusive result from a throwable
    */
  private def inconclusive(e: Expression, l: Location, t: Throwable): Inconclusive =
    inconclusive( e, l, t.getMessage )

  private def inconclusive(sv: SimpleValue, xs: List[(Simple, Try[Boolean])]) = {
    val reasons = xs map {
      case (s, Failure(e)) => Reason( s.location, e.getMessage )
      case _ => ??? //Not gonna happens
    }
    Inconclusive( Trace(sv, reasons) )
  }

  private def inconclusive(pv: PathValue, c: Element, xs1: List[Simple], xs2: List[Simple]) = {
    val p1 = s"${c.location.path}.${pv.path1}"
    val p2 = s"${c.location.path}.${pv.path2}"
    val m = s"path1($p1) and path2($p2) resolution returned respectively ${xs1.length} and ${xs1.length} elements."
    val reasons = Reason( c.location, m ) :: Nil
    Inconclusive( Trace(pv, reasons) )
  }

  //FIXME This needs to be updated according to Rob's feedback
  //FIXME For now every non null entry is considered as violation
  private def isVSViolated( e: Entry ): Boolean = e != null

}
