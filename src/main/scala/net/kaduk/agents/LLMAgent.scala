package net.kaduk.agents

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.Materializer
import net.kaduk.domain.*
import net.kaduk.infrastructure.llm.LLMProvider
import net.kaduk.agents.BaseAgent.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure}


object LLMAgent:
  
  def apply(
    capability: AgentCapability,
    provider: LLMProvider
  )(using ec: ExecutionContext): Behavior[Command] =
    Behaviors.setup: ctx =>
      given ActorContext[Command] = ctx
      idle(capability, provider, Map.empty)

  private def idle(
    capability: AgentCapability,
    provider: LLMProvider,
    conversations: Map[String, ConversationContext]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =
    
    Behaviors.receiveMessage:
      case ProcessMessage(message, context, replyTo) =>
        withLogging(ctx, context.id):
          ctx.log.info(s"Processing message ${message.id} from conversation ${context.id}")
          
          ctx.pipeToSelf(
            provider.completion(
              context.messages.toSeq :+ message,
              capability.config.getOrElse("systemPrompt", "You are a helpful assistant")
            )
          ):
            case Success(response) =>
              val responseMsg = Message(
                role = MessageRole.Assistant,
                content = MessageContent(response),
                conversationId = context.id,
                agentId = Some(ctx.self.path.name)
              )
              val updatedContext = context.addMessage(message).addMessage(responseMsg)
              replyTo ! ProcessedMessage(responseMsg, updatedContext)
              Stop // Return Command to transition
            case Failure(ex) =>
              ctx.log.error(s"LLM completion failed", ex)
              replyTo ! ProcessingFailed(ex.getMessage, message.id)
              Stop
          
          processing(capability, provider, conversations + (context.id -> context), replyTo)

      case StreamMessage(message, context, replyTo) =>
        withLogging(ctx, context.id):
          ctx.log.info(s"Streaming message ${message.id}")
          
          given Materializer = Materializer(ctx.system)
          
          val stream = provider.streamCompletion(
            context.messages.toSeq :+ message,
            capability.config.getOrElse("systemPrompt", "")
          )
          
          val completionFuture = stream.runWith(Sink.fold("")(_ + _.content))
          
          ctx.pipeToSelf(completionFuture):
            case Success(fullResponse) =>
              val responseMsg = Message(
                role = MessageRole.Assistant,
                content = MessageContent(fullResponse),
                conversationId = context.id,
                agentId = Some(ctx.self.path.name)
              )
              val updatedContext = context.addMessage(message).addMessage(responseMsg)
              replyTo ! StreamComplete(responseMsg)
              Stop
            case Failure(ex) =>
              replyTo ! StreamError(ex.getMessage)
              Stop
          
          // Stream tokens to replyTo
          stream.runForeach: token =>
            replyTo ! StreamChunk(token.content, token.messageId)
          
          processing(capability, provider, conversations + (context.id -> context), replyTo)

      case GetStatus =>
        ctx.log.debug("Status check")
        Behaviors.same

      case Stop =>
        ctx.log.info("Shutting down LLM agent")
        Behaviors.stopped

      case NoOp =>
        Behaviors.same

      case pm: ProcessedMessage =>
        ctx.log.debug(s"Ignoring nested ProcessedMessage in idle: $pm")
        Behaviors.same

      case pf: ProcessingFailed =>
        ctx.log.warn(s"Ignoring nested ProcessingFailed in idle: ${pf.error}")
        Behaviors.same

      case as: AgentStatusResponse =>
        ctx.log.debug(s"Ignoring nested AgentStatusResponse in idle")
        Behaviors.same


  private def processing(
    capability: AgentCapability,
    provider: LLMProvider,
    conversations: Map[String, ConversationContext],
    pendingReply: ActorRef[?]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =
    
    Behaviors.receiveMessage:
      case Stop =>
        idle(capability, provider, conversations)

      case _ =>
        ctx.log.warn("Ignoring message while processing")
        Behaviors.same
