package net.kaduk.agents

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import net.kaduk.domain.{Message, ConversationContext, AgentCapability}
import org.slf4j.MDC

object BaseAgent:
  
  trait Command
  case class ProcessMessage(
    message: Message,
    context: ConversationContext,
    replyTo: ActorRef[Any]
  ) extends Command

  case class StreamMessage(
    message: Message,
    context: ConversationContext,
    replyTo: ActorRef[Any]
  ) extends Command

  case object Stop extends Command
  case object GetStatus extends Command
  case object NoOp extends Command

  sealed trait Response
  case class ProcessedMessage(message: Message, updatedContext: ConversationContext) extends Response with Command
  case class ProcessingFailed(error: String, messageId: String) extends Response with Command
  case class AgentStatusResponse(status: String, load: Int) extends Response with Command
  case class ClarificationRequest(
    originalStepId: String,
    question: Message,
    context: ConversationContext,
    targetCapabilities: Set[String] = Set.empty,
    targetSkills: Set[String] = Set.empty,
    metadata: Map[String, String] = Map.empty
  ) extends Response with Command
  
  sealed trait StreamResponse
  case class StreamChunk(content: String, messageId: String) extends StreamResponse
  case class StreamComplete(message: Message) extends StreamResponse
  case class StreamError(error: String) extends StreamResponse

  def withLogging[T](ctx: ActorContext[?], conversationId: String)(f: => Behavior[T]): Behavior[T] =
    MDC.put("conversationId", conversationId)
    MDC.put("agentId", ctx.self.path.name)
    try f finally MDC.clear()
