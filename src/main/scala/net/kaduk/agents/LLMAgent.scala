package net.kaduk.agents

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import net.kaduk.domain.*
import net.kaduk.infrastructure.llm.LLMProvider
import net.kaduk.infrastructure.registry.AgentRegistry
import net.kaduk.agents.BaseAgent.*
import net.kaduk.telemetry.UiEventBus
import org.slf4j.LoggerFactory
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure, Try}
import scala.concurrent.duration.*
import java.util.UUID

object LLMAgent:

  // Thread-safe logger for use from Future callbacks (avoid ActorContext logging off-thread)
  private val log = LoggerFactory.getLogger("LLMAgent")

  // ========== Extended Protocol ==========

  sealed trait InternalCommand extends Command

  case class PlanTask(
      message: Message,
      context: ConversationContext,
      replyTo: ActorRef[Any]
  ) extends InternalCommand

  case class ExecutePlan(
      plan: ExecutionPlan,
      context: ConversationContext,
      replyTo: ActorRef[Any]
  ) extends InternalCommand

  case class StepCompleted(
      stepId: String,
      result: String,
      agentId: String
  ) extends InternalCommand

  case class StepFailed(
      stepId: String,
      error: String
  ) extends InternalCommand

  case class AgentDiscoveryResult(
      stepId: String,
      agents: Set[ActorRef[BaseAgent.Command]]
  ) extends InternalCommand

  // ========== Actor Factory ==========

  def apply(
      capability: AgentCapability,
      provider: LLMProvider,
      registry: AgentRegistry,
      uiBus: Option[ActorRef[UiEventBus.Command]] = None,
      planningEnabled: Boolean = true
  )(using ec: ExecutionContext): Behavior[Command] =
    Behaviors.setup { ctx =>
      given ActorContext[Command] = ctx

      // Register with extended skills
      val skills =
        capability.skills ++ Set("planning", "reasoning", "orchestration")
      registry.register(ctx.self, capability.copy(skills = skills), skills)

      ctx.log.info(
        s"[${capability.name}] Initialized with planning=${planningEnabled}, skills=${skills.mkString(",")}"
      )

      idle(
        capability,
        provider,
        registry,
        Map.empty,
        uiBus,
        planningEnabled,
        Map.empty
      )
    }

  // ========== State: Idle ==========

  private def idle(
      capability: AgentCapability,
      provider: LLMProvider,
      registry: AgentRegistry,
      conversations: Map[String, ConversationContext],
      uiBus: Option[ActorRef[UiEventBus.Command]],
      planningEnabled: Boolean,
      activePlans: Map[String, ExecutionPlan]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =
    Behaviors.receiveMessage[Command] {

      case ProcessMessage(message, context, replyTo) =>
        BaseAgent.withLogging(ctx, context.id) {
          ctx.log.info(s"[${capability.name}] Received message ${message.id}")

          uiBus.foreach(
            _ ! UiEventBus.Publish(
              UiEventBus.ChatMessage(
                context.id,
                message.role.toString,
                message.id,
                message.content.text,
                message.agentId
              )
            )
          )

          if planningEnabled && shouldPlan(message, context) then
            ctx.log.info(s"[${capability.name}] Initiating planning phase")
            ctx.self ! PlanTask(message, context, replyTo)
            Behaviors.same
          else
            // Direct execution (existing logic)
            executeDirectly(
              message,
              context,
              replyTo,
              capability,
              provider,
              registry,
              conversations,
              uiBus,
              planningEnabled,
              activePlans
            )
        }

      case PlanTask(message, context, replyTo) =>
        BaseAgent.withLogging(ctx, context.id) {
          ctx.log.info(
            s"[${capability.name}] Planning task for message ${message.id}"
          )

          uiBus.foreach { bus =>
            val stateMsg = Message(
              role = MessageRole.System,
              content = MessageContent(s"Planning started"),
              conversationId = context.id,
              agentId = Some(capability.name)
            )
            bus ! UiEventBus.Publish(
              UiEventBus.ChatMessage(
                context.id,
                stateMsg.role.toString,
                stateMsg.id,
                stateMsg.content.text,
                stateMsg.agentId
              )
            )
          }

          ctx.pipeToSelf(
            createPlan(message, context, provider, registry, uiBus, capability)
          ) {
            case Success(planResp) =>
              ctx.log.info(
                s"[${capability.name}] Plan created: ${planResp.plan.steps.size} steps, confidence=${planResp.confidence}"
              )

              // Emit plan to UI
              uiBus.foreach { bus =>
                val stepInfos = planResp.plan.steps.map { s =>
                  UiEventBus.StepInfo(
                    s.id,
                    s.targetCapability.getOrElse("unknown"),
                    s.dependencies.toSeq
                  )
                }
                bus ! UiEventBus.Publish(
                  UiEventBus.PlanComputed(context.id, stepInfos)
                )
              }

              ExecutePlan(planResp.plan, context, replyTo)

            case Failure(ex) =>
              ctx.log.error(
                s"[${capability.name}] Planning failed: ${ex.getMessage}",
                ex
              )
              uiBus.foreach(
                _ ! UiEventBus.Publish(
                  UiEventBus.ErrorEvent(
                    context.id,
                    s"Planning failed: ${ex.getMessage}"
                  )
                )
              )
              // Fall back to direct execution
              ProcessMessage(message, context, replyTo)
          }

          Behaviors.same
        }

      case ExecutePlan(plan, context, replyTo) =>
        BaseAgent.withLogging(ctx, context.id) {
          ctx.log.info(
            s"[${capability.name}] Executing plan ${plan.id} with ${plan.steps.size} steps with ${plan.strategy} strategy"
          )

          // Publish state to UI
          uiBus.foreach { bus =>
            val stateMsg = Message(
              role = MessageRole.System,
              content = MessageContent(
                s"Executing plan ${plan.id} with ${plan.steps.size} steps using ${plan.strategy} strategy"
              ),
              conversationId = context.id,
              agentId = Some(capability.name)
            )
            bus ! UiEventBus.Publish(
              UiEventBus.ChatMessage(
                context.id,
                stateMsg.role.toString,
                stateMsg.id,
                stateMsg.content.text,
                stateMsg.agentId
              )
            )
          }

          plan.strategy match {
            case ExecutionStrategy.Sequential =>
              executeSequential(
                plan,
                context,
                replyTo,
                capability,
                provider,
                registry,
                conversations,
                uiBus,
                activePlans,
                planningEnabled
              )
            case ExecutionStrategy.Parallel =>
              executeParallel(
                plan,
                context,
                replyTo,
                capability,
                provider,
                registry,
                conversations,
                uiBus,
                activePlans,
                planningEnabled
              )
            case ExecutionStrategy.Adaptive =>
              executeAdaptive(
                plan,
                context,
                replyTo,
                capability,
                provider,
                registry,
                conversations,
                uiBus,
                activePlans,
                planningEnabled
              )
          }
        }

      case StepCompleted(stepId, result, agentId) =>
        ctx.log.info(s"[${capability.name}] Step $stepId completed by $agentId")
        // Conversation id not tracked here; we emit from execution paths directly.
        Behaviors.same

      case StepFailed(stepId, error) =>
        ctx.log.warn(s"[${capability.name}] Step $stepId failed: $error")
        // Conversation id not tracked here; we emit from execution paths directly.
        Behaviors.same

      case StreamMessage(message, context, replyTo) =>
        // Existing streaming logic (unchanged)
        streamDirectly(
          message,
          context,
          replyTo,
          capability,
          provider,
          registry,
          conversations,
          uiBus,
          planningEnabled,
          activePlans
        )

      case GetStatus =>
        ctx.log.debug(
          s"[${capability.name}] Status: ${activePlans.size} active plans"
        )
        Behaviors.same

      case Stop =>
        ctx.log.info(s"[${capability.name}] Shutting down")
        registry.deregister(ctx.self, capability)
        Behaviors.stopped

      case NoOp =>
        Behaviors.same

      case _ =>
        ctx.log.warn(s"[${capability.name}] Unhandled message in idle state")
        Behaviors.same
    }

  // ========== Planning Logic ==========

  private def shouldPlan(
      message: Message,
      context: ConversationContext
  ): Boolean =
    true

  private def createPlan(
      message: Message,
      context: ConversationContext,
      provider: LLMProvider,
      registry: AgentRegistry,
      uiBus: Option[ActorRef[UiEventBus.Command]],
      capability: AgentCapability
  )(using ec: ExecutionContext): Future[PlanningResponse] =
    for {
      // Discover available agents
      agentSkillsMap <- discoverAgentCapabilities(registry)

      // Generate plan via LLM
      planPrompt = buildPlanningPrompt(message, context, agentSkillsMap)
      sysMsg = Message(
        role = MessageRole.System,
        content = MessageContent(
          "You are a task planning expert. Respond ONLY with valid JSON."
        ),
        conversationId = context.id
      )
      userMsg = Message(
        role = MessageRole.User,
        content = MessageContent(planPrompt),
        conversationId = context.id
      )
      _ = uiBus.foreach { bus =>
        bus ! UiEventBus.Publish(
          UiEventBus.ChatMessage(
            context.id,
            sysMsg.role.toString,
            sysMsg.id,
            sysMsg.content.text,
            Some(capability.name)
          )
        )
        bus ! UiEventBus.Publish(
          UiEventBus.ChatMessage(
            context.id,
            userMsg.role.toString,
            userMsg.id,
            userMsg.content.text,
            Some(capability.name)
          )
        )
      }
      planJson <- provider.completion(
        Seq(sysMsg, userMsg),
        systemPrompt = ""
      )
      // Publish assistant plan JSON
      publishPlan = uiBus.foreach { bus =>
        val planMsg = Message(
          role = MessageRole.Assistant,
          content = MessageContent(planJson),
          conversationId = context.id,
          agentId = Some(capability.name)
        )
        bus ! UiEventBus.Publish(
          UiEventBus.ChatMessage(
            context.id,
            planMsg.role.toString,
            planMsg.id,
            planMsg.content.text,
            planMsg.agentId
          )
        )
      }

      // Parse plan
      plan <- Future.fromTry(parsePlan(planJson, message, context))

    } yield PlanningResponse(plan, planJson, 0.85)

  private def discoverAgentCapabilities(registry: AgentRegistry)(using
      ec: ExecutionContext
  ): Future[Map[String, Set[String]]] =
    Future.successful(registry.listRegisteredCapabilities())

  private def buildPlanningPrompt(
      message: Message,
      context: ConversationContext,
      availableAgents: Map[String, Set[String]]
  ): String =
    val agentsList = availableAgents
      .map { case (cap, skills) =>
        s"- $cap: ${skills.mkString(", ")}"
      }
      .mkString("\n")

    s"""Decompose this task into executable steps. Available specialized agents:
$agentsList

User Query: ${message.content.text}

You are a precise JSON generator. 
Return **only** valid JSON — no explanations, no markdown, no comments.

Generate a JSON object with the following structure:
{
  "steps": [
    {
      "id": "step-1",
      "description": "Extract named entities",
      "requiredSkills": ["ner"],
      "targetCapability": "ner",
      "dependencies": []
    }
  ],
  "strategy": "parallel",
  "reasoning": "Why this plan"
}

Use double quotes for all keys and string values.
Output only JSON — nothing else.

"""

  private def parsePlan(
      json: String,
      message: Message,
      context: ConversationContext
  ): Try[ExecutionPlan] =
    Try {
      // Be tolerant to LLM formatting: strip code fences and isolate the JSON object
      val cleaned = sanitizeJson(json)

      // Use Jackson to parse JSON robustly (available transitively via pekko-serialization-jackson)
      val mapper = new com.fasterxml.jackson.databind.ObjectMapper()
      val root = mapper.readTree(cleaned)

      def getText(
          n: com.fasterxml.jackson.databind.JsonNode,
          field: String
      ): Option[String] =
        if n != null && n.has(field) then Option(n.get(field)).map(_.asText())
        else None

      def getArrayStrings(
          n: com.fasterxml.jackson.databind.JsonNode,
          field: String
      ): Seq[String] =
        if n != null && n.has(field) && n.get(field).isArray then
          val it = n.get(field).elements()
          val buf = scala.collection.mutable.ArrayBuffer.empty[String]
          while it.hasNext do buf += it.next().asText()
          buf.toSeq
        else Seq.empty

      // Support either a flat plan { "steps": [...] } or nested { "plan": { "steps": [...] } }
      val planNode =
        if root != null && root.has("steps") then root
        else if root != null && root.has("plan") then root.get("plan")
        else root

      val stepsNode =
        if planNode != null && planNode.has("steps") then planNode.get("steps")
        else null

      val steps =
        if stepsNode != null && stepsNode.isArray then
          val it = stepsNode.elements()
          val buf = scala.collection.mutable.ArrayBuffer.empty[PlanStep]
          var idx = 0
          while it.hasNext do
            val s = it.next()
            val id = getText(s, "id").getOrElse(s"step-$idx")
            val desc = getText(s, "description").getOrElse("")
            // val cap = getText(s, "targetAgent")

            val cap = getText(s, "targetAgent").orElse(getText(s, "agent"))
                      .orElse(getText(s, "targetCapability"))

            val skills = getArrayStrings(s, "requiredSkills").toSet
            val deps = getArrayStrings(s, "dependencies").toSet

            if id.trim.isEmpty || desc.trim.isEmpty then
              throw new IllegalArgumentException(
                "Invalid step: missing id or description"
              )

            buf += PlanStep(
              id = id,
              description = desc,
              requiredSkills = skills,
              dependencies = deps,
              targetCapability = cap
            )
            idx += 1
          buf.toSeq
        else Seq.empty

      if steps.isEmpty then
        throw new IllegalArgumentException("Invalid plan JSON: no steps parsed")

      val strategyText =
        getText(planNode, "strategy")
          .orElse(getText(root, "strategy"))
          .getOrElse("parallel")
          .toLowerCase()

      val strategy = strategyText match
        case "sequential" => ExecutionStrategy.Sequential
        case "adaptive"   => ExecutionStrategy.Adaptive
        case _            => ExecutionStrategy.Parallel

      ExecutionPlan(
        conversationId = context.id,
        originalQuery = message.content.text,
        steps = steps,
        strategy = strategy
      )
    }

  private def sanitizeJson(json: String): String =
    val noFences = json
      .replaceAll("(?i)```json", "")
      .replaceAll("```", "")
      .trim
    val start = noFences.indexOf('{')
    val end = noFences.lastIndexOf('}')
    if start >= 0 && end >= start then noFences.substring(start, end + 1)
    else noFences

  // ========== Execution Strategies ==========

  private def executeSequential(
      plan: ExecutionPlan,
      context: ConversationContext,
      replyTo: ActorRef[Any],
      capability: AgentCapability,
      provider: LLMProvider,
      registry: AgentRegistry,
      conversations: Map[String, ConversationContext],
      uiBus: Option[ActorRef[UiEventBus.Command]],
      activePlans: Map[String, ExecutionPlan],
      planningEnabled: Boolean
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =
    // Execute steps strictly in order, honoring dependencies as provided in the plan order.
    val run = plan.steps.foldLeft(
      Future.successful((Map.empty[String, String], context))
    ) { case (accF, step) =>
      accF.flatMap { case (accResults, ctxAcc) =>
        executeStep(
          step,
          ctxAcc,
          accResults,
          registry,
          provider,
          uiBus,
          capability
        )
          .map { case (result, newCtx) =>
            // Emit step completion to UI bus
            uiBus.foreach(
              _ ! UiEventBus.Publish(
                UiEventBus.StepCompleted(ctxAcc.id, step.id)
              )
            )
            (accResults + (step.id -> result), newCtx)
          }
      }
    }

    ctx.pipeToSelf(run) {
      case Success((results, finalCtx)) =>
        val finalResult = aggregateResults(plan, results)
        val responseMsg = Message(
          role = MessageRole.Assistant,
          content = MessageContent(finalResult),
          conversationId = finalCtx.id,
          agentId = Some(ctx.self.path.name)
        )
        uiBus.foreach(
          _ ! UiEventBus.Publish(
            UiEventBus.ChatMessage(
              finalCtx.id,
              responseMsg.role.toString,
              responseMsg.id,
              responseMsg.content.text,
              responseMsg.agentId
            )
          )
        )
        uiBus.foreach(
          _ ! UiEventBus.Publish(
            UiEventBus.AggregateCompleted(finalCtx.id, finalResult.length)
          )
        )
        replyTo ! ProcessedMessage(
          responseMsg,
          finalCtx.addMessage(responseMsg)
        )
        NoOp

      case Failure(ex) =>
        ctx.log.error(s"[${capability.name}] Sequential execution failed", ex)
        uiBus.foreach(
          _ ! UiEventBus.Publish(
            UiEventBus.ErrorEvent(
              context.id,
              s"Sequential failure: ${ex.getMessage}"
            )
          )
        )
        replyTo ! ProcessingFailed(ex.getMessage, context.id)
        NoOp
    }

    idle(
      capability,
      provider,
      registry,
      conversations + (context.id -> context),
      uiBus,
      planningEnabled,
      activePlans
    )

  private def executeParallel(
      plan: ExecutionPlan,
      context: ConversationContext,
      replyTo: ActorRef[Any],
      capability: AgentCapability,
      provider: LLMProvider,
      registry: AgentRegistry,
      conversations: Map[String, ConversationContext],
      uiBus: Option[ActorRef[UiEventBus.Command]],
      activePlans: Map[String, ExecutionPlan],
      planningEnabled: Boolean
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =

    // Execute independent steps in parallel (no dependencies)
    val independentSteps = plan.steps.filter(_.dependencies.isEmpty)

    ctx.log.info(
      s"[${capability.name}] Executing ${independentSteps.size} steps in parallel"
    )

    val tasks: Seq[Future[Either[(String, Throwable), (String, String)]]] =
      independentSteps
        .take(plan.maxParallelism)
        .map { step =>
          executeStep(
            step,
            context,
            Map.empty,
            registry,
            provider,
            uiBus,
            capability
          )
            .map { case (res, _) =>
              // Emit step completion to UI bus
              uiBus.foreach(
                _ ! UiEventBus
                  .Publish(UiEventBus.StepCompleted(context.id, step.id))
              )
              Right(step.id -> res)
            }
            .recover { case ex =>
              uiBus.foreach(
                _ ! UiEventBus.Publish(
                  UiEventBus.ErrorEvent(
                    context.id,
                    s"Step ${step.id} failure: ${ex.getMessage}"
                  )
                )
              )
              Left(step.id -> ex)
            }
        }

    val resultsFuture = Future.sequence(tasks)

    ctx.pipeToSelf(resultsFuture) {
      case Success(results) =>
        val successes = results.collect { case Right((id, res)) =>
          id -> res
        }.toMap
        val finalResult = aggregateResults(plan, successes)
        val responseMsg = Message(
          role = MessageRole.Assistant,
          content = MessageContent(finalResult),
          conversationId = context.id,
          agentId = Some(ctx.self.path.name)
        )
        uiBus.foreach(
          _ ! UiEventBus.Publish(
            UiEventBus.ChatMessage(
              context.id,
              responseMsg.role.toString,
              responseMsg.id,
              responseMsg.content.text,
              responseMsg.agentId
            )
          )
        )

        ctx.log.info(
          s"[${capability.name}] Parallel execution completed with ${successes.size}/${independentSteps.size} successes"
        )
        uiBus.foreach(
          _ ! UiEventBus.Publish(
            UiEventBus.AggregateCompleted(context.id, finalResult.length)
          )
        )

        replyTo ! ProcessedMessage(responseMsg, context.addMessage(responseMsg))
        NoOp

      case Failure(ex) =>
        ctx.log.error(s"[${capability.name}] Parallel execution failed", ex)
        uiBus.foreach(
          _ ! UiEventBus.Publish(
            UiEventBus.ErrorEvent(context.id, ex.getMessage)
          )
        )
        replyTo ! ProcessingFailed(ex.getMessage, context.id)
        NoOp
    }

    idle(
      capability,
      provider,
      registry,
      conversations + (context.id -> context),
      uiBus,
      planningEnabled,
      activePlans
    )

  private def executeAdaptive(
      plan: ExecutionPlan,
      context: ConversationContext,
      replyTo: ActorRef[Any],
      capability: AgentCapability,
      provider: LLMProvider,
      registry: AgentRegistry,
      conversations: Map[String, ConversationContext],
      uiBus: Option[ActorRef[UiEventBus.Command]],
      activePlans: Map[String, ExecutionPlan],
      planningEnabled: Boolean
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =
    // Adaptive: start with parallel, fall back to sequential on contention
    ctx.log.info(
      s"[${capability.name}] Using adaptive strategy (defaulting to parallel)"
    )
    executeParallel(
      plan,
      context,
      replyTo,
      capability,
      provider,
      registry,
      conversations,
      uiBus,
      activePlans,
      planningEnabled
    )

  // ========== Step Execution ==========

  private def executeStep(
      step: PlanStep,
      context: ConversationContext,
      priorResults: Map[String, String],
      registry: AgentRegistry,
      provider: LLMProvider,
      uiBus: Option[ActorRef[UiEventBus.Command]],
      capability: AgentCapability
  )(using
      ctx: ActorContext[Command],
      ec: ExecutionContext
  ): Future[(String, ConversationContext)] =

    log.info(s"[executeStep] Step ${step.id}: ${step.description}")

    for {
      // 1. Find suitable agent
      agentOpt <- findBestAgent(step, registry)

      // 2. Delegate or execute locally
      result <- agentOpt match {
        case Some(agent) =>
          log.info(
            s"[executeStep] Delegating step ${step.id} to agent ${agent.path.name}"
          )
          delegateToAgent(
            agent,
            step,
            context,
            priorResults,
            registry,
            uiBus,
            capability
          )

        case None =>
          log.info(
            s"[executeStep] No suitable agent, executing step ${step.id} locally"
          )
          executeSelfContained(
            step,
            context,
            priorResults,
            provider,
            uiBus,
            capability
          )
      }

    } yield (result, context)

  private def findBestAgent(
      step: PlanStep,
      registry: AgentRegistry
  )(using ec: ExecutionContext): Future[Option[ActorRef[BaseAgent.Command]]] =

    // Priority: agent -> target capability > all skills > any skill
    log.info(s"[STEP] , my ${step}")
    log.info(s"[CAPABILITY] , my ${step.targetCapability}  or agent ${step.assignedAgent}")
    step.assignedAgent match {
      case Some(agent) =>
        registry.findAgent(agent)

      case None =>
        step.targetCapability match {
          case Some(cap) =>
            registry.findAgent(cap)

          case None if step.requiredSkills.nonEmpty =>
            registry.findAgentsByAllSkills(step.requiredSkills).flatMap {
              agents =>
                if agents.nonEmpty then Future.successful(agents.headOption)
                else
                  registry
                    .findAgentsByAnySkill(step.requiredSkills)
                    .map(_.headOption)
            }

          case _ =>
            Future.successful(None)
        }
    }

  private def delegateToAgent(
      agent: ActorRef[BaseAgent.Command],
      step: PlanStep,
      context: ConversationContext,
      priorResults: Map[String, String],
      registry: AgentRegistry,
      uiBus: Option[ActorRef[UiEventBus.Command]],
      capability: AgentCapability
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Future[String] =

    val enrichedPrompt = buildStepPrompt(step, priorResults, context)
    val message = Message(
      role = MessageRole.User,
      content =
        MessageContent(enrichedPrompt, metadata = Map("stepId" -> step.id)),
      conversationId = context.id
    )

    // Publish chat and dispatch info
    uiBus.foreach { bus =>
      bus ! UiEventBus.Publish(
        UiEventBus.ChatMessage(
          context.id,
          message.role.toString,
          message.id,
          message.content.text,
          Some(capability.name)
        )
      )
      bus ! UiEventBus.Publish(
        UiEventBus.StepDispatched(
          context.id,
          step.id,
          step.targetCapability.getOrElse("unknown"),
          message.id
        )
      )
    }

    val maxClarificationRounds = capability.config
      .get("maxClarificationRounds")
      .flatMap(v => Try(v.toInt).toOption)
      .getOrElse(5)

    dialogueWithAgent(
      agent,
      message,
      context,
      registry,
      uiBus,
      capability,
      step,
      depth = 0,
      maxDepth = maxClarificationRounds,
      publishOutbound = false
    ).map { case (finalMessage, _) =>
      finalMessage.content.text
    }

  private def dialogueWithAgent(
      agent: ActorRef[BaseAgent.Command],
      outbound: Message,
      context: ConversationContext,
      registry: AgentRegistry,
      uiBus: Option[ActorRef[UiEventBus.Command]],
      capability: AgentCapability,
      step: PlanStep,
      depth: Int,
      maxDepth: Int,
      publishOutbound: Boolean
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Future[(Message, ConversationContext)] =

    if publishOutbound then
      uiBus.foreach { bus =>
        bus ! UiEventBus.Publish(
          UiEventBus.ChatMessage(
            context.id,
            outbound.role.toString,
            outbound.id,
            outbound.content.text,
            outbound.agentId.orElse(Some(capability.name))
          )
        )
      }

    import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
    import org.apache.pekko.actor.typed.ActorSystem
    import org.apache.pekko.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
    import org.apache.pekko.util.Timeout
    given Timeout = 360.seconds
    given ActorSystem[?] = ctx.system

    agent
      .ask[Command](replyTo =>
        ProcessMessage(outbound, context, replyTo.unsafeUpcast[Any])
      )
      .flatMap {
        case ProcessedMessage(response, _) =>
          uiBus.foreach { bus =>
            bus ! UiEventBus.Publish(
              UiEventBus.ChatMessage(
                context.id,
                response.role.toString,
                response.id,
                response.content.text,
                response.agentId.orElse(Some(agent.path.name))
              )
            )
          }
          val updatedContext =
            context.addMessage(outbound).addMessage(response)
          Future.successful(response -> updatedContext)

        case req: ClarificationRequest =>
          if depth >= maxDepth then
            Future.failed(
              new RuntimeException(
                s"Clarification limit reached for step ${step.id}"
              )
            )
          else
            resolveClarification(
              req,
              agent,
              registry,
              uiBus,
              capability,
              step,
              depth,
              maxDepth
            ).flatMap { case (followUpMessage, followUpContext) =>
              dialogueWithAgent(
                agent,
                followUpMessage,
                followUpContext,
                registry,
                uiBus,
                capability,
                step,
                depth + 1,
                maxDepth,
                publishOutbound = true
              )
            }

        case ProcessingFailed(error, _) =>
          Future.failed(new RuntimeException(s"Agent failed: $error"))

        case other =>
          Future.failed(new RuntimeException(s"Unexpected response: $other"))
      }

  private def resolveClarification(
      request: ClarificationRequest,
      requestingAgent: ActorRef[BaseAgent.Command],
      registry: AgentRegistry,
      uiBus: Option[ActorRef[UiEventBus.Command]],
      capability: AgentCapability,
      step: PlanStep,
      depth: Int,
      maxDepth: Int
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Future[(Message, ConversationContext)] =

    val (capTargets, skillTargets) = extractClarificationTargets(request)
    findClarifier(capTargets, skillTargets, registry, requestingAgent).flatMap {
      case Some(clarifier) =>
        val clarificationMessage = request.question.copy(
          role = MessageRole.Agent,
          conversationId = request.context.id,
          agentId = request.question.agentId.orElse(Some(capability.name))
        )

        dialogueWithAgent(
          clarifier,
          clarificationMessage,
          request.context,
          registry,
          uiBus,
          capability,
          step,
          depth + 1,
          maxDepth,
          publishOutbound = true
        ).map { case (clarifierAnswer, updatedContext) =>
          val followUp =
            buildClarificationFollowUp(request, clarifierAnswer, clarifier)
          followUp -> updatedContext
        }

      case None =>
        Future.failed(
          new RuntimeException(
            s"No agent available to clarify request ${request.question.id} for step ${step.id}"
          )
        )
    }

  private def extractClarificationTargets(
      request: ClarificationRequest
  ): (Seq[String], Set[String]) =
    val metaCaps =
      request.metadata
        .get("clarificationCapability")
        .toSeq
        .flatMap(_.split(",").map(_.trim).filter(_.nonEmpty)) ++
        request.question.content.metadata
          .get("clarificationCapability")
          .toSeq
          .flatMap(_.split(",").map(_.trim).filter(_.nonEmpty))

    val explicitCaps =
      (request.targetCapabilities ++ metaCaps).map(_.trim.toLowerCase).toSeq.distinct

    val metaSkills =
      request.metadata
        .get("clarificationSkill")
        .toSeq
        .flatMap(_.split(",").map(_.trim).filter(_.nonEmpty)) ++
        request.question.content.metadata
          .get("clarificationSkill")
          .toSeq
          .flatMap(_.split(",").map(_.trim).filter(_.nonEmpty))

    val explicitSkills =
      (request.targetSkills ++ metaSkills).map(_.trim.toLowerCase).filter(_.nonEmpty)

    (explicitCaps, explicitSkills)

  private def findClarifier(
      capabilities: Seq[String],
      skills: Set[String],
      registry: AgentRegistry,
      requestingAgent: ActorRef[BaseAgent.Command]
  )(using ec: ExecutionContext): Future[Option[ActorRef[BaseAgent.Command]]] =

    def tryCapabilities(remaining: Seq[String]): Future[Option[ActorRef[BaseAgent.Command]]] =
      remaining match
        case head +: tail =>
          registry.findAgent(head).flatMap {
            case Some(ref) if ref != requestingAgent => Future.successful(Some(ref))
            case _                                   => tryCapabilities(tail)
          }
        case _ =>
          if skills.nonEmpty then
            registry
              .findAgentsByAnySkill(skills)
              .map(_.find(_ != requestingAgent))
          else Future.successful(None)

    tryCapabilities(capabilities)

  private def buildClarificationFollowUp(
      request: ClarificationRequest,
      clarifierAnswer: Message,
      clarifier: ActorRef[BaseAgent.Command]
  ): Message =

    val mergedMetadata =
      (request.metadata ++ request.question.content.metadata ++ clarifierAnswer.content.metadata)
        .filter { case (_, value) => value != null && value.nonEmpty }

    val clarificationId =
      mergedMetadata
        .get("clarificationId")
        .getOrElse(UUID.randomUUID().toString)

    val topic =
      mergedMetadata.getOrElse("clarificationTopic", "")

    Message(
      role = MessageRole.Agent,
      content = MessageContent(
        clarifierAnswer.content.text,
        metadata = mergedMetadata ++ Map(
          "clarificationResolved" -> "true",
          "clarificationId" -> clarificationId,
          "clarificationTopic" -> topic,
          "clarificationSource" -> clarifierAnswer.agentId
            .getOrElse(clarifier.path.name),
          "stepId" -> request.originalStepId
        )
      ),
      conversationId = request.context.id,
      agentId = clarifierAnswer.agentId.orElse(Some(clarifier.path.name))
    )

  private def executeSelfContained(
      step: PlanStep,
      context: ConversationContext,
      priorResults: Map[String, String],
      provider: LLMProvider,
      uiBus: Option[ActorRef[UiEventBus.Command]],
      capability: AgentCapability
  )(using ec: ExecutionContext): Future[String] =

    val prompt = buildStepPrompt(step, priorResults, context)
    val userMsg = Message(
      role = MessageRole.User,
      content = MessageContent(prompt, metadata = Map("stepId" -> step.id)),
      conversationId = context.id
    )
    // Publish the prompt and dispatch
    uiBus.foreach { bus =>
      bus ! UiEventBus.Publish(
        UiEventBus.ChatMessage(
          context.id,
          userMsg.role.toString,
          userMsg.id,
          userMsg.content.text,
          Some(capability.name)
        )
      )
      bus ! UiEventBus.Publish(
        UiEventBus.StepDispatched(
          context.id,
          step.id,
          capability.name,
          userMsg.id
        )
      )
    }
    provider
      .completion(
        Seq(userMsg),
        systemPrompt =
          "You are a helpful assistant executing a specific task step."
      )
      .map { txt =>
        // Publish assistant response
        val respMsg = Message(
          role = MessageRole.Assistant,
          content = MessageContent(txt),
          conversationId = context.id,
          agentId = Some(capability.name)
        )
        uiBus.foreach { bus =>
          bus ! UiEventBus.Publish(
            UiEventBus.ChatMessage(
              context.id,
              respMsg.role.toString,
              respMsg.id,
              respMsg.content.text,
              respMsg.agentId
            )
          )
        }
        txt
      }

  private def buildStepPrompt(
      step: PlanStep,
      priorResults: Map[String, String],
      context: ConversationContext
  ): String =
    val dependencies = step.dependencies.toSeq.sorted
    val ctxText =
      if dependencies.isEmpty then ""
      else {
        val depResults = dependencies
          .flatMap { depId =>
            priorResults.get(depId).map(res => s"[$depId]: $res")
          }
          .mkString("\n")
        s"\n\nContext from prior steps:\n$depResults\n"
      }

    log.info(s"[CONTEXT fordelegation Prompt]  ${context.metadata}")
    s"${step.description} [stepId: ${step.id}]$ctxText withh context: ${context.metadata}" //FIXME better format vector context
    
  private def aggregateResults(
      plan: ExecutionPlan,
      results: Map[String, String]
  ): String =
    def humanStepLabel(id: String): String =
      val m = "(?i)step-?(\\d+)".r.findFirstMatchIn(id)
      m.map(m => s"Step ${m.group(1)}").getOrElse(id)

    val sortedSteps = plan.steps.sortBy(_.id)
    val sections = sortedSteps.flatMap { step =>
      results.get(step.id).map { result =>
        s"### [${humanStepLabel(step.id)}] ${step.description}\n$result"
      }
    }

    s"""# Results for: ${plan.originalQuery}
       |
       |${sections.mkString("\n\n")}
       |
       |---
       |Plan executed with ${results.size}/${plan.steps.size} steps completed.
    """.stripMargin

  // ========== Direct Execution (Fallback) ==========

  private def executeDirectly(
      message: Message,
      context: ConversationContext,
      replyTo: ActorRef[Any],
      capability: AgentCapability,
      provider: LLMProvider,
      registry: AgentRegistry,
      conversations: Map[String, ConversationContext],
      uiBus: Option[ActorRef[UiEventBus.Command]],
      planningEnabled: Boolean,
      activePlans: Map[String, ExecutionPlan]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =

    val stepId = message.content.metadata.getOrElse("stepId", "direct")
    ctx.log.info(s"[${capability.name}] Direct execution stepId=$stepId")
    uiBus.foreach(
      _ ! UiEventBus.Publish(
        UiEventBus.AgentStart(
          context.id,
          capability.name,
          stepId,
          message.id,
          false
        )
      )
    )
    uiBus.foreach { bus =>
      val sysText = capability.config.getOrElse(
        "systemPrompt",
        "You are a helpful assistant"
      )
      val sysMsg = Message(
        role = MessageRole.System,
        content = MessageContent(sysText),
        conversationId = context.id,
        agentId = Some(capability.name)
      )
      bus ! UiEventBus.Publish(
        UiEventBus.ChatMessage(
          context.id,
          sysMsg.role.toString,
          sysMsg.id,
          sysMsg.content.text,
          sysMsg.agentId
        )
      )
    }

    ctx.pipeToSelf(
      provider.completion(
        context.messages.toSeq :+ message,
        capability.config
          .getOrElse("systemPrompt", "You are a helpful assistant")
      )
    ) {
      case Success(response) =>
        val responseMsg = Message(
          role = MessageRole.Assistant,
          content = MessageContent(response),
          conversationId = context.id,
          agentId = Some(ctx.self.path.name)
        )
        uiBus.foreach(
          _ ! UiEventBus.Publish(
            UiEventBus.ChatMessage(
              context.id,
              responseMsg.role.toString,
              responseMsg.id,
              responseMsg.content.text,
              responseMsg.agentId
            )
          )
        )
        uiBus.foreach(
          _ ! UiEventBus.Publish(
            UiEventBus.AgentComplete(
              context.id,
              capability.name,
              stepId,
              responseMsg.id,
              response.length
            )
          )
        )
        replyTo ! ProcessedMessage(
          responseMsg,
          context.addMessage(message).addMessage(responseMsg)
        )
        NoOp

      case Failure(ex) =>
        ctx.log.error(s"[${capability.name}] Direct execution failed", ex)
        uiBus.foreach(
          _ ! UiEventBus.Publish(
            UiEventBus.ErrorEvent(context.id, ex.getMessage)
          )
        )
        replyTo ! ProcessingFailed(ex.getMessage, message.id)
        NoOp
    }

    idle(
      capability,
      provider,
      registry,
      conversations + (context.id -> context),
      uiBus,
      planningEnabled,
      activePlans
    )

  private def streamDirectly(
      message: Message,
      context: ConversationContext,
      replyTo: ActorRef[Any],
      capability: AgentCapability,
      provider: LLMProvider,
      registry: AgentRegistry,
      conversations: Map[String, ConversationContext],
      uiBus: Option[ActorRef[UiEventBus.Command]],
      planningEnabled: Boolean,
      activePlans: Map[String, ExecutionPlan]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =

    given Materializer = Materializer(ctx.system)

    val stepId = message.content.metadata.getOrElse("stepId", "stream")
    ctx.log.info(s"[${capability.name}] Streaming stepId=$stepId")
    uiBus.foreach(
      _ ! UiEventBus.Publish(
        UiEventBus.AgentStart(
          context.id,
          capability.name,
          stepId,
          message.id,
          false
        )
      )
    )
    uiBus.foreach(
      _ ! UiEventBus.Publish(
        UiEventBus.ChatMessage(
          context.id,
          message.role.toString,
          message.id,
          message.content.text,
          message.agentId
        )
      )
    )
    uiBus.foreach { bus =>
      val sysText = capability.config.getOrElse("systemPrompt", "")
      if sysText.nonEmpty then
        val sysMsg = Message(
          role = MessageRole.System,
          content = MessageContent(sysText),
          conversationId = context.id,
          agentId = Some(capability.name)
        )
        bus ! UiEventBus.Publish(
          UiEventBus.ChatMessage(
            context.id,
            sysMsg.role.toString,
            sysMsg.id,
            sysMsg.content.text,
            sysMsg.agentId
          )
        )
    }

    val stream = provider.streamCompletion(
      context.messages.toSeq :+ message,
      capability.config.getOrElse("systemPrompt", "")
    )

    stream.runForeach { token =>
      replyTo ! StreamChunk(token.content, token.messageId)
      if token.content.nonEmpty then
        uiBus.foreach(
          _ ! UiEventBus.Publish(
            UiEventBus.ChatMessage(
              context.id,
              MessageRole.Assistant.toString,
              token.messageId,
              token.content,
              Some(ctx.self.path.name)
            )
          )
        )
    }

    ctx.pipeToSelf(stream.runWith(Sink.fold("")(_ + _.content))) {
      case Success(fullResponse) =>
        val responseMsg = Message(
          role = MessageRole.Assistant,
          content = MessageContent(fullResponse),
          conversationId = context.id,
          agentId = Some(ctx.self.path.name)
        )
        uiBus.foreach(
          _ ! UiEventBus.Publish(
            UiEventBus.ChatMessage(
              context.id,
              responseMsg.role.toString,
              responseMsg.id,
              responseMsg.content.text,
              responseMsg.agentId
            )
          )
        )
        uiBus.foreach(
          _ ! UiEventBus.Publish(
            UiEventBus.AgentComplete(
              context.id,
              capability.name,
              stepId,
              responseMsg.id,
              fullResponse.length
            )
          )
        )
        replyTo ! StreamComplete(responseMsg)
        NoOp

      case Failure(ex) =>
        ctx.log.error(s"[${capability.name}] Streaming failed", ex)
        uiBus.foreach(
          _ ! UiEventBus.Publish(
            UiEventBus.ErrorEvent(context.id, ex.getMessage)
          )
        )
        replyTo ! StreamError(ex.getMessage)
        NoOp
    }

    idle(
      capability,
      provider,
      registry,
      conversations + (context.id -> context),
      uiBus,
      planningEnabled,
      activePlans
    )

  // ========== Processing State ==========

  private def processing(
      capability: AgentCapability,
      provider: LLMProvider,
      registry: AgentRegistry,
      conversations: Map[String, ConversationContext],
      pendingReply: ActorRef[?],
      uiBus: Option[ActorRef[UiEventBus.Command]],
      planningEnabled: Boolean,
      activePlans: Map[String, ExecutionPlan]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =
    Behaviors.receiveMessage[Command] {
      case Stop =>
        registry.deregister(ctx.self, capability)
        Behaviors.stopped

      case NoOp =>
        idle(
          capability,
          provider,
          registry,
          conversations,
          uiBus,
          planningEnabled,
          activePlans
        )

      case _ =>
        ctx.log.warn(s"[${capability.name}] Ignoring message while processing")
        Behaviors.same
    }
