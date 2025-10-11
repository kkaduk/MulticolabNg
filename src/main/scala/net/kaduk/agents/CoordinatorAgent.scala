package net.kaduk.agents

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import net.kaduk.domain.*
import net.kaduk.infrastructure.registry.AgentRegistry
import net.kaduk.agents.BaseAgent.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure}

object CoordinatorAgent:
  
  case class TaskPlan(steps: Seq[TaskStep])
  case class TaskStep(agentCapability: String, instruction: String, dependencies: Seq[String] = Seq.empty)

  def apply(registry: AgentRegistry)(using ec: ExecutionContext): Behavior[Command] =
    Behaviors.setup: ctx =>
      idle(registry, Map.empty)(using ctx, ec)

  private def idle(
    registry: AgentRegistry,
    activeTasks: Map[String, (TaskPlan, ActorRef[Response])]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =

    Behaviors.receiveMessage:
      case ProcessMessage(message, context, replyTo) =>
        ctx.log.info(s"Coordinating task for conversation ${context.id}")

        val plan = decomposeTask(message.content.text)
        executePlan(plan, context, registry)

        coordinating(registry, activeTasks + (context.id -> (plan, replyTo)))

      case Stop =>
        Behaviors.stopped

      case _ =>
        Behaviors.same

  private def coordinating(
    registry: AgentRegistry,
    activeTasks: Map[String, (TaskPlan, ActorRef[Response])]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =
    Behaviors.receiveMessage:
      case msg: ProcessedMessage =>
        ctx.log.info(s"Step completed for ${msg.updatedContext.id}")
        val (plan, originalReplyTo) = activeTasks(msg.updatedContext.id)
        originalReplyTo ! msg
        idle(registry, activeTasks - msg.updatedContext.id)

      case failed: ProcessingFailed =>
        ctx.log.info(s"Step failed for ${failed.messageId}")
        val (plan, originalReplyTo) = activeTasks(failed.messageId)
        originalReplyTo ! failed
        idle(registry, activeTasks - failed.messageId)

      case Stop =>
        Behaviors.stopped

      case _ =>
        Behaviors.same

  private def decomposeTask(task: String): TaskPlan =
    if task.toLowerCase.contains("data") && task.toLowerCase.contains("visualize") then
      TaskPlan(Seq(
        TaskStep("sql-agent", "Extract data", Seq.empty),
        TaskStep("viz-agent", "Create visualization", Seq("sql-agent"))
      ))
    else
      TaskPlan(Seq(TaskStep("sql-agent", task, Seq.empty)))

  private def executePlan(
    plan: TaskPlan,
    context: ConversationContext,
    registry: AgentRegistry
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Unit =

    plan.steps.headOption.foreach: step =>
      ctx.pipeToSelf(registry.findAgent(step.agentCapability)):
        case Success(Some(agentRef)) =>
          val msg = Message(
            role = MessageRole.User,
            content = MessageContent(step.instruction),
            conversationId = context.id
          )
          val replyTo = ctx.self.asInstanceOf[ActorRef[Any]]
          agentRef ! ProcessMessage(msg, context, replyTo)
          NoOp
        case Success(None) =>
          ProcessingFailed(s"Agent ${step.agentCapability} not found", context.id)
        case Failure(ex) =>
          ProcessingFailed(ex.getMessage, context.id)
