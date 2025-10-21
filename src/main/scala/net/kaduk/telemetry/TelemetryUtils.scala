package net.kaduk.telemetry

import org.apache.pekko.actor.typed.ActorRef

object TelemetryUtils:

  private def publish(
      uiBus: Option[ActorRef[UiEventBus.Command]],
      event: UiEventBus.UiEvent
  ): Unit =
    uiBus.foreach(_ ! UiEventBus.Publish(event))

  def chatMessage(
      uiBus: Option[ActorRef[UiEventBus.Command]],
      conversationId: String,
      role: String,
      messageId: String,
      text: String,
      agent: Option[String]
  ): Unit =
    publish(
      uiBus,
      UiEventBus.ChatMessage(conversationId, role, messageId, text, agent)
    )

  def agentStart(
      uiBus: Option[ActorRef[UiEventBus.Command]],
      conversationId: String,
      agent: String,
      stepId: String,
      messageId: String,
      refinement: Boolean
  ): Unit =
    publish(
      uiBus,
      UiEventBus.AgentStart(conversationId, agent, stepId, messageId, refinement)
    )

  def agentComplete(
      uiBus: Option[ActorRef[UiEventBus.Command]],
      conversationId: String,
      agent: String,
      stepId: String,
      responseMessageId: String,
      textLength: Int
  ): Unit =
    publish(
      uiBus,
      UiEventBus.AgentComplete(
        conversationId,
        agent,
        stepId,
        responseMessageId,
        textLength
      )
    )

  def stepDispatched(
      uiBus: Option[ActorRef[UiEventBus.Command]],
      conversationId: String,
      stepId: String,
      capability: String,
      messageId: String
  ): Unit =
    publish(
      uiBus,
      UiEventBus.StepDispatched(conversationId, stepId, capability, messageId)
    )

  def stepCompleted(
      uiBus: Option[ActorRef[UiEventBus.Command]],
      conversationId: String,
      stepId: String
  ): Unit =
    publish(uiBus, UiEventBus.StepCompleted(conversationId, stepId))

  def planComputed(
      uiBus: Option[ActorRef[UiEventBus.Command]],
      conversationId: String,
      steps: Seq[UiEventBus.StepInfo]
  ): Unit =
    publish(uiBus, UiEventBus.PlanComputed(conversationId, steps))

  def aggregateCompleted(
      uiBus: Option[ActorRef[UiEventBus.Command]],
      conversationId: String,
      textLength: Int
  ): Unit =
    publish(uiBus, UiEventBus.AggregateCompleted(conversationId, textLength))

  def errorEvent(
      uiBus: Option[ActorRef[UiEventBus.Command]],
      conversationId: String,
      message: String
  ): Unit =
    publish(uiBus, UiEventBus.ErrorEvent(conversationId, message))
