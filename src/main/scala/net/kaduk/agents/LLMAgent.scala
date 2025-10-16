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
import net.kaduk.telemetry.UiEventBus
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure}

object LLMAgent:
  
  def apply(
    capability: AgentCapability,
    provider: LLMProvider,
    registry: AgentRegistry,
    uiBus: Option[ActorRef[UiEventBus.Command]] = None
  )(using ec: ExecutionContext): Behavior[Command] =
    Behaviors.setup { ctx =>
      given ActorContext[Command] = ctx
      // Register capability and skills on startup
      registry.register(ctx.self, capability)
      // Start in idle behavior
      idle(capability, provider, registry, Map.empty, uiBus)
    }

  private def idle(
    capability: AgentCapability,
    provider: LLMProvider,
    registry: AgentRegistry,
    conversations: Map[String, ConversationContext],
    uiBus: Option[ActorRef[UiEventBus.Command]]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =
    Behaviors.receiveMessage[Command] {
      case ProcessMessage(message, context, replyTo) =>
        withLogging[Command](ctx, context.id) {
          ctx.log.info(s"Processing message ${message.id} from conversation ${context.id}")
          val stepId = message.content.metadata.getOrElse("stepId", "n/a")
          val refinement = message.content.metadata.getOrElse("refinement", "false")
          ctx.log.info(s"[${capability.name}] Starting stepId=$stepId refinement=$refinement msgId=${message.id}")
          uiBus.foreach(_ => UiEventBus) // keep import alive if optimized
          uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.AgentStart(context.id, capability.name, stepId, message.id, refinement.equalsIgnoreCase("true"))))

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
              ctx.log.info(s"[${capability.name}] Completed stepId=$stepId responseMsgId=${responseMsg.id} len=${response.length}")
              uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.AgentComplete(context.id, capability.name, stepId, responseMsg.id, response.length)))
              uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.ChatMessage(context.id, "assistant", responseMsg.id, response, Some(ctx.self.path.name))))
              replyTo ! ProcessedMessage(responseMsg, updatedContext)
              NoOp // Return to idle via processing handler
            case Failure(ex) =>
              ctx.log.error(s"[${capability.name}] LLM completion failed for stepId=$stepId", ex)
              uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.ErrorEvent(context.id, ex.getMessage)))
              uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.ChatMessage(context.id, "assistant", java.util.UUID.randomUUID().toString, s"Error: ${ex.getMessage}", Some(ctx.self.path.name))))
              replyTo ! ProcessingFailed(ex.getMessage, message.id)
              NoOp
          }

          processing(capability, provider, registry, conversations + (context.id -> context), replyTo, uiBus)
        }

      case StreamMessage(message, context, replyTo) =>
        withLogging[Command](ctx, context.id) {
          ctx.log.info(s"Streaming message ${message.id}")
          val stepId = message.content.metadata.getOrElse("stepId", "n/a")
          val refinement = message.content.metadata.getOrElse("refinement", "false")
          ctx.log.info(s"[${capability.name}] Streaming start stepId=$stepId refinement=$refinement msgId=${message.id}")
          uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.AgentStart(context.id, capability.name, stepId, message.id, refinement.equalsIgnoreCase("true"))))

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
              ctx.log.info(s"[${capability.name}] Streaming complete stepId=$stepId responseMsgId=${responseMsg.id} len=${fullResponse.length}")
              uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.AgentComplete(context.id, capability.name, stepId, responseMsg.id, fullResponse.length)))
              uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.ChatMessage(context.id, "assistant", responseMsg.id, fullResponse, Some(ctx.self.path.name))))
              replyTo ! StreamComplete(responseMsg)
              NoOp
            case Failure(ex) =>
              ctx.log.error(s"[${capability.name}] Streaming failed for stepId=$stepId", ex)
              uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.ErrorEvent(context.id, ex.getMessage)))
              uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.ChatMessage(context.id, "assistant", java.util.UUID.randomUUID().toString, s"Error: ${ex.getMessage}", Some(ctx.self.path.name))))
              replyTo ! StreamError(ex.getMessage)
              NoOp
          }

          // Stream tokens to replyTo
          stream.runForeach { token =>
            replyTo ! StreamChunk(token.content, token.messageId)
          }

          processing(capability, provider, registry, conversations + (context.id -> context), replyTo, uiBus)
        }

      case GetStatus =>
        ctx.log.debug("Status check")
        Behaviors.same

      case Stop =>
        ctx.log.info("Shutting down LLM agent")
        registry.deregister(ctx.self, capability)
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
    conversations: Map[String, ConversationContext],
    pendingReply: ActorRef[?],
    uiBus: Option[ActorRef[UiEventBus.Command]]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =
    Behaviors.receiveMessage[Command] {
      case Stop =>
        registry.deregister(ctx.self, capability)
        ctx.log.debug(s"Deregistered agent")
        Behaviors.stopped

      case NoOp =>
        idle(capability, provider, registry, conversations, uiBus)

      case _ =>
        ctx.log.warn("Ignoring message while processing")
        Behaviors.same
    }
