/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel.component

import java.net.InetSocketAddress
import java.util.{Map => JMap}
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

import org.apache.camel._
import org.apache.camel.impl.{DefaultProducer, DefaultEndpoint, DefaultComponent}

import se.scalablesolutions.akka.actor._
import se.scalablesolutions.akka.camel.{Failure, Message}
import se.scalablesolutions.akka.camel.CamelMessageConversion.toExchangeAdapter
import se.scalablesolutions.akka.dispatch.{CompletableFuture, MessageInvocation, MessageDispatcher}
import se.scalablesolutions.akka.stm.TransactionConfig

import scala.reflect.BeanProperty

/**
 * @author Martin Krasser
 */
object ActorComponent {
  /**
   * Name of the message header containing the actor id or uuid.
   */
  val ActorIdentifier = "CamelActorIdentifier"
}

/**
 * Camel component for sending messages to and receiving replies from (untyped) actors.
 *
 * @see se.scalablesolutions.akka.camel.component.ActorEndpoint
 * @see se.scalablesolutions.akka.camel.component.ActorProducer
 *
 * @author Martin Krasser
 */
class ActorComponent extends DefaultComponent {
  def createEndpoint(uri: String, remaining: String, parameters: JMap[String, Object]): ActorEndpoint = {
    val (idType, idValue) = parsePath(remaining)
    new ActorEndpoint(uri, this, idType, idValue)
  }

  private def parsePath(remaining: String): Tuple2[String, Option[String]] = remaining match {
    case null | "" => throw new IllegalArgumentException("invalid path: [%s] - should be <actorid> or id:<actorid> or uuid:<actoruuid>" format remaining)
    case   id if id   startsWith "id:"   => ("id",   parseIdentifier(id substring 3))
    case uuid if uuid startsWith "uuid:" => ("uuid", parseIdentifier(uuid substring 5))
    case   id                            => ("id",   parseIdentifier(id))
  }

  private def parseIdentifier(identifier: String): Option[String] =
    if (identifier.length > 0) Some(identifier) else None
}

/**
 * Camel endpoint for sending messages to and receiving replies from (untyped) actors. Actors
 * are referenced using <code>actor</code> endpoint URIs of the following format:
 * <code>actor:<actor-id></code>,
 * <code>actor:id:[<actor-id>]</code> and
 * <code>actor:uuid:[<actor-uuid>]</code>,
 * where <code><actor-id></code> refers to <code>ActorRef.id</code> and <code><actor-uuid></code>
 * refers to the String-representation od <code>ActorRef.uuid</code>. In URIs that contain
 * <code>id:</code> or <code>uuid:</code>, an actor identifier (id or uuid) is optional. In this
 * case, the in-message of an exchange produced to this endpoint must contain a message header
 * with name <code>CamelActorIdentifier</code> and a value that is the target actor's identifier.
 * If the URI contains an actor identifier, a message with a <code>CamelActorIdentifier</code>
 * header overrides the identifier in the endpoint URI.
 *
 * @see se.scalablesolutions.akka.camel.component.ActorComponent
 * @see se.scalablesolutions.akka.camel.component.ActorProducer

 * @author Martin Krasser
 */
class ActorEndpoint(uri: String,
                    comp: ActorComponent,
                    val idType: String,
                    val idValue: Option[String]) extends DefaultEndpoint(uri, comp) {

  /**
   * Whether to block caller thread during two-way message exchanges with (untyped) actors. This is
   * set via the <code>blocking=true|false</code> endpoint URI parameter. Default value is
   * <code>false</code>.
   */
  @BeanProperty var blocking: Boolean = false

  /**
   * @throws UnsupportedOperationException
   */
  def createConsumer(processor: Processor): Consumer =
    throw new UnsupportedOperationException("actor consumer not supported yet")

  /**
   * Creates a new ActorProducer instance initialized with this endpoint.
   */
  def createProducer: ActorProducer = new ActorProducer(this)

  /**
   * Returns true.
   */
  def isSingleton: Boolean = true
}

/**
 * Sends the in-message of an exchange to an (untyped) actor, identified by an
 * actor endpoint URI or by a <code>CamelActorIdentifier</code> message header.
 * <ul>
 * <li>If the exchange pattern is out-capable and <code>blocking</code> is set to
 * <code>true</code> then the producer waits for a reply, using the !! operator.</li>
 * <li>If the exchange pattern is out-capable and <code>blocking</code> is set to
 * <code>false</code> then the producer sends the message using the ! operator, together
 * with a callback handler. The callback handler is an <code>ActorRef</code> that can be
 * used by the receiving actor to asynchronously reply to the route that is sending the 
 * message.</li>
 * <li>If the exchange pattern is in-only then the producer sends the message using the
 * ! operator.</li>
 * </ul>
 *
 * @see se.scalablesolutions.akka.camel.component.ActorComponent
 * @see se.scalablesolutions.akka.camel.component.ActorEndpoint
 *
 * @author Martin Krasser
 */
class ActorProducer(val ep: ActorEndpoint) extends DefaultProducer(ep) with AsyncProcessor {
  import ActorProducer._

  private lazy val uuid = uuidFrom(ep.idValue.getOrElse(throw new ActorIdentifierNotSetException))

  def process(exchange: Exchange) =
    if (exchange.getPattern.isOutCapable) sendSync(exchange) else sendAsync(exchange)

  def process(exchange: Exchange, callback: AsyncCallback): Boolean = {
    (exchange.getPattern.isOutCapable, ep.blocking) match {
      case (true, true) => {
        sendSync(exchange)
        callback.done(true)
        true
      }
      case (true, false) => {
        sendAsync(exchange, Some(AsyncCallbackAdapter(exchange, callback)))
        false
      }
      case (false, _) => {
        sendAsync(exchange)
        callback.done(true)
        true
      }
    }
  }

  private def sendSync(exchange: Exchange) = {
    val actor = target(exchange)
    val result: Any = actor !! requestFor(exchange)

    result match {
      case Some(msg: Failure) => exchange.fromFailureMessage(msg)
      case Some(msg)          => exchange.fromResponseMessage(Message.canonicalize(msg))
      case None               => throw new TimeoutException("timeout (%d ms) while waiting response from %s"
                                                            format (actor.timeout, ep.getEndpointUri))
    }
  }

  private def sendAsync(exchange: Exchange, sender: Option[ActorRef] = None) =
    target(exchange).!(requestFor(exchange))(sender)

  private def target(exchange: Exchange) =
    targetOption(exchange) getOrElse (throw new ActorNotRegisteredException(ep.getEndpointUri))

  private def targetOption(exchange: Exchange): Option[ActorRef] = ep.idType match {
    case "id"   => targetById(targetId(exchange))
    case "uuid" => targetByUuid(targetUuid(exchange))
  }

  private def targetId(exchange: Exchange) = exchange.getIn.getHeader(ActorComponent.ActorIdentifier) match {
    case id: String  => id
    case null        => ep.idValue.getOrElse(throw new ActorIdentifierNotSetException)
  }

  private def targetUuid(exchange: Exchange) = exchange.getIn.getHeader(ActorComponent.ActorIdentifier) match {
    case uuid: Uuid   => uuid
    case uuid: String => uuidFrom(uuid)
    case null         => uuid
  }

  private def targetById(id: String) = ActorRegistry.actorsFor(id) match {
    case actors if actors.length == 0 => None
    case actors                       => Some(actors(0))
  }

  private def targetByUuid(uuid: Uuid) = ActorRegistry.actorFor(uuid)
}

/**
 * @author Martin Krasser
 */
private[camel] object ActorProducer {
  def requestFor(exchange: Exchange) =
    exchange.toRequestMessage(Map(Message.MessageExchangeId -> exchange.getExchangeId))
}

/**
 * Thrown to indicate that an actor referenced by an endpoint URI cannot be
 * found in the ActorRegistry.
 *
 * @author Martin Krasser
 */
class ActorNotRegisteredException(uri: String) extends RuntimeException {
  override def getMessage = "%s not registered" format uri
}

/**
 * Thrown to indicate that no actor identifier has been set.
 *
 * @author Martin Krasser
 */
class ActorIdentifierNotSetException extends RuntimeException {
  override def getMessage = "actor identifier not set"
}

/**
 * @author Martin Krasser
 */
private[akka] object AsyncCallbackAdapter {
  /**
   * Creates and starts an <code>AsyncCallbackAdapter</code>.
   *
   * @param exchange message exchange to write results to.
   * @param callback callback object to generate completion notifications.
   */
  def apply(exchange: Exchange, callback: AsyncCallback) =
    new AsyncCallbackAdapter(exchange, callback).start
}

/**
 * Adapts an <code>ActorRef</code> to a Camel <code>AsyncCallback</code>. Used by receiving actors to reply
 * asynchronously to Camel routes with <code>ActorRef.reply</code>.
 * <p>
 * <em>Please note</em> that this adapter can only be used locally at the moment which should not
 * be a problem is most situations since Camel endpoints are only activated for local actor references,
 * never for remote references.
 *
 * @author Martin Krasser
 */
private[akka] class AsyncCallbackAdapter(exchange: Exchange, callback: AsyncCallback) extends ActorRef with ScalaActorRef {

  def start = {
    _status = ActorRefInternals.RUNNING
    this
  }

  def stop() = {
    _status = ActorRefInternals.SHUTDOWN
  }

  /**
   * Populates the initial <code>exchange</code> with the reply <code>message</code> and uses the
   * <code>callback</code> handler to notify Camel about the asynchronous completion of the message
   * exchange.
   *
   * @param message reply message
   * @param sender ignored
   */
  protected[akka] def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]) = {
    message match {
      case msg: Failure => exchange.fromFailureMessage(msg)
      case msg          => exchange.fromResponseMessage(Message.canonicalize(msg))
    }
    callback.done(false)
  }

  def actorClass: Class[_ <: Actor] = unsupported
  def actorClassName = unsupported
  def dispatcher_=(md: MessageDispatcher): Unit = unsupported
  def dispatcher: MessageDispatcher = unsupported
  def transactionConfig_=(config: TransactionConfig): Unit = unsupported
  def transactionConfig: TransactionConfig = unsupported
  def makeTransactionRequired: Unit = unsupported
  def makeRemote(hostname: String, port: Int): Unit = unsupported
  def makeRemote(address: InetSocketAddress): Unit = unsupported
  def homeAddress_=(address: InetSocketAddress): Unit = unsupported
  def remoteAddress: Option[InetSocketAddress] = unsupported
  def link(actorRef: ActorRef): Unit = unsupported
  def unlink(actorRef: ActorRef): Unit = unsupported
  def startLink(actorRef: ActorRef): Unit = unsupported
  def startLinkRemote(actorRef: ActorRef, hostname: String, port: Int): Unit = unsupported
  def spawn(clazz: Class[_ <: Actor]): ActorRef = unsupported
  def spawnRemote(clazz: Class[_ <: Actor], hostname: String, port: Int): ActorRef = unsupported
  def spawnLink(clazz: Class[_ <: Actor]): ActorRef = unsupported
  def spawnLinkRemote(clazz: Class[_ <: Actor], hostname: String, port: Int): ActorRef = unsupported
  def shutdownLinkedActors: Unit = unsupported
  def supervisor: Option[ActorRef] = unsupported
  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout[T](message: Any, timeout: Long, senderOption: Option[ActorRef], senderFuture: Option[CompletableFuture[T]]) = unsupported
  protected[akka] def mailbox: AnyRef = unsupported
  protected[akka] def mailbox_=(msg: AnyRef):AnyRef = unsupported
  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Unit = unsupported
  protected[akka] def restartLinkedActors(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Unit = unsupported
  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable): Unit = unsupported
  protected[akka] def linkedActors: JMap[Uuid, ActorRef] = unsupported
  protected[akka] def linkedActorsAsList: List[ActorRef] = unsupported
  protected[akka] def invoke(messageHandle: MessageInvocation): Unit = unsupported
  protected[akka] def remoteAddress_=(addr: Option[InetSocketAddress]): Unit = unsupported
  protected[akka] def registerSupervisorAsRemoteActor = unsupported
  protected[akka] def supervisor_=(sup: Option[ActorRef]): Unit = unsupported
  protected[akka] def actorInstance: AtomicReference[Actor] = unsupported

  private def unsupported = throw new UnsupportedOperationException("Not supported for %s" format classOf[AsyncCallbackAdapter].getName)
}
