package net.kaduk.infrastructure.grpc

import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.util.Timeout

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

import org.slf4j.{Logger, LoggerFactory}

import net.kaduk.agents.BaseAgent
import net.kaduk.domain._
import net.kaduk.protobuf.agent_service.{AgentService, ChatMessage, ChatResponse, AgentStatusRequest, AgentStatusResponse}

/**
 * Server-side implementation of the generated gRPC service.
 * Bridges protobuf messages to the domain model and delegates to the Coordinator.
 */
class AgentServiceImpl(
  coordinatorRef: ActorRef[BaseAgent.Command]
)(using system: ActorSystem[?], ec: ExecutionContext) extends AgentService {

  private val log: Logger = LoggerFactory.getLogger(getClass)

  private def parseRole(s: String): MessageRole =
    MessageRole.values.find(_.toString.equalsIgnoreCase(s)).getOrElse(MessageRole.User)

  override def chat(in: Source[ChatMessage, NotUsed]): Source[ChatResponse, NotUsed] =
    in.flatMapConcat { msg =>
      log.info(s"receiving from cmd ${msg}")
      val context = ConversationContext(msg.conversationId, metadata = msg.metadata)
      val domainMsg = Message(
        role = parseRole(msg.role),
        content = MessageContent(msg.content, msg.metadata),
        conversationId = msg.conversationId
      )

      Source.future(askCoordinator(domainMsg, context)).flatMapConcat(identity)
    }

  private def askCoordinator(
    msg: Message,
    ctx: ConversationContext
  ): Future[Source[ChatResponse, NotUsed]] = {
    given Timeout = 360.seconds

    coordinatorRef.ask[Any](replyTo => BaseAgent.ProcessMessage(msg, ctx, replyTo)).map {
      case BaseAgent.ProcessedMessage(message, _) =>
        Source.single(ChatResponse(message.id, message.content.text, isComplete = true))
      case BaseAgent.ProcessingFailed(error, msgId) =>
        Source.single(ChatResponse(msgId, "", isComplete = true, error = error))
      case _ =>
        Source.single(ChatResponse("", "", isComplete = true, error = "Unknown response"))
    }
  }

  override def getAgentStatus(in: AgentStatusRequest): Future[AgentStatusResponse] =
    Future.successful(AgentStatusResponse("active", 0))
}
