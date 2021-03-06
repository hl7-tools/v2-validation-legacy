package hl7.v2.instance

import hl7.v2.profile.{Component => CM, Req, Datatype, Composite, Primitive}

object DataElement {

  /**
    * Creates and returns a component object
    * @param d - The datatype
    * @param r - The requirement
    * @param l - The location
    * @param v - The value
    * @param i - The instance number
    * @return A component object
    */
  def field(d: Datatype, r: Req, l: Location, v: String, i: Int)
           (implicit s: Separators): Option[Field] =
    v matches emptyField(s.cs, s.ss) match {
      case true  => None
      case false => Some {
        d match {
          case p: Primitive => SimpleField(p, r, l, i, Value(p, v))
          case c: Composite =>
            val (hasExtra, components) = children(l, c.components, v, s.cs)
            ComplexField(c, r, l, i, components, hasExtra)
        }
      }
    }

  /**
    * Creates and returns a component object
    * @param d - The datatype
    * @param r - The requirement
    * @param l - The location
    * @param v - The value
    * @return A component object
    */
  def component(d: Datatype, r: Req, l: Location, v: String)
                       (implicit s: Separators): Option[Component] =
    v matches emptyComponent( s.ss ) match {
      case true  => None
      case false => Some {
        d match {
          case p: Primitive => SimpleComponent(p, r, l, Value(p, v))
          case c: Composite =>
            val (hasExtra, x) = children(l, c.components, v, s.ss)
            val components = x.asInstanceOf[List[SimpleComponent]]
            ComplexComponent(c, r, l, components, hasExtra)
        }
      }
    }

  /**
    * Creates and returns the list of components
    */
  private def children(l: Location, ml: List[CM], v: String, sep: Char)
                      (implicit s: Separators): (Boolean, List[Component]) = {
    val max = ml.size
    val vs = if( isNull(v) ) Array.fill(max)(l.column -> "\"\"") else split(sep, v, l.column)
    val hasExtra = vs.size > max
    val _children = ml zip vs map { t =>
      val (m, (col, vv)) = t
      val pos = m.req.position
      val loc = l.copy( eType(l.path), desc=m.name, path=s"${l.path}.$pos", column=col )
      component( m.datatype, m.req, loc, vv )
    }
    (hasExtra, _children.flatten)
  }

  private def eType(p: String): EType =
    if ( p.drop(4).split("\\.").length == 1 ) EType.Component else EType.SubComponent

  /**
    * Returns if the value is Null i.e. ""
    */
  private def isNull(v: String) = v == Value.NULL

  /**
    * Regular expression to match an empty component
    */
  private def emptyComponent(ss: Char) = s"(?:\\s*$ss*\\s*)*"

  /**
    * Regular expression to match an empty field
    */
  private def emptyField(cs: Char, ss: Char) = s"(?:\\s*\\Q$cs\\E*\\s*$ss*\\s*)*"

}
