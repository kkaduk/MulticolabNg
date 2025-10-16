package net.kaduk.telemetry

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

object UiEventBus {

  // -------- UI event model --------
  sealed trait UiEvent { def conversationId: String }

  final case class StepInfo(id: String, capability: String, dependencies: Seq[String])

  final case class PlanComputed(conversationId: String, steps: Seq[StepInfo]) extends UiEvent
  final case class StepDispatched(conversationId: String, stepId: String, capability: String, messageId: String) extends UiEvent
  final case class StepCompleted(conversationId: String, stepId: String) extends UiEvent
  final case class AggregateCompleted(conversationId: String, textLength: Int) extends UiEvent
  final case class AgentStart(conversationId: String, agent: String, stepId: String, messageId: String, refinement: Boolean) extends UiEvent
  final case class AgentComplete(conversationId: String, agent: String, stepId: String, responseMessageId: String, textLength: Int) extends UiEvent
  final case class ChatMessage(conversationId: String, role: String, messageId: String, text: String, agent: Option[String]) extends UiEvent
  final case class ErrorEvent(conversationId: String, message: String) extends UiEvent

  // -------- Event bus protocol --------
  sealed trait Command
  final case class Publish(ev: UiEvent) extends Command
  final case class Subscribe(subscriber: ActorRef[UiEvent]) extends Command
  final case class Unsubscribe(subscriber: ActorRef[UiEvent]) extends Command

  def apply(): Behavior[Command] =
    Behaviors.setup { _ =>
      var subscribers = Set.empty[ActorRef[UiEvent]]
      var history     = Vector.empty[UiEvent]

      Behaviors.receiveMessage {
        case Subscribe(s) =>
          subscribers += s
          // Replay recent history to new subscriber
          history.foreach(ev => s ! ev)
          Behaviors.same

        case Unsubscribe(s) =>
          subscribers -= s
          Behaviors.same

        case Publish(ev) =>
          // Append to history (cap at 500 for memory safety) and broadcast
          history = (history :+ ev).takeRight(500)
          subscribers.foreach(_ ! ev)
          Behaviors.same
      }
    }

  // -------- Minimal JSON serialization (no external deps) --------
  private def esc(s: String): String =
    s.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c    => c.toString
    }

  def toJson(ev: UiEvent): String = ev match {
    case PlanComputed(cid, steps) =>
      val stepsJson = steps
        .map { si =>
          val deps = si.dependencies.map(d => s""""${esc(d)}"""").mkString(",")
          s"""{"id":"${esc(si.id)}","capability":"${esc(si.capability)}","dependencies":[$deps]}"""
        }
        .mkString(",")
      s"""{"type":"plan","conversationId":"${esc(cid)}","steps":[$stepsJson]}"""

    case StepDispatched(cid, stepId, cap, msgId) =>
      s"""{"type":"dispatch","conversationId":"${esc(cid)}","stepId":"${esc(stepId)}","capability":"${esc(cap)}","messageId":"${esc(msgId)}"}"""

    case StepCompleted(cid, stepId) =>
      s"""{"type":"stepCompleted","conversationId":"${esc(cid)}","stepId":"${esc(stepId)}"}"""

    case AggregateCompleted(cid, len) =>
      s"""{"type":"aggregate","conversationId":"${esc(cid)}","length":$len}"""

    case AgentStart(cid, agent, stepId, msgId, ref) =>
      s"""{"type":"agentStart","conversationId":"${esc(cid)}","agent":"${esc(agent)}","stepId":"${esc(stepId)}","messageId":"${esc(msgId)}","refinement":$ref}"""

    case AgentComplete(cid, agent, stepId, respId, len) =>
      s"""{"type":"agentComplete","conversationId":"${esc(cid)}","agent":"${esc(agent)}","stepId":"${esc(stepId)}","responseMessageId":"${esc(respId)}","length":$len}"""

    case ChatMessage(cid, role, msgId, text, agentOpt) =>
      s"""{"type":"chat","conversationId":"${esc(cid)}","role":"${esc(role)}","messageId":"${esc(msgId)}","agent":"${esc(agentOpt.getOrElse(""))}","text":"${esc(text)}"}"""

    case ErrorEvent(cid, msg) =>
      s"""{"type":"error","conversationId":"${esc(cid)}","message":"${esc(msg)}"}"""
  }
}
