package ccn.ccnlite

import ccnliteinterface.CCNLiteInterface
import ccn.packet._
import java.io.{FileOutputStream, File}
import com.typesafe.scalalogging.slf4j.Logging
import network.NFNCommunication

object CCNLite extends Logging {
  val ccnIf = new CCNLiteInterface()

  def ccnbToXml(ccnbData: Array[Byte]): String = {
    ccnIf.ccnbToXml(ccnbData)
  }

  def mkBinaryContent(content: Content): Array[Byte] = {
    val name = content.name.cmps.toArray
    val data = content.data
    ccnIf.mkBinaryContent(name, data)
  }

  def mkBinaryContent(name: Array[String], data: Array[Byte]): Array[Byte] = {
    ccnIf.mkBinaryContent(name, data)
  }

  def mkBinaryInterest(interest: Interest): Array[Byte] = {
    ccnIf.mkBinaryInterest(interest.name.cmps.toArray)
  }

  def mkBinaryPacket(packet: CCNPacket): Array[Byte] = {
    packet match {
      case i: Interest => mkBinaryInterest(i)
      case c: Content => mkBinaryContent(c)
    }
  }

  def mkBinaryInterest(name: Array[String]): Array[Byte] = {
    ccnIf.mkBinaryInterest(name)
  }


  def mkAddToCacheInterest(content: Content): Array[Byte] = {
    val binaryContent = mkBinaryContent(content)

    val servLibDir = new File("./service-library")
    if(!servLibDir.exists) {
      servLibDir.mkdir()
    }
    val filename = s"./service-library/test-${content.name.hashCode}-${System.nanoTime}.ccnb"
    val file = new File(filename)

    // Just to be sure, if the file already exists, wait quickly and try again
    if (file.exists) {
      logger.warn(s"Temporary file already existed, this should never happen!")
      Thread.sleep(1)
      mkAddToCacheInterest(content)
    } else {
      file.createNewFile
      val out = new FileOutputStream(file)
      try {
        out.write(binaryContent)
      } finally {
        if (out != null) out.close
      }
      val absoluteFilename = file.getCanonicalPath
      val binaryInterest = ccnIf.mkAddToCacheInterest(absoluteFilename)
      file.delete
      binaryInterest
    }
  }

  def base64CCNBToPacket(base64ccnb: String): Option[CCNPacket] = {
    NFNCommunication.parseCCNPacket(CCNLite.ccnbToXml(NFNCommunication.decodeBase64(base64ccnb)))
  }

  private def mkAddToCacheInterest(ccnbAbsoluteFilename: String): Array[Byte] = {
    ccnIf.mkAddToCacheInterest(ccnbAbsoluteFilename)
  }
}


object CCNLiteTest extends App {
  val i = Interest("test", "name")
  println("creating binary interst")
  val bi = CCNLite.mkBinaryInterest(i)

  println(s"creating parsed interst for:\n${new String(bi)}")

  val parsedI = CCNLite.ccnbToXml(bi)

  println(s"parsed interest:\n$parsedI")

  val c = Content("contentyo".getBytes, "test", "content", "name")

  println(s"creating binary content object")
  val bc = CCNLite.mkBinaryContent(c)
  println(s"creating parsed content for:\n${new String(bc)}")

  val parsedC = CCNLite.ccnbToXml(bc)

  println(s"parsedC: $parsedC")
}