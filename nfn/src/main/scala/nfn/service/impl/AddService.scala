package nfn.service.impl

import nfn.service._
import com.typesafe.scalalogging.slf4j.Logging
import scala.util.Try
import nfn.service.NFNName
import nfn.service.NFNIntValue
import nfn.service.CallableNFNService

/**
 * Created by basil on 24/03/14.
 */
case class AddService() extends  NFNService with Logging {
  override def parse(unparsedName: String, unparsedValues: Seq[String]): CallableNFNService = {
    logger.debug("parse")
    val values = unparsedValues match {
      case Seq(lStr, rStr) => Seq(
          NFNIntValue(lStr.toInt),
          NFNIntValue(rStr.toInt)
      )
      case _ => throw new Exception(s"Service $toNFNName could not parse two int values from: '$unparsedValues'")
    }
    val name = NFNName(Seq(unparsedName))
    assert(name == this.toNFNName)

    val function = { (values: Seq[NFNServiceValue]) =>
      values match {
        case Seq(l: NFNIntValue, r: NFNIntValue) => { Try(
          NFNIntValue(l.amount + r.amount)
        )}
        case _ => throw new NFNServiceException(s"${this.toNFNName} can only be applied to a single NFNIntValue and not $values")
      }

    }
    CallableNFNService(name, values, function)
  }

  override def toNFNName: NFNName = NFNName(Seq("/AddService/Int/Int/rInt"))
}