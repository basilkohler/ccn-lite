package nfn

import akka.actor._
import akka.util.ByteString
import akka.event.Logging

import network._
import network.NetworkConnection._
import nfn.service._
import ccn.ccnlite.CCNLite
import ccn.packet._
import nfn.NFNWorker._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}
import java.io.{PrintWriter, StringWriter}

trait CCNPacketHandler {
  def receivedContent(content: Content): Unit

  def receivedInterest(interest: Interest): Unit
}

object NFNWorker {
  case class CCNSendReceive(interest: Interest)
  case class CCNSend(interest: Interest)
  case class CCNReceive(packet: Packet)
  case class CCNAddToCache(content: Content)
  case class ComputeResult(content: Content)
}
/**
 * Worker Actor which responds to ccn interest and content packets
 */
case class NFNWorker() extends Actor {

  val nfnSocket = context.actorOf(Props(new NetworkConnection()), name = "udpsocket")

  val logger = Logging(context.system, this)
  val name = self.path.name

  val ccnIf = CCNLite

  var pendingInterests: Map[Seq[String], ActorRef] = Map()

  override def preStart() = {
    nfnSocket ! Handler(self)
  }

  private def createComputeWorker(interest: Interest): ActorRef =
    context.actorOf(Props(classOf[ComputeWorker], self), s"ComputeWorker-${interest.hashCode}")

  override def receive: Actor.Receive = {

    // received Data from network
    // If it is an interest, start a compute request
//    case CCNReceive(packet) => {
//      packet match {
//        case interest: Interest => {
//          val computeWorker = createComputeWorker(interest)
//          pendingInterests += (interest.name -> computeWorker)
//          computeWorker.tell(interest, self)
//        }
//        case content: Content => {
//          pendingInterests.get(content.name) match {
//            case Some(interestSender) => interestSender ! content
//            case None => logger.error(s"Discarding content $content without pending interest")
//          }
//        }
//      }
//    }
    case data: ByteString => {
      val byteArr = data.toByteBuffer.array.clone
      val maybePacket: Option[Packet] = NFNCommunication.parseXml(ccnIf.ccnbToXml(byteArr))

      logger.info(s"$name received ${maybePacket.getOrElse("unparsable data")}")
      maybePacket match {
        // Received an interest from the network (byte format) -> spawn a new worker which handles the messages (if it crashes we just assume a timeout at the moment)
        case Some(interest: Interest) => {
          val computeWorker = createComputeWorker(interest)
          pendingInterests += (interest.name -> computeWorker)
          computeWorker.tell(interest, self)
        }
        // Received content, check if there is an entry in the pit. If so, send it to the attached worker, otherwise discard it
        case Some(content: Content) => {
          pendingInterests.get(content.name) match {
            case Some(interestSender) => interestSender ! content
            case None => logger.error(s"Discarding content $content without pending interest")
          }
        }
        case None => logger.error(s"Received data which cannot be parsed to a ccn packet: ${new String(byteArr)}")
      }
    }

    case CCNSendReceive(interest) => {
      logger.info(s"$name received send-recv request for interest: $interest")
      pendingInterests += (interest.name -> sender)
      send(interest)
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
      val binaryInterest = ccnIf.mkAddToCacheInterest(content)
      nfnSocket ! Send(binaryInterest)
    }
  }

  def send(packet: Packet) = nfnSocket ! Send(ccnIf.mkBinaryPacket(packet))
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
    ???
    logger.error(s"ComputeWorker received content, discarding it because it does not know what to do with it")
  }

  // Recievied compute request
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