package nfn

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Success, Failure}

import java.io.{PrintWriter, StringWriter}

import akka.actor._
import akka.util.ByteString
import akka.event.Logging

import network._
import network.UDPConnection._
import nfn.service._
import nfn.NFNMaster._
import ccn.ccnlite.CCNLite
import ccn.packet._
import ccn.{ContentStore, CCNLiteProcess}


object NFNMaster {

  case class CCNSendReceive(interest: Interest)

  case class CCNAddToCache(content: Content)

  case class ComputeResult(content: Content)

  case class Exit()

}


/**
 * Worker Actor which responds to ccn interest and content packets
 */
trait NFNMaster extends Actor {

  val logger = Logging(context.system, this)
  val name = self.path.name

  val ccnIf = CCNLite

  val cs = ContentStore()

  var pendingInterests: Map[Seq[String], ActorRef] = Map()

  private def createComputeWorker(interest: Interest): ActorRef =
    context.actorOf(Props(classOf[ComputeWorker], self), s"ComputeWorker-${interest.hashCode}")

  private def handleInterest(interest: Interest) = {
    cs.find(interest.name) match {
      case Some(content) => sender ! content
      case None => {
        val computeWorker = createComputeWorker(interest)
        pendingInterests += (interest.name -> computeWorker)
        computeWorker.tell(interest, self)
      }
    }
  }


  // Check pit for an address to return content to, otherwise discard it
  private def handleContent(content: Content) = {
    pendingInterests.get(content.name) match {
      case Some(interestSender) => {
        interestSender ! content
        pendingInterests -= content.name
      }
      case None => logger.error(s"Discarding content $content because there is no entry in pit")
    }
  }

  def handlePacket(packet: Packet) = {
    packet match {
      case i: Interest => handleInterest(i)
      case c: Content => handleContent(c)
    }
  }

  override def receive: Actor.Receive = {

    // received Data from network
    // If it is an interest, start a compute request
//    case CCNReceive(packet) => handlePacket(packet)
    case packet:Packet => handlePacket(packet)

    case data: ByteString => {
      val byteArr = data.toByteBuffer.array.clone
      val maybePacket: Option[Packet] = NFNCommunication.parseXml(ccnIf.ccnbToXml(byteArr))

      logger.info(s"$name received ${maybePacket.getOrElse("unparsable data")}")
      maybePacket match {
        // Received an interest from the network (byte format) -> spawn a new worker which handles the messages (if it crashes we just assume a timeout at the moment)
        case Some(packet: Packet) => handlePacket(packet)
        case None => logger.error(s"Received data which cannot be parsed to a ccn packet: ${new String(byteArr)}")
      }
    }

    case CCNSendReceive(interest) => {
      cs.find(interest.name) match {
        case Some(content) => {
          logger.debug(s"Received SendReceive request, found content for interest $interest in local CS")
          sender ! content
        }
        case None => {
          logger.debug(s"Received SendReceive request, aksing network for $interest ")
          pendingInterests += (interest.name -> sender)
          send(interest)
        }
      }
    }
    // CCN worker itself is responsible to handle compute requests from the network (interests which arrived in binary format)
    // convert the result to ccnb format and send it to the socket
    case ComputeResult(content) => {
      pendingInterests.get(content.name) match {
        case Some(worker) => {
          logger.debug("sending compute result to socket")
          send(content)
          // TODO
          // context.stop(worker)
          // pit -= content.name
          // but first make sure we are always talking about the same worker and that there are not several worker for the same name!
        }
        case None => logger.error(s"Received result from compute worker which timed out, discarding the result content: $content")
      }
    }

    case CCNAddToCache(content) => {
      logger.debug(s"sending add to cache for name ${content.name.mkString("/")}")
      sendAddToCache(content)
    }

    case Exit() => {
      exit()
      context.system.shutdown()
    }
  }

  def send(packet: Packet)
  def sendAddToCache(content: Content)
  def exit(): Unit = ()
}

case class NFNMasterNetwork() extends NFNMaster {

  val ccnLiteNFNNetworkProcess = CCNLiteProcess()
  ccnLiteNFNNetworkProcess.start()

  val nfnSocket = context.actorOf(Props(new UDPConnection()), name = "udpsocket")

  override def preStart() = {
    nfnSocket ! Handler(self)
  }


  override def send(packet: Packet): Unit = {
    nfnSocket ! Send(ccnIf.mkBinaryPacket(packet))
  }

  override def sendAddToCache(content: Content): Unit = {
//    cs.add(content)
    nfnSocket ! Send(ccnIf.mkAddToCacheInterest(content))
  }

  override def exit(): Unit = {
    ccnLiteNFNNetworkProcess.stop()
  }
}

case class NFNMasterLocal() extends NFNMaster {

  val localAM = context.actorOf(Props(classOf[LocalAbstractMachineWorker], self), name = "localAM")

  override def send(packet: Packet): Unit = localAM ! packet

  override def sendAddToCache(content: Content): Unit = {
    cs.add(content)
  }
}



/**
 *
 */
case class ComputeWorker(ccnWorker: ActorRef) extends Actor {

  val name = self.path.name
  val logger = Logging(context.system, this)
  val ccnIf = CCNLite

  private var result : Option[String] = None

  def receivedContent(content: Content) = {
    // Received content from request (sendrcv)
    logger.error(s"ComputeWorker received content, discarding it because it does not know what to do with it")
  }

  // Received compute request
  // Make sure it actually is a compute request and forward to the handle method
  def receivedInterest(interest: Interest, requestor: ActorRef) = {
    logger.debug(s"received compute interest: $interest")
    val cmps = interest.name
    val computeCmps = cmps match {
      case Seq("COMPUTE", reqNfnCmps @ _ *) => {
        val computeCmps = reqNfnCmps.take(reqNfnCmps.size - 1)
        handleComputeRequest(computeCmps, interest, requestor)
      }
      case _ => logger.error(s"Dropping interest $interest because it is not a compute request")
    }
  }

  def handleComputeRequest(computeCmps: Seq[String], interest: Interest, requestor: ActorRef) = {
    logger.debug(s"Handling compute request for cmps: $computeCmps")
    val callableServ: Future[CallableNFNService] = NFNService.parseAndFindFromName(computeCmps.mkString(" "), ccnWorker)

    callableServ onComplete {
      case Success(callableServ) => {
        val result = callableServ.exec
        val content = Content(interest.name, result.toValueName.name.mkString(" ").getBytes)
        logger.debug(s"Finished computation, result: $content")
        requestor ! ComputeResult(content)
      }
      case Failure(e) => {
        val sw = new StringWriter()
        val pw = new PrintWriter(sw)
        e.printStackTrace(pw)
        logger.error(sw.toString)
      }
    }
  }

  override def receive: Actor.Receive = {
    case content: Content => receivedContent(content)
    case interest: Interest => {
      // Just to make sure we are not closing over sender
      val requestor = sender
      receivedInterest(interest, requestor)
    }
  }
}