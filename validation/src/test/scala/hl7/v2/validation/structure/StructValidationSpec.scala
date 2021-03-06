package hl7.v2.validation.structure

import gov.nist.validation.report.Entry
import hl7.v2.instance.{EType, Location}
import hl7.v2.parser.impl.DefaultParser
import hl7.v2.profile.{Range, XMLDeserializer}
import hl7.v2.validation.report._
import hl7.v2.validation.vs.ValueSet
import org.specs2.Specification

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

/**
  * Integration test for the structure validation
  */

trait StructValidationSpec 
       extends Specification
       with Validator
       with DefaultParser 
     { def is = s2"""

  Structure validation specification

    The minimally populated message should pass the validation             $e1
    The structure validation should correctly report usage errors          $e2
    The structure validation should correctly report cardinality errors    $e3
    The structure validation should correctly report length errors         $e4
    The structure validation should correctly report invalid lines         $e5
    The structure validation should correctly report unexpected segments   $e6
    The structure validation should correctly report extra elements        $e7
    The structure validation should correctly report separators in a value $e8
  """

  //TODO: Implements invalid lines and unexpected segments

  val valueSetLibrary = Map[String, ValueSet]()

  val profile = {
    val xml = getClass.getResourceAsStream("/ORU_R01_Profile.xml")
    val r = XMLDeserializer.deserialize( xml )
    assert(r.isSuccess, "[Error] An error occurred while creating the profile.")
    r.get
  }

  val mm = profile.messages.getOrElse("ORU_R01",
                           throw new Error("Unable to find the message model") )

  /**
    * Valid message test
    */
  def e1 = {
    // Minimally populated valid message
    val m = """/MSH|^~\&#
               /PID|11||~^^^&3.4.2
               /UAC
               /UAC""".stripMargin('/')

    validate(m) === Nil
  }


  /**
    * Usage test
    */
  def e2 = {
    /*
     * [2, 1] SFT is W but Present
     * [3, 1] UAC is X but Present
     * [4, 1] PID.1 is R but is missing
     * [4, 6] PID.2 is W but present
     * [4, 9] PID.3[2].1 is X but present
     * [7, 1] ORDER is X but present
     */
    val m = """/MSH|^~\&#
               /SFT
               /UAC
               /PID||x|~1^^^&3.4.2
               /UAC
               /UAC
               /SFT""".stripMargin('/')
    val expected =
      List(
          W(EType.Segment, "Software Segment", "SFT", 2, 1),
          X(EType.Segment, "User Authentication Credential Segment", "UAC",3 , 1),
          R(EType.Field, "Set ID - PID", "PID-1", 4,1),
          W(EType.Field, "Patient ID", "PID-2", 4, 6),
          X(EType.Component, "ID Number", "PID-3.1", 4, 9),
          X(EType.Group, "ORDER", "ORDER", 7, 1)
      )

    validate(m) must containTheSameElementsAs( expected )
  }

  /**
    * Cardinality test
    */
  def e3 = {
    /*
     * [7, 1] PATIENT[3] cardinality (3) not in range [ 1..2 ]
     * [5,11] PID[1].3 cardinality (4) not in range [ 2..3 ]
     * [6, 1] UAC cardinality (1) not in range [ 2..2 ]
     */
    val m = """/MSH|^~\&#
               /PID|11||~^^^&3.4.2
               /UAC
               /UAC
               /PID|22||~~~^^^&3.4.2
               /UAC
               /PID|33||~~^^^&3.4.2
               /UAC
               /UAC""".stripMargin('/')
    val expected =
      List(
        MaxC(EType.Group, "PATIENT", "PATIENT", 7, 1, 3, Range(1, "2")),
        MaxC(EType.Field, "Patient Identifier List", "PID-3", 5, 12, 4, Range(2, "3")),
        MinC(EType.Segment, "User Authentication Credential Segment","UAC",6,1,1,Range(2,"2"))
      )

    validate(m) must containTheSameElementsAs( expected )
  }

  /**
    * Length test
    */
  def e4 = {
    /*
     * [2, 5] PID[1].1 length (1) not in range [ 2..3 ]
     * [5, 5] PID[1].1 length (4) not in range [ 2..3 ]
     */
    val m = """/MSH|^~\&#
               /PID|1||~^^^&3.4.2
               /UAC
               /UAC
               /PID|333\F\||~^^^&3.4.2
               /UAC
               /UAC""".stripMargin('/')
    val expected = List(
            Len(EType.Field, "Set ID - PID", "PID-1", 2, 5, "1", Range(2, "3")),
            Len(EType.Field, "Set ID - PID", "PID-1", 5, 5, "333|", Range(2, "3"))
    )

    validate(m) must containTheSameElementsAs( expected )
  }

  /**
    * Invalid lines test
    */
  def e5 = {
    /*
     *    (1, "sss")
     *    (4, "xzsas")
     *    (6, "PID!")
     */
    val m = """sss
              /MSH|^~\&#
              /PID|11||~^^^&3.4.2
              /xzsas
              /UAC
              /PID!
              /UAC""".stripMargin('/')
    //val expected = List( InvalidLines( List( Line(1, "sss"), Line(4, "xzsas"), Line(6,"PID!")) ) )
    val expected = List( (1, "sss"), (4, "xzsas"), (6,"PID!")) map { t => Detections.invalid (t._1, t._2) }

    validate(m) must containTheSameElementsAs( expected )
  }

  /**
    * Unexpected lines test
    */
  def e6 = {
    /*
     * (5, PDQ|1)
     */
    //FIXME improve test
    val m = """/MSH|^~\&#
               /PID|11||~^^^&3.4.2
               /UAC
               /UAC
               /PDQ|1""".stripMargin('/')
    val expected = Detections.unexpected(5, "PDQ|1") :: Nil //List( UnexpectedLines( Line(5, "PDQ|1") :: Nil ) )

    validate(m) must containTheSameElementsAs( expected )
  }

  /**
    * Extra children test
    */
  def e7 = {
    /*
     * [2, 10] PID[1].3[2]
     * [2, 13] PID[1].3[2].4[1]
     */
    val m = """/MSH|^~\&#
               /PID|11||~^^^&3.4.2&&HD.4^CX.5
               /UAC
               /UAC""".stripMargin('/')
    val expected =
      List(
        Detections.extra( Location(EType.Field, "Patient Identifier List", "PID-3", 2, 10) ),
        Detections.extra( Location(EType.Component, "Assigning Authority", "PID-3.4", 2, 13) )
      )

    validate(m) must containTheSameElementsAs( expected )
  }

  /**
    * Unescaped separators in value test
    */
  def e8 = {
    /*
     * [2, 5] PID[1].1
     */
    val m = """/MSH|^~\&#
                /PID|1^&||~^^^&3.4.2
                /UAC
                /UAC""".stripMargin('/')
    val expected =
      List(
        Detections.unescaped( Location(EType.Field, "Set ID - PID", "PID-1", 2, 5) )
      )

    validate(m) must containTheSameElementsAs( expected )
  }

  private def validate(m: String): Seq[Entry] = parse(m, mm) match {
    case Success(msg) =>
      Await.result( checkStructure(msg) , Duration(2, "seconds"))
    case Failure(e) => throw e
  }

  private def R(et: EType, d: String, p: String, l: Int, c: Int) = Detections.rusage( Location(et, d, p, l, c) )
  private def X(et: EType, d: String, p: String, l: Int, c: Int) = Detections.xusage( Location(et, d, p, l, c) )
  private def W(et: EType, d: String, p: String, l: Int, c: Int) = Detections.wusage( Location(et, d, p, l, c) )

  private def MaxC(et: EType, d: String, p: String, l: Int, c: Int, i: Int, r: Range) =
    Detections.cardinality(Location(et, d, p, l, c), r, i)

  private def MinC(et: EType, d: String, p: String, l: Int, c: Int, i: Int, r: Range) =
    Detections.cardinality(Location(et, d, p, l, c), r, i)

  private def Len(et: EType, d: String, p: String, l: Int, c: Int, v: String, r: Range) =
    Detections.length(Location(et, d, p, l, c), r, v)

}
