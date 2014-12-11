package hl7.v2.validation.content

import expression.Expression

sealed trait ConstraintTag

object ConstraintTag {
  case object Predicate extends ConstraintTag
  case object ConfStmt  extends ConstraintTag
}

case class Reference( chapter: String, section: String, page: Int, url: String )

/**
  * A constraint describes an assertion that shall always be verified
  */
case class Constraint( 
    id: Option[String],
    tag: Option[ConstraintTag],
    reference: Option[Reference],
    description: Option[String],
    assertion: Expression
)
