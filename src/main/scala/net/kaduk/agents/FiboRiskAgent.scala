package net.kaduk.agents

import net.kaduk.domain.*
import net.kaduk.infrastructure.llm.LLMProvider
import net.kaduk.infrastructure.registry.AgentRegistry
import net.kaduk.telemetry.UiEventBus

import org.apache.pekko
import pekko.actor.typed.{ActorRef, Behavior}
import pekko.actor.typed.scaladsl.{ActorContext, Behaviors}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object FiboRiskAgent:

  private val fiboRiskGlossary: Map[String, String] = Map(
    "CreditRisk" ->
      "Potential that a borrower or counterparty fails to meet obligations. FIBO aligns this with CreditRiskAssessment and BorrowerRiskProfile.",
    "MarketRisk" ->
      "Exposure to market-driven value changes (interest rates, FX, equities). FIBO references MarketRisk and MarketRateShock concepts.",
    "LiquidityRisk" ->
      "Risk that cash flow obligations cannot be met when due. FIBO models this via LiquidityRisk and FundingLiquidity.",
    "OperationalRisk" ->
      "Losses stemming from failed processes, people, systems, or external events. Captured in FIBO under OperationalRiskEvent.",
    "ComplianceRisk" ->
      "Risk of sanctions or loss due to non-compliance. FIBO treats this as RegulatoryCompliance and PolicyBreach events.",
    "SystemicRisk" ->
      "Propagation of distress across the financial system. FIBO's SystemicRisk and FinancialSystemStability classes describe this."
  )

  private val fiboDimensions: Seq[String] =
    Seq("CreditRisk", "MarketRisk", "LiquidityRisk", "OperationalRisk", "ComplianceRisk", "SystemicRisk")

  def apply(
      capability: AgentCapability,
      provider: LLMProvider,
      registry: AgentRegistry,
      uiBus: Option[ActorRef[UiEventBus.Command]] = None
  )(using ec: ExecutionContext): Behavior[BaseAgent.Command] =
    Behaviors.setup { ctx =>
      registry.register(ctx.self, capability, capability.skills)
      ctx.log.info(
        s"[${capability.name}] FIBO risk agent online with skills: ${capability.skills.mkString(", ")}"
      )
      idle(capability, provider, registry, uiBus, ctx)
    }

  private def idle(
      capability: AgentCapability,
      provider: LLMProvider,
      registry: AgentRegistry,
      uiBus: Option[ActorRef[UiEventBus.Command]],
      ctx: ActorContext[BaseAgent.Command]
  )(using
      ec: ExecutionContext
  ): Behavior[BaseAgent.Command] =
    Behaviors.receiveMessage {
      case BaseAgent.ProcessMessage(message, context, replyTo) =>
        handleRiskAssessment(message, context, replyTo, capability, provider, uiBus)(using ctx, ec)
        Behaviors.same

      case BaseAgent.StreamMessage(_, _, _) =>
        ctx.log.warn(s"[${capability.name}] Streaming not supported for FIBO risk agent")
        Behaviors.same

      case BaseAgent.GetStatus =>
        ctx.log.debug(s"[${capability.name}] Status requested (fibo-risk idle)")
        Behaviors.same

      case BaseAgent.Stop =>
        ctx.log.info(s"[${capability.name}] Stopping FIBO risk agent")
        registry.deregister(ctx.self, capability)
        Behaviors.stopped

      case BaseAgent.NoOp =>
        Behaviors.same

      case other =>
        ctx.log.warn(
          s"[${capability.name}] Unhandled message type: ${other.getClass.getSimpleName}"
        )
        Behaviors.same
    }

  private def handleRiskAssessment(
      message: Message,
      context: ConversationContext,
      replyTo: ActorRef[Any],
      capability: AgentCapability,
      provider: LLMProvider,
      uiBus: Option[ActorRef[UiEventBus.Command]]
  )(using
      ctx: ActorContext[BaseAgent.Command],
      ec: ExecutionContext
  ): Unit =
    val stepId = message.content.metadata.getOrElse("stepId", "fibo-risk")
    ctx.log.info(s"[${capability.name}] Performing FIBO risk analysis step=$stepId")

    uiBus.foreach(
      _ ! UiEventBus.Publish(
        UiEventBus.AgentStart(context.id, capability.name, stepId, message.id, false)
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
    //FIXME - use my FIBO RISK assesment ontology server. (IT IS ONLY DEMO)
    val prompt = buildPrompt(message, context, capability)
    val userMsg = Message(
      role = MessageRole.User,
      content = MessageContent(prompt, metadata = Map("stepId" -> stepId)),
      conversationId = context.id
    )

    provider
      .completion(
        Seq(userMsg),
        capability.config.getOrElse(
          "systemPrompt",
          "You are a banking risk officer. Ground every assessment in the FIBO ontology definitions provided."
        )
      )
      .onComplete {
        case Success(resultText) =>
          val responseMsg = Message(
            role = MessageRole.Assistant,
            content = MessageContent(
              resultText,
              metadata = Map(
                "satisfied" -> "true",
                "analysisFramework" -> "FIBO"
              )
            ),
            conversationId = context.id,
            agentId = Some(capability.name)
          )

          uiBus.foreach { bus =>
            bus ! UiEventBus.Publish(
              UiEventBus.ChatMessage(
                context.id,
                responseMsg.role.toString,
                responseMsg.id,
                responseMsg.content.text,
                responseMsg.agentId
              )
            )
            bus ! UiEventBus.Publish(
              UiEventBus.AgentComplete(
                context.id,
                capability.name,
                stepId,
                responseMsg.id,
                responseMsg.content.text.length
              )
            )
          }

          replyTo ! BaseAgent.ProcessedMessage(
            responseMsg,
            context
              .addMessage(message)
              .addMessage(responseMsg)
          )

        case Failure(ex) =>
          val err = s"FIBO risk analysis failed: ${ex.getMessage}"
          ctx.log.error(err, ex)
          uiBus.foreach(
            _ ! UiEventBus.Publish(UiEventBus.ErrorEvent(context.id, err))
          )
          replyTo ! BaseAgent.ProcessingFailed(err, message.id)
      }

  private def buildPrompt(
      message: Message,
      context: ConversationContext,
      capability: AgentCapability
  ): String =
    val productContext = message.content.text.trim
    val metadataLines =
      if context.metadata.nonEmpty then
        context.metadata.toSeq
          .map { case (k, v) => s"- $k: $v" }
          .mkString("\n")
      else "None supplied."

    val fiboTable = fiboDimensions
      .map { dim =>
        val definition = fiboRiskGlossary.getOrElse(dim, "See FIBO definition.")
        s"- $dim: $definition"
      }
      .mkString("\n")

    val riskScale =
      """Use the following ordinal scale:
        |- Low: aligned with FIBO risk controls, minimal residual exposure.
        |- Moderate: identifiable exposures with mitigations defined.
        |- High: material exposure needing immediate remediation.
        |- Critical: severe exposure breaching risk appetite or regulation.
        |""".stripMargin

    s"""Assess the risk of the following banking product using the FIBO ontology.
       |
       |=== Product Description ===
       |$productContext
       |
       |=== Context Metadata ===
       |$metadataLines
       |
       |=== FIBO Risk Dimensions ===
       |$fiboTable
       |
       |$riskScale
       |
       |For each dimension, provide:
       |1. Risk rating (Low/Moderate/High/Critical).
       |2. Evidence referencing FIBO concepts.
       |3. Recommended mitigations tied to FIBO controls or policies.
       |
       |Then produce:
       |- Aggregated overall risk verdict.
       |- Key regulatory references (Basel, EBA, etc.) impacted.
       |- Summary of recommended next actions.
       |
       |Respond in markdown with clear sections per dimension and overall summary.
       |""".stripMargin
