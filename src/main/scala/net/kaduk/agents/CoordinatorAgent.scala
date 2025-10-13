package net.kaduk.agents

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import net.kaduk.domain.*
import net.kaduk.infrastructure.registry.AgentRegistry
import net.kaduk.agents.BaseAgent.*
import scala.concurrent.{ExecutionContext}
import scala.util.{Success, Failure, Try}
import scala.concurrent.Await
import scala.concurrent.duration.*

/** CoordinatorAgent
  *
  *   - Decomposes an incoming task into an acyclic graph (DAG) of steps.
  *   - Dispatches ready steps to specialized agents once their dependencies are
  *     satisfied.
  *   - Collects all responses, aggregates them, and returns a single final
  *     response.
  *   - Optionally loops the conversation up to k times if the final result is
  *     not satisfactory. The loop count can be provided via
  *     ConversationContext.metadata("maxLoops"), defaults to 1. Satisfaction is
  *     heuristically determined using either:
  *     - response.content.metadata("satisfied") == "true", or
  *     - response.content.text contains "[done]" (case-insensitive).
  *
  * Protocol note: This actor keeps its protocol strictly as BaseAgent.Command
  * to avoid extending the sealed trait from another file. Correlation of step
  * completions is achieved by:
  *   - Attaching "stepId" into the metadata of the user message sent to a
  *     specialized agent
  *   - Tracking user message id -> stepId mapping in TaskState.msgIdToStep
  *   - On ProcessedMessage, extracting the last user message id from the
  *     updatedContext and resolving stepId
  *   - On ProcessingFailed, using the provided messageId to resolve stepId via
  *     msgIdToStep
  */
object CoordinatorAgent:

  // Public types to represent a DAG plan
  case class TaskPlan(steps: Seq[TaskStep]):
    // Index by id for quick lookup
    lazy val byId: Map[String, TaskStep] = steps.map(s => s.id -> s).toMap

  case class TaskStep(
      id: String, // Unique step ID inside the plan
      agentCapability: String, // Name to resolve the agent via AgentRegistry
      instruction: String, // Instruction for this step
      dependencies: Seq[String] =
        Seq.empty // Other step IDs that must complete first
  )

  // Internal state for a single conversation execution
  private case class TaskState(
      plan: TaskPlan,
      replyTo: ActorRef[Response],
      context: ConversationContext,
      results: Map[String, Message] =
        Map.empty, // stepId -> agent response message
      completed: Set[String] = Set.empty,
      inProgress: Set[String] = Set.empty,
      attempts: Int = 0,
      maxAttempts: Int = 1,
      msgIdToStep: Map[String, String] = Map.empty // user message id -> stepId
  )

  def apply(
      registry: AgentRegistry
  )(using ec: ExecutionContext): Behavior[Command] =
    Behaviors.setup: ctx =>
      idle(registry, Map.empty)(using ctx, ec)

  private def idle(
      registry: AgentRegistry,
      activeTasks: Map[String, TaskState]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =
    Behaviors.receiveMessage:
      case ProcessMessage(message, context, replyTo) =>
        withLogging(ctx, context.id):
          ctx.log.info(s"Coordinating task for conversation ${context.id}")

          val plan = decomposeTask(message.content.text, registry)
          val maxLoops = context.metadata
            .get("maxLoops")
            .flatMap(s => Try(s.toInt).toOption)
            .filter(_ >= 1)
            .getOrElse(1)

          val state0 = TaskState(
            plan = plan,
            replyTo = replyTo.asInstanceOf[ActorRef[Response]],
            context = context,
            attempts = 0,
            maxAttempts = maxLoops
          )

          val state1 = dispatchReadySteps(context.id, state0, registry)

          coordinating(registry, activeTasks + (context.id -> state1))

      case Stop =>
        Behaviors.stopped

      case other =>
        ctx.log.debug(s"Ignoring message in idle: $other")
        Behaviors.same

  private def coordinating(
      registry: AgentRegistry,
      activeTasks: Map[String, TaskState]
  )(using ctx: ActorContext[Command], ec: ExecutionContext): Behavior[Command] =
    Behaviors.receiveMessage:
      case pm: ProcessedMessage =>
        val convId = pm.updatedContext.id
        withLogging(ctx, convId):
          val stateOpt: Option[TaskState] = activeTasks.get(convId)
          stateOpt match
            case None =>
              idle(registry, activeTasks) // No state, ignore and go idle
            case Some(st) =>
              // The last user message id added just before the agent's assistant response
              val lastUserIdOpt =
                pm.updatedContext.messages.reverse
                  .find(_.role == MessageRole.User)
                  .map(_.id)

              val stepIdOpt = lastUserIdOpt.flatMap(st.msgIdToStep.get)

              stepIdOpt match
                case None =>
                  ctx.log.warn(
                    s"Could not correlate ProcessedMessage to a step for conversation $convId"
                  )
                  // As a fallback, reply aggregated result
                  val aggregated =
                    aggregateResults(st.copy(context = pm.updatedContext))
                  st.replyTo ! ProcessedMessage(aggregated, pm.updatedContext)
                  idle(registry, activeTasks - convId)

                case Some(stepId) =>
                  ctx.log.info(
                    s"Step '$stepId' completed for conversation $convId"
                  )

                  val cleanedMap = lastUserIdOpt match
                    case Some(uid) => st.msgIdToStep - uid
                    case None      => st.msgIdToStep

                  val updatedState = st.copy(
                    context = pm.updatedContext,
                    results = st.results + (stepId -> pm.message),
                    completed = st.completed + stepId,
                    inProgress = st.inProgress - stepId,
                    msgIdToStep = cleanedMap
                  )

                  // If all steps done and nothing in progress, evaluate satisfaction
                  val allSteps = updatedState.plan.steps.map(_.id).toSet
                  val doneNow =
                    updatedState.completed == allSteps && updatedState.inProgress.isEmpty

                  if doneNow then
                    if isSatisfactory(
                        updatedState
                      ) || updatedState.attempts >= (updatedState.maxAttempts - 1)
                    then
                      // Aggregate and reply
                      val aggregated = aggregateResults(updatedState)
                      updatedState.replyTo ! ProcessedMessage(
                        aggregated,
                        updatedState.context
                      )
                      idle(registry, activeTasks - convId)
                    else
                      // Not satisfactory, attempt refinement: re-run last step with refinement hint
                      updatedState.plan.steps.lastOption match
                        case Some(lastStep) =>
                          val refinedState = updatedState.copy(
                            attempts = updatedState.attempts + 1,
                            completed = updatedState.completed - lastStep.id,
                            results = updatedState.results - lastStep.id,
                            // Remove any stale mappings for this step id
                            msgIdToStep = updatedState.msgIdToStep.filterNot {
                              case (_, sid) => sid == lastStep.id
                            }
                          )
                          val afterDispatch = dispatchSpecificStep(
                            convId,
                            refinedState,
                            lastStep,
                            registry,
                            refinement = true
                          )
                          coordinating(
                            registry,
                            activeTasks + (convId -> afterDispatch)
                          )
                        case None =>
                          // No steps? Return empty aggregation
                          val aggregated = aggregateResults(updatedState)
                          updatedState.replyTo ! ProcessedMessage(
                            aggregated,
                            updatedState.context
                          )
                          idle(registry, activeTasks - convId)
                  else
                    // Schedule further ready steps if any
                    val nextState =
                      dispatchReadySteps(convId, updatedState, registry)
                    coordinating(registry, activeTasks + (convId -> nextState))

      case failed: ProcessingFailed =>
        // Correlate failure using messageId -> stepId map across conversations
        val ownerOpt: Option[(String, TaskState)] =
          activeTasks.collectFirst {
            case (cid, st) if st.msgIdToStep.contains(failed.messageId) =>
              (cid, st)
          }

        ownerOpt match
          case None =>
            ctx.log.warn(
              s"Uncorrelated ProcessingFailed for messageId=${failed.messageId}: ${failed.error}"
            )
            // If we cannot correlate, fail the first active task (fallback)
            activeTasks.headOption match
              case Some((cid, st)) =>
                st.replyTo ! failed
                idle(registry, activeTasks - cid)
              case None =>
                idle(registry, activeTasks)

          case Some((convId, state)) =>
            withLogging(ctx, convId):
              val stepId = state.msgIdToStep(failed.messageId)
              ctx.log.warn(
                s"Step '$stepId' failed for conversation $convId: ${failed.error}"
              )

              // On failure, if we still have attempts left, try to re-run this step with refinement
              if state.attempts < (state.maxAttempts - 1) then
                val refinedState = state.copy(
                  attempts = state.attempts + 1,
                  inProgress = state.inProgress - stepId,
                  completed = state.completed - stepId,
                  results = state.results - stepId,
                  msgIdToStep = state.msgIdToStep - failed.messageId
                )
                val step = refinedState.plan.byId(stepId)
                val afterDispatch = dispatchSpecificStep(
                  convId,
                  refinedState,
                  step,
                  registry,
                  refinement = true
                )
                coordinating(registry, activeTasks + (convId -> afterDispatch))
              else
                // Exhausted attempts - fail the whole conversation
                state.replyTo ! failed
                idle(registry, activeTasks - convId)

      case Stop =>
        Behaviors.stopped

      case other =>
        ctx.log.debug(s"Ignoring message in coordinating: $other")
        Behaviors.same

  // Planner: consult receptionist for available agents by capability name and inferred skills,
  // then construct a DAG plan. Steps of the approach:
  // 1) Ask receptionist about skills suitable for task (heuristic inference + capability availability check)
  // 2) Receive agent references (validated by registry.findAgent)
  // 3) Plan task decomposition (build DAG)
  // 4) Distribution and consolidation are handled by Coordinator via dispatchReadySteps/aggregateResults
  private def decomposeTask(task: String, registry: AgentRegistry): TaskPlan =

    // ---- Heuristic skill inference from task text ----
    def norm(s: String) = s.trim.toLowerCase
    val textL = norm(task)

    // map observable intents/keywords to skills
    val keywordSkills: Seq[(String, String)] = Seq(
      "plan" -> "planning",
      "decompose" -> "planning",
      "search" -> "search",
      "crawl" -> "search",
      "web" -> "search",
      "extract entities" -> "ner",
      "ner" -> "ner",
      "classify" -> "classification",
      "label" -> "classification",
      "sql" -> "sql",
      "database" -> "sql",
      "summar" -> "summarization", // summary/summarize/summarization
      "report" -> "summarization",
      "tool" -> "tool-use"
    )

    val inferredSkills: Seq[String] =
      keywordSkills.collect {
        case (kw, sk) if textL.contains(kw) => sk
      }.distinct match
        case Nil =>
          Seq(
            "planning",
            "search",
            "summarization"
          ) // sensible default pipeline
        case xs => xs

    // ---- Capabilities preferred for each skill (fallback to "<skill>-agent") ----
    val skillToCapability: Map[String, String] = Map(
      "planning" -> "planner",
      "search" -> "web-crawler",
      "ner" -> "ie-agent",
      "classification" -> "classifier",
      "sql" -> "sql-agent",
      "summarization" -> "summarizer",
      "tool-use" -> "orchestrator"
    )

    // ---- Availability check using the subscription-aware AgentRegistry ----
    def skillAvailable(sk: String): Boolean =
      try Await.result(registry.findAgentsBySkill(sk), 250.millis).nonEmpty
      catch case _: Throwable => false

    val availableSkills = inferredSkills.filter(skillAvailable)

    // If no skill is available, fall back to a minimal pipeline to avoid dead-ends
    val pipelineSkills: Seq[String] =
      if availableSkills.nonEmpty then availableSkills
      else Seq("planning", "summarization")

    // Impose a gentle order to form a reasonable DAG (acyclic linear chain by default)
    val canonicalOrder = List(
      "planning",
      "search",
      "ner",
      "sql",
      "classification",
      "summarization"
    )
    val orderedSkills = canonicalOrder.filter(pipelineSkills.contains)

    // ---- Build concrete TaskSteps ----
    val steps = scala.collection.mutable.ArrayBuffer.empty[TaskStep]
    var last: Option[String] = None

    orderedSkills.foreach { sk =>
      val stepId = s"${sk}-step"
      val cap = skillToCapability.getOrElse(sk, s"${sk}-agent")
      val deps = last.toSeq
      val instruction = sk match
        case "planning" =>
          s"Create a concise DAG of steps to accomplish: '$task'. Return only the next actionable instruction."
        case "search" =>
          s"Search the web for authoritative sources relevant to: '$task'. Provide short citations."
        case "ner" =>
          s"Extract key entities (people, organizations, dates, amounts) from gathered content for: '$task'."
        case "sql" =>
          s"Generate and execute SQL (or pseudo-SQL) over available datasets to answer: '$task'."
        case "classification" =>
          s"Categorize the findings into labeled groups relevant to: '$task'."
        case "summarization" =>
          s"Synthesize the results from prior steps into a crisp final answer. Add '[done]' if satisfied."
        case _ =>
          s"Perform skill '${sk}' towards solving: '$task'."

      steps += TaskStep(
        id = stepId,
        agentCapability = cap,
        instruction = instruction,
        dependencies = deps
      )
      last = Some(stepId)
    }

    if steps.isEmpty then
      // ultimate fallback: a single-step generic plan
      steps += TaskStep(
        id = "generic-step",
        agentCapability = "sql-agent",
        instruction =
          s"Attempt to complete the task: '$task'. If external tools are needed, describe them.",
        dependencies = Seq.empty
      )

    TaskPlan(steps.toSeq)

  // Decide if the final result is satisfactory
  private def isSatisfactory(state: TaskState): Boolean =
    state.plan.steps.lastOption
      .flatMap(s => state.results.get(s.id))
      .exists: m =>
        val mdOk =
          m.content.metadata.get("satisfied").exists(_.equalsIgnoreCase("true"))
        val textOk = m.content.text.toLowerCase.contains("[done]")
        mdOk || textOk

  // Build a final aggregated message across all steps (topological order)
  private def aggregateResults(state: TaskState): Message =
    val parts =
      state.plan.steps.flatMap: s =>
        state.results
          .get(s.id)
          .map: m =>
            val tag = s"[${s.id}]"
            s"$tag ${m.content.text}"
    val text =
      if parts.nonEmpty then parts.mkString("\n\n")
      else "No results produced."
    Message(
      role = MessageRole.Assistant,
      content = MessageContent(text),
      conversationId = state.context.id,
      agentId = Some("coordinator")
    )

  // Dispatch all ready steps (dependencies satisfied and not yet started/completed)
  private def dispatchReadySteps(
      convId: String,
      state: TaskState,
      registry: AgentRegistry
  )(using ctx: ActorContext[Command], ec: ExecutionContext): TaskState =
    val completedSet = state.completed
    val inProg = state.inProgress
    val ready = state.plan.steps.filter { s =>
      !completedSet.contains(s.id) &&
      !inProg.contains(s.id) &&
      s.dependencies.forall(dep => completedSet.contains(dep))
    }

    ready.foldLeft(state) { (acc, step) =>
      dispatchSpecificStep(convId, acc, step, registry, refinement = false)
    }

  // Dispatch a specific step with optional refinement instruction
  private def dispatchSpecificStep(
      convId: String,
      state: TaskState,
      step: TaskStep,
      registry: AgentRegistry,
      refinement: Boolean
  )(using ctx: ActorContext[Command], ec: ExecutionContext): TaskState =
    val depsContextText =
      if step.dependencies.nonEmpty then
        step.dependencies
          .flatMap(state.results.get)
          .zip(step.dependencies)
          .map { case (msg, depId) =>
            val who = msg.agentId.getOrElse("agent")
            s"- [$depId][$who]: ${msg.content.text}"
          }
          .mkString("\n")
      else ""

    val refinementHint =
      if refinement then
        s"\n\nRefine and improve the previous result. The last attempt was not satisfactory."
      else ""

    val fullInstruction =
      if depsContextText.nonEmpty then
        s"${step.instruction}$refinementHint\n\nContext from dependencies:\n$depsContextText"
      else s"${step.instruction}$refinementHint"

    val userMsg = Message(
      role = MessageRole.User,
      content = MessageContent(
        text = fullInstruction,
        metadata = Map(
          "stepId" -> step.id,
          "refinement" -> refinement.toString
        )
      ),
      conversationId = state.context.id
    )

    // Resolve agent and send the message; correlate failures to this user message id
    ctx.pipeToSelf(registry.findAgent(step.agentCapability)):
      case Success(Some(agentRef)) =>
        agentRef ! ProcessMessage(
          userMsg,
          state.context,
          ctx.self.asInstanceOf[ActorRef[Any]]
        )
        NoOp
      case Success(None) =>
        ProcessingFailed(
          s"Agent '${step.agentCapability}' not found",
          userMsg.id
        )
      case Failure(ex) =>
        ProcessingFailed(ex.getMessage, userMsg.id)

    state.copy(
      inProgress = state.inProgress + step.id,
      msgIdToStep = state.msgIdToStep + (userMsg.id -> step.id)
    )
