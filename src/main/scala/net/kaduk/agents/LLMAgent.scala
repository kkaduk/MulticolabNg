package net.kaduk.agents

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.actor.typed.{PostStop, Signal}
import net.kaduk.domain.*
import net.kaduk.infrastructure.llm.LLMProvider
import net.kaduk.infrastructure.registry.AgentRegistry
import net.kaduk.agents.BaseAgent.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure}

object LLMAgent:
  
  def apply(
    capability: AgentCapability,
    provider: LLMProvider,
    registry: AgentRegistry,
    skills: Set[String] = Set.empty
  )(using ec: ExecutionContext): Behavior[Command] =
    Behaviors.setup { ctx =>
      given ActorContext[Command] = ctx
      // Register capability and skills on startup
      registry.register(ctx.self, capability, skills)
      // Start in idle behavior
      idle(capability, provider, registry, skills, Map.empty)
    }

  private def idle(
    capability: AgentCapability,
    provider: LLMProvider,
    registry: AgentRegistry,
    skills: Set[String],
    conversations: Map[String, ConversationContext]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =
    Behaviors.receiveMessage[Command] {
      case ProcessMessage(message, context, replyTo) =>
        withLogging[Command](ctx, context.id) {
          ctx.log.info(s"Processing message ${message.id} from conversation ${context.id}")

          ctx.pipeToSelf(
            provider.completion(
              context.messages.toSeq :+ message,
              capability.config.getOrElse("systemPrompt", "You are a helpful assistant")
            )
          ) {
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
          }

          processing(capability, provider, registry, skills, conversations + (context.id -> context), replyTo)
        }

      case StreamMessage(message, context, replyTo) =>
        withLogging[Command](ctx, context.id) {
          ctx.log.info(s"Streaming message ${message.id}")

          given Materializer = Materializer(ctx.system)

          val stream = provider.streamCompletion(
            context.messages.toSeq :+ message,
            capability.config.getOrElse("systemPrompt", "")
          )

          val completionFuture = stream.runWith(Sink.fold("")(_ + _.content))

          ctx.pipeToSelf(completionFuture) {
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
          }

          // Stream tokens to replyTo
          stream.runForeach { token =>
            replyTo ! StreamChunk(token.content, token.messageId)
          }

          processing(capability, provider, registry, skills, conversations + (context.id -> context), replyTo)
        }

      case GetStatus =>
        ctx.log.debug("Status check")
        Behaviors.same

      case Stop =>
        ctx.log.info("Shutting down LLM agent")
        registry.deregister(ctx.self, capability)
        if skills.nonEmpty then registry.deregisterSkills(ctx.self, skills)
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
    }

  private def processing(
    capability: AgentCapability,
    provider: LLMProvider,
    registry: AgentRegistry,
    skills: Set[String],
    conversations: Map[String, ConversationContext],
    pendingReply: ActorRef[?]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =
    Behaviors.receiveMessage[Command] {
      case Stop =>
        registry.deregister(ctx.self, capability)
        if skills.nonEmpty then registry.deregisterSkills(ctx.self, skills)
        ctx.log.debug(s"Deregistered agent and skills")
        Behaviors.stopped

      case _ =>
        ctx.log.warn("Ignoring message while processing")
        Behaviors.same
    }
