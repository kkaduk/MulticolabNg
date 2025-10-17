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
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure, Try}
import scala.concurrent.duration.*
import java.util.UUID

object LLMAgent:

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
      val skills = capability.skills ++ Set("planning", "reasoning", "orchestration")
      registry.register(ctx.self, capability.copy(skills = skills), skills)
      
      ctx.log.info(s"[${capability.name}] Initialized with planning=${planningEnabled}, skills=${skills.mkString(",")}")
      
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
        withLogging(ctx, context.id) {
          ctx.log.info(s"[${capability.name}] Received message ${message.id}")
          
          if planningEnabled && shouldPlan(message, context) then
            ctx.log.info(s"[${capability.name}] Initiating planning phase")
            ctx.self ! PlanTask(message, context, replyTo)
            Behaviors.same
          else
            // Direct execution (existing logic)
            executeDirectly(message, context, replyTo, capability, provider, conversations, uiBus)
        }

      case PlanTask(message, context, replyTo) =>
        withLogging(ctx, context.id) {
          ctx.log.info(s"[${capability.name}] Planning task for message ${message.id}")
          
          ctx.pipeToSelf(createPlan(message, context, provider, registry)) {
            case Success(planResp) =>
              ctx.log.info(s"[${capability.name}] Plan created: ${planResp.plan.steps.size} steps, confidence=${planResp.confidence}")
              
              // Emit plan to UI
              uiBus.foreach { bus =>
                val stepInfos = planResp.plan.steps.map { s =>
                  UiEventBus.StepInfo(s.id, s.targetCapability.getOrElse("unknown"), s.dependencies.toSeq)
                }
                bus ! UiEventBus.Publish(UiEventBus.PlanComputed(context.id, stepInfos))
              }
              
              ExecutePlan(planResp.plan, context, replyTo)
              
            case Failure(ex) =>
              ctx.log.error(s"[${capability.name}] Planning failed: ${ex.getMessage}", ex)
              uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.ErrorEvent(context.id, s"Planning failed: ${ex.getMessage}")))
              // Fall back to direct execution
              ProcessMessage(message, context, replyTo)
          }
          
          Behaviors.same
        }

      case ExecutePlan(plan, context, replyTo) =>
        withLogging(ctx, context.id) {
          ctx.log.info(s"[${capability.name}] Executing plan ${plan.id} with ${plan.steps.size} steps")
          
          plan.strategy match {
            case ExecutionStrategy.Sequential =>
              executeSequential(plan, context, replyTo, capability, provider, registry, conversations, uiBus, activePlans)
            case ExecutionStrategy.Parallel =>
              executeParallel(plan, context, replyTo, capability, provider, registry, conversations, uiBus, activePlans)
            case ExecutionStrategy.Adaptive =>
              executeAdaptive(plan, context, replyTo, capability, provider, registry, conversations, uiBus, activePlans)
          }
        }

      case StepCompleted(stepId, result, agentId) =>
        ctx.log.info(s"[${capability.name}] Step $stepId completed by $agentId")
        uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.StepCompleted("unknown", stepId)))
        Behaviors.same

      case StepFailed(stepId, error) =>
        ctx.log.warn(s"[${capability.name}] Step $stepId failed: $error")
        uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.ErrorEvent("unknown", s"Step $stepId failed: $error")))
        Behaviors.same

      case StreamMessage(message, context, replyTo) =>
        // Existing streaming logic (unchanged)
        streamDirectly(message, context, replyTo, capability, provider, conversations, uiBus)

      case GetStatus =>
        ctx.log.debug(s"[${capability.name}] Status: ${activePlans.size} active plans")
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

  private def shouldPlan(message: Message, context: ConversationContext): Boolean =
    val query = message.content.text.toLowerCase
    val planningKeywords = Set(
      "analyze", "research", "compare", "investigate", "break down",
      "summarize multiple", "extract from", "combine", "aggregate"
    )
    planningKeywords.exists(query.contains) || query.split("\\s+").length > 20

  private def createPlan(
    message: Message,
    context: ConversationContext,
    provider: LLMProvider,
    registry: AgentRegistry
  )(using ec: ExecutionContext): Future[PlanningResponse] =
    for {
      // Discover available agents
      agentSkillsMap <- discoverAgentCapabilities(registry)
      
      // Generate plan via LLM
      planPrompt = buildPlanningPrompt(message, context, agentSkillsMap)
      planJson <- provider.completion(
        Seq(Message(
          role = MessageRole.System,
          content = MessageContent("You are a task planning expert. Respond ONLY with valid JSON."),
          conversationId = context.id
        ), Message(
          role = MessageRole.User,
          content = MessageContent(planPrompt),
          conversationId = context.id
        )),
        systemPrompt = ""
      )
      
      // Parse plan
      plan <- Future.fromTry(parsePlan(planJson, message, context))
      
    } yield PlanningResponse(plan, planJson, 0.85)

  private def discoverAgentCapabilities(registry: AgentRegistry)(using ec: ExecutionContext): Future[Map[String, Set[String]]] =
    // Query all known capabilities (NER, summarization, etc.)
    val knownCapabilities = Seq("ner", "summarization", "translation", "sentiment", "qa")
    
    Future.traverse(knownCapabilities) { cap =>
      registry.findAgent(cap).map { optRef =>
        cap -> Set(cap) // Simplified: capability name as skill
      }
    }.map(_.toMap.filter(_._2.nonEmpty))

  private def buildPlanningPrompt(
    message: Message,
    context: ConversationContext,
    availableAgents: Map[String, Set[String]]
  ): String =
    val agentsList = availableAgents.map { case (cap, skills) =>
      s"- $cap: ${skills.mkString(", ")}"
    }.mkString("\n")
    
    s"""Decompose this task into executable steps. Available specialized agents:
$agentsList

User Query: ${message.content.text}

Respond with JSON:
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
}"""

  private def parsePlan(json: String, message: Message, context: ConversationContext): Try[ExecutionPlan] =
    Try {
      // Simplified parser (production: use proper JSON library)
      val stepsPattern = """"steps"\s*:\s*\[(.*?)\]""".r
      val stepPattern = """\{[^}]+\}""".r
      
      val stepsJson = stepsPattern.findFirstMatchIn(json).map(_.group(1)).getOrElse("")
      val stepMatches = stepPattern.findAllIn(stepsJson).toSeq
      
      val steps = stepMatches.zipWithIndex.map { case (stepJson, idx) =>
        val id = extractJsonField(stepJson, "id").getOrElse(s"step-$idx")
        val desc = extractJsonField(stepJson, "description").getOrElse("Unknown step")
        val cap = extractJsonField(stepJson, "targetCapability")
        val skills = extractJsonArray(stepJson, "requiredSkills")
        val deps = extractJsonArray(stepJson, "dependencies")
        
        PlanStep(
          id = id,
          description = desc,
          requiredSkills = skills.toSet,
          dependencies = deps.toSet,
          targetCapability = cap
        )
      }
      
      val strategy = extractJsonField(json, "strategy") match {
        case Some("sequential") => ExecutionStrategy.Sequential
        case Some("adaptive") => ExecutionStrategy.Adaptive
        case _ => ExecutionStrategy.Parallel
      }
      
      ExecutionPlan(
        conversationId = context.id,
        originalQuery = message.content.text,
        steps = steps,
        strategy = strategy
      )
    }
  private def extractJsonField(json: String, field: String): Option[String] =
    raw""""$field"\s*:\s*"([^"]+)"""".r.findFirstMatchIn(json).map(_.group(1))

  private def extractJsonArray(json: String, field: String): Seq[String] =
    raw""""$field"\s*:\s*\[(.*?)\]""".r.findFirstMatchIn(json).map { m =>
      """"([^"]+)"""".r.findAllMatchIn(m.group(1)).map(_.group(1)).toSeq
    }.getOrElse(Seq.empty)

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
    activePlans: Map[String, ExecutionPlan]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =
    
    def executeNext(remainingSteps: Seq[PlanStep], results: Map[String, String], updatedContext: ConversationContext): Unit =
      remainingSteps.headOption match {
        case Some(step) =>
          ctx.log.info(s"[${capability.name}] Executing step ${step.id} sequentially")
          
          ctx.pipeToSelf(executeStep(step, updatedContext, results, registry, provider, uiBus)) {
            case Success((stepResult, newContext)) =>
              StepCompleted(step.id, stepResult, "self")
            case Failure(ex) =>
              StepFailed(step.id, ex.getMessage)
          }
          
          // Wait for completion before continuing (handled via message processing)
          
        case None =>
          // All steps complete - aggregate results
          val finalResult = aggregateResults(plan, results)
          val responseMsg = Message(
            role = MessageRole.Assistant,
            content = MessageContent(finalResult),
            conversationId = context.id,
            agentId = Some(ctx.self.path.name)
          )
          
          ctx.log.info(s"[${capability.name}] Plan ${plan.id} completed")
          uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.AggregateCompleted(context.id, finalResult.length)))
          
          replyTo ! ProcessedMessage(responseMsg, updatedContext.addMessage(responseMsg))
      }
    
    executeNext(plan.steps, Map.empty, context)
    
    // Return behavior that handles step completion
    processing(capability, provider, registry, conversations + (context.id -> context), replyTo, uiBus)

  private def executeParallel(
    plan: ExecutionPlan,
    context: ConversationContext,
    replyTo: ActorRef[Any],
    capability: AgentCapability,
    provider: LLMProvider,
    registry: AgentRegistry,
    conversations: Map[String, ConversationContext],
    uiBus: Option[ActorRef[UiEventBus.Command]],
    activePlans: Map[String, ExecutionPlan]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =
    
    // Execute independent steps in parallel
    val independentSteps = plan.steps.filter(_.dependencies.isEmpty)
    
    ctx.log.info(s"[${capability.name}] Executing ${independentSteps.size} steps in parallel")
    
    val resultsFuture = Future.traverse(independentSteps.take(plan.maxParallelism)) { step =>
      executeStep(step, context, Map.empty, registry, provider, uiBus).map { case (result, _) =>
        step.id -> result
      }
    }
    
    ctx.pipeToSelf(resultsFuture) {
      case Success(results) =>
        val resultsMap = results.toMap
        val finalResult = aggregateResults(plan, resultsMap)
        val responseMsg = Message(
          role = MessageRole.Assistant,
          content = MessageContent(finalResult),
          conversationId = context.id,
          agentId = Some(ctx.self.path.name)
        )
        
        ctx.log.info(s"[${capability.name}] Parallel execution completed")
        uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.AggregateCompleted(context.id, finalResult.length)))
        
        replyTo ! ProcessedMessage(responseMsg, context.addMessage(responseMsg))
        NoOp
        
      case Failure(ex) =>
        ctx.log.error(s"[${capability.name}] Parallel execution failed", ex)
        replyTo ! ProcessingFailed(ex.getMessage, context.id)
        NoOp
    }
    
    processing(capability, provider, registry, conversations + (context.id -> context), replyTo, uiBus)

  private def executeAdaptive(
    plan: ExecutionPlan,
    context: ConversationContext,
    replyTo: ActorRef[Any],
    capability: AgentCapability,
    provider: LLMProvider,
    registry: AgentRegistry,
    conversations: Map[String, ConversationContext],
    uiBus: Option[ActorRef[UiEventBus.Command]],
    activePlans: Map[String, ExecutionPlan]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =
    // Adaptive: start with parallel, fall back to sequential on contention
    ctx.log.info(s"[${capability.name}] Using adaptive strategy (defaulting to parallel)")
    executeParallel(plan, context, replyTo, capability, provider, registry, conversations, uiBus, activePlans)

  // ========== Step Execution ==========

  private def executeStep(
    step: PlanStep,
    context: ConversationContext,
    priorResults: Map[String, String],
    registry: AgentRegistry,
    provider: LLMProvider,
    uiBus: Option[ActorRef[UiEventBus.Command]]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Future[(String, ConversationContext)] =
    
    ctx.log.info(s"[executeStep] Step ${step.id}: ${step.description}")
    
    for {
      // 1. Find suitable agent
      agentOpt <- findBestAgent(step, registry)
      
      // 2. Delegate or execute locally
      result <- agentOpt match {
        case Some(agent) =>
          ctx.log.info(s"[executeStep] Delegating step ${step.id} to agent ${agent.path.name}")
          uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.StepDispatched(
            context.id, step.id, step.targetCapability.getOrElse("unknown"), step.id
          )))
          delegateToAgent(agent, step, context, priorResults)
          
        case None =>
          ctx.log.info(s"[executeStep] No suitable agent, executing step ${step.id} locally")
          executeSelfContained(step, context, priorResults, provider)
      }
      
    } yield (result, context)

  private def findBestAgent(
    step: PlanStep,
    registry: AgentRegistry
  )(using ec: ExecutionContext): Future[Option[ActorRef[BaseAgent.Command]]] =
    
    // Priority: target capability > all skills > any skill
    step.targetCapability match {
      case Some(cap) =>
        registry.findAgent(cap)
        
      case None if step.requiredSkills.nonEmpty =>
        registry.findAgentsByAllSkills(step.requiredSkills).flatMap { agents =>
          if agents.nonEmpty then Future.successful(agents.headOption)
          else registry.findAgentsByAnySkill(step.requiredSkills).map(_.headOption)
        }
        
      case _ =>
        Future.successful(None)
    }

  private def delegateToAgent(
    agent: ActorRef[BaseAgent.Command],
    step: PlanStep,
    context: ConversationContext,
    priorResults: Map[String, String]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Future[String] =
    
    import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
    import org.apache.pekko.actor.typed.ActorSystem
    import org.apache.pekko.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
    import org.apache.pekko.util.Timeout
    given Timeout = 30.seconds
    given ActorSystem[?] = ctx.system
    
    val enrichedPrompt = buildStepPrompt(step, priorResults)
    val message = Message(
      role = MessageRole.User,
      content = MessageContent(enrichedPrompt, metadata = Map("stepId" -> step.id)),
      conversationId = context.id
    )
    
    agent.ask[Command](replyTo => ProcessMessage(message, context, replyTo.unsafeUpcast[Any])).flatMap {
      case ProcessedMessage(response, _) =>
        Future.successful(response.content.text)
      case ProcessingFailed(error, _) =>
        Future.failed(new RuntimeException(s"Agent failed: $error"))
      case other =>
        Future.failed(new RuntimeException(s"Unexpected response: $other"))
    }

  private def executeSelfContained(
    step: PlanStep,
    context: ConversationContext,
    priorResults: Map[String, String],
    provider: LLMProvider
  )(using ec: ExecutionContext): Future[String] =
    
    val prompt = buildStepPrompt(step, priorResults)
    provider.completion(
      Seq(Message(
        role = MessageRole.User,
        content = MessageContent(prompt),
        conversationId = context.id
      )),
      systemPrompt = "You are a helpful assistant executing a specific task step."
    )

  private def buildStepPrompt(step: PlanStep, priorResults: Map[String, String]): String =
    val dependencies = step.dependencies.toSeq.sorted
    val context = if dependencies.isEmpty then ""
    else {
      val depResults = dependencies.flatMap { depId =>
        priorResults.get(depId).map(res => s"[$depId]: $res")
      }.mkString("\n")
      s"\n\nContext from prior steps:\n$depResults\n"
    }
    
    s"${step.description}$context"

  private def aggregateResults(plan: ExecutionPlan, results: Map[String, String]): String =
    val sortedSteps = plan.steps.sortBy(_.id)
    val sections = sortedSteps.flatMap { step =>
      results.get(step.id).map { result =>
        s"### ${step.description}\n$result"
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
    conversations: Map[String, ConversationContext],
    uiBus: Option[ActorRef[UiEventBus.Command]]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =
    
    val stepId = message.content.metadata.getOrElse("stepId", "direct")
    ctx.log.info(s"[${capability.name}] Direct execution stepId=$stepId")
    uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.AgentStart(context.id, capability.name, stepId, message.id, false)))
    
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
        uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.AgentComplete(context.id, capability.name, stepId, responseMsg.id, response.length)))
        replyTo ! ProcessedMessage(responseMsg, context.addMessage(message).addMessage(responseMsg))
        NoOp
        
      case Failure(ex) =>
        ctx.log.error(s"[${capability.name}] Direct execution failed", ex)
        uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.ErrorEvent(context.id, ex.getMessage)))
        replyTo ! ProcessingFailed(ex.getMessage, message.id)
        NoOp
    }
    
    processing(capability, provider, registry = null, conversations + (context.id -> context), replyTo, uiBus)

  private def streamDirectly(
    message: Message,
    context: ConversationContext,
    replyTo: ActorRef[Any],
    capability: AgentCapability,
    provider: LLMProvider,
    conversations: Map[String, ConversationContext],
    uiBus: Option[ActorRef[UiEventBus.Command]]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =
    
    given Materializer = Materializer(ctx.system)
    
    val stepId = message.content.metadata.getOrElse("stepId", "stream")
    ctx.log.info(s"[${capability.name}] Streaming stepId=$stepId")
    uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.AgentStart(context.id, capability.name, stepId, message.id, false)))
    
    val stream = provider.streamCompletion(
      context.messages.toSeq :+ message,
      capability.config.getOrElse("systemPrompt", "")
    )
    
    stream.runForeach { token =>
      replyTo ! StreamChunk(token.content, token.messageId)
    }
    
    ctx.pipeToSelf(stream.runWith(Sink.fold("")(_ + _.content))) {
      case Success(fullResponse) =>
        val responseMsg = Message(
          role = MessageRole.Assistant,
          content = MessageContent(fullResponse),
          conversationId = context.id,
          agentId = Some(ctx.self.path.name)
        )
        uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.AgentComplete(context.id, capability.name, stepId, responseMsg.id, fullResponse.length)))
        replyTo ! StreamComplete(responseMsg)
        NoOp
        
      case Failure(ex) =>
        ctx.log.error(s"[${capability.name}] Streaming failed", ex)
        uiBus.foreach(_ ! UiEventBus.Publish(UiEventBus.ErrorEvent(context.id, ex.getMessage)))
        replyTo ! StreamError(ex.getMessage)
        NoOp
    }
    
    processing(capability, provider, registry = null, conversations + (context.id -> context), replyTo, uiBus)

  // ========== Processing State ==========

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
        if registry != null then registry.deregister(ctx.self, capability)
        Behaviors.stopped

      case NoOp =>
        idle(capability, provider, registry, conversations, uiBus, planningEnabled = true, Map.empty)

      case _ =>
        ctx.log.warn(s"[${capability.name}] Ignoring message while processing")
        Behaviors.same
    }

  // ========== Utility ==========

  private def withLogging[T](ctx: ActorContext[?], conversationId: String)(block: => T): T =
    try block
    catch {
      case ex: Exception =>
        ctx.log.error(s"Error in conversation $conversationId", ex)
        throw ex
    }
