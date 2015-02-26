package hl7.v2.validation.vs

/**
  * Trait representing a code usage
  */
sealed trait CodeUsage { def description: String }
object CodeUsage {
  case object R extends CodeUsage { val description = "Required"  }
  case object P extends CodeUsage { val description = "Permitted" }
  case object E extends CodeUsage { val description = "Excluded"  }
}

/**
  * Class representing a code
  */
case class Code(value: String, description: String, usage: CodeUsage, codeSys: String)

/**
  * Trait representing a value set extensibility
  */
sealed trait Extensibility
object Extensibility {
  case object Open  extends Extensibility
  case object Close extends Extensibility
}

/**
  * Trait representing a value set stability
  */
sealed trait Stability
object Stability {
  case object Static  extends Stability
  case object Dynamic extends Stability
}

/**
  * Class representing a value set
  */
case class ValueSet(
    id: String,
    extensibility: Extensibility,
    stability: Stability,
    codes: Set[Code]
)

/**
  * Trait representing a binding strength
  */
trait BindingStrength  { def desc: String }
object BindingStrength {
  case object R extends BindingStrength { def desc = "Required"     }
  case object S extends BindingStrength { def desc = "Suggested"    }
  case object U extends BindingStrength { def desc = "Undetermined" }
}
