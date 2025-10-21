package net.kaduk.agents

import net.kaduk.domain.*
import net.kaduk.infrastructure.llm.LLMProvider
import net.kaduk.infrastructure.registry.AgentRegistry
import net.kaduk.telemetry.UiEventBus

import org.apache.pekko
import pekko.actor.typed.{ActorRef, Behavior}
import pekko.actor.typed.scaladsl.{ActorContext, Behaviors}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}
import java.util.UUID

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
    val attempt = message.content.metadata
      .get("clarificationAttempt")
      .flatMap(v => Try(v.toInt).toOption)
      .getOrElse(0)

    val maxAttempts = capability.config
      .get("maxClarificationRounds")
      .flatMap(v => Try(v.toInt).toOption)
      .getOrElse(3)

    val contextWithDetails = integrateClarificationIntoContext(message, context)
    val productDetailsOpt = extractProductDetails(message, contextWithDetails)
    val minDetailLength = capability.config
      .get("minDetailLength")
      .flatMap(v => Try(v.toInt).toOption)
      .getOrElse(60)

    val detailIsWeak =
      productDetailsOpt.forall(text => text.trim.isEmpty || text.trim.length < minDetailLength)

    if detailIsWeak && attempt < maxAttempts then
      val request = buildProductClarificationRequest(
        capability,
        contextWithDetails,
        stepId,
        attempt + 1
      )
      ctx.log.info(
        s"[${capability.name}] Requesting product clarification (attempt ${attempt + 1})"
      )
      replyTo ! request
      ()
    else
      val productDetails = productDetailsOpt
        .filter(_.nonEmpty)
        .getOrElse(message.content.text.trim)

      val analysisContext =
        contextWithDetails.copy(
          metadata = contextWithDetails.metadata + ("productDetails" -> productDetails)
        )

      val prompt = buildPrompt(productDetails, analysisContext, capability)
      val userMsg = Message(
        role = MessageRole.User,
        content = MessageContent(prompt, metadata = Map("stepId" -> stepId)),
        conversationId = analysisContext.id
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
              conversationId = analysisContext.id,
              agentId = Some(capability.name)
            )

            uiBus.foreach { bus =>
              bus ! UiEventBus.Publish(
                UiEventBus.ChatMessage(
                  analysisContext.id,
                  responseMsg.role.toString,
                  responseMsg.id,
                  responseMsg.content.text,
                  responseMsg.agentId
                )
              )
              bus ! UiEventBus.Publish(
                UiEventBus.AgentComplete(
                  analysisContext.id,
                  capability.name,
                  stepId,
                  responseMsg.id,
                  responseMsg.content.text.length
                )
              )
            }

            replyTo ! BaseAgent.ProcessedMessage(
              responseMsg,
              analysisContext
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
      productDetails: String,
      context: ConversationContext,
      capability: AgentCapability
  ): String =
    val productContext = productDetails.trim
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

  private def integrateClarificationIntoContext(
      message: Message,
      context: ConversationContext
  ): ConversationContext =
    val clarifiedDetails =
      message.content.metadata
        .get("clarificationTopic")
        .filter(_ == "product-details")
        .flatMap(_ => Option(message.content.text.trim))
        .filter(_.nonEmpty)

    val metadataDetails =
      message.content.metadata.get("productDetails").filter(_.nonEmpty)

    clarifiedDetails
      .orElse(metadataDetails)
      .map(detail =>
        context.copy(metadata = context.metadata + ("productDetails" -> detail))
      )
      .getOrElse(context)

  private def extractProductDetails(
      message: Message,
      context: ConversationContext
  ): Option[String] =
    val fromContext = context.metadata.get("productDetails").filter(_.nonEmpty)

    val fromConversation =
      context.messages.reverseIterator
        .collectFirst {
          case msg
              if msg.content.metadata
                .get("clarificationTopic")
                .contains("product-details") =>
            msg.content.text.trim
        }
        .filter(_.nonEmpty)

    val fromMessageMetadata =
      message.content.metadata.get("productDetails").filter(_.nonEmpty)

    val fromClarification =
      message.content.metadata
        .get("clarificationTopic")
        .filter(_ == "product-details")
        .flatMap(_ => Option(message.content.text.trim))
        .filter(_.nonEmpty)

    val fromBody =
      Option(message.content.text.trim)
        .filter(text => text.nonEmpty && text.length > 40)

    fromClarification
      .orElse(fromMessageMetadata)
      .orElse(fromContext)
      .orElse(fromConversation)
      .orElse(fromBody)

  private def buildProductClarificationRequest(
      capability: AgentCapability,
      context: ConversationContext,
      stepId: String,
      attempt: Int
  ): BaseAgent.ClarificationRequest =
    val clarificationId = UUID.randomUUID().toString
    val questionText =
      """I need a complete product brief before running a FIBO-aligned risk assessment.
        |- Summarize the product and its purpose.
        |- Identify target customer segments.
        |- Describe expected cash inflows and outflows.
        |- List collateral, guarantees, or risk mitigations.
        |- Mention regulatory constraints or eligibility criteria.
        |
        |Provide concise bullet points. Only reply with "clarification-needed" if you truly cannot supply the details.
        |""".stripMargin

    val metadata = Map(
      "clarificationTopic" -> "product-details",
      "clarificationId" -> clarificationId,
      "clarificationCapability" -> "creator",
      "clarificationSkill" -> "create idea,banking expertise",
      "clarificationAttempt" -> attempt.toString,
      "requestedBy" -> capability.name
    )

    val question = Message(
      role = MessageRole.Agent,
      content = MessageContent(questionText, metadata),
      conversationId = context.id,
      agentId = Some(capability.name)
    )

    BaseAgent.ClarificationRequest(
      originalStepId = stepId,
      question = question,
      context = context,
      targetCapabilities = Set("creator"),
      targetSkills = Set("create idea", "banking expertise"),
      metadata = metadata
    )
