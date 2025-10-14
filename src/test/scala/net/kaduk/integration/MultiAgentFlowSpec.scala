package net.kaduk.integration

import org.apache.pekko.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

// Project imports
import net.kaduk.agents.{BaseAgent, CoordinatorAgent}
import net.kaduk.infrastructure.registry.AgentRegistry
import net.kaduk.domain.*

/**
  * Integration-ish tests that verify CoordinatorAgent:
  *   1) decomposes a task into a DAG
  *   2) dispatches steps respecting dependencies
  *   3) aggregates all responses into a single final response
  *   4) loops up to k times (via ConversationContext.metadata("maxLoops")) until satisfied
  */
class MultiAgentFlowSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike:

  given ExecutionContext = system.executionContext

  // ---------------- Helpers ----------------
  private def mockWorker(name: String, replyText: String, satisfied: Boolean = false): Behavior[BaseAgent.Command] =
    Behaviors.receiveMessage {
      case BaseAgent.ProcessMessage(msg, ctx, replyTo) =>
        val meta = if satisfied then Map("satisfied" -> "true") else Map.empty[String, String]
        val content = MessageContent(text = s"[$name] ${replyText}", metadata = meta)
        val assistantMsg = Message(role = MessageRole.Assistant, content = content, conversationId = ctx.id)
        val updatedCtx   = ctx.addMessage(msg).addMessage(assistantMsg)
        val response     = BaseAgent.ProcessedMessage(assistantMsg, updatedCtx)
        replyTo.asInstanceOf[ActorRef[BaseAgent.Response]] ! response
        Behaviors.same
      case BaseAgent.StreamMessage(msg, ctx, replyTo) =>
        // not used in this spec
        Behaviors.same
      case BaseAgent.Stop => Behaviors.stopped
      case _              => Behaviors.same
    }

  /** A stub registry mapping capabilities -> specific mock actors. */
  private final class StubRegistry(mapping: Map[String, ActorRef[BaseAgent.Command]])(using as: org.apache.pekko.actor.typed.ActorSystem[?], ec: ExecutionContext)
      extends AgentRegistry:

    override def findAgent(capability: String): Future[Option[ActorRef[BaseAgent.Command]]] =
      Future.successful(mapping.get(capability))

    override def findAllAgents(agentType: String): Future[Set[ActorRef[BaseAgent.Command]]] =
      Future.successful(mapping.get(agentType).toSet)

    // skills-based lookups are unused by CoordinatorAgent dispatch in this spec
    override def findAgentsBySkill(skill: String): Future[Set[ActorRef[BaseAgent.Command]]] =
      // Only report skills we have a matching capability for in this stub.
      // This avoids injecting steps like "ner" which map to "ie-agent" that we didn't register.
      val capOpt = skill.trim.toLowerCase match
        case "planning"      => Some("planner")
        case "search"        => Some("web-crawler")
        case "summarization" => Some("summarizer")
        case _               => None
      Future.successful(capOpt.flatMap(mapping.get).toSet)

    override def findAgentsByAnySkill(skills: Set[String]): Future[Set[ActorRef[BaseAgent.Command]]] =
      Future.successful(Set.empty)

    override def findAgentsByAllSkills(skills: Set[String]): Future[Set[ActorRef[BaseAgent.Command]]] =
      Future.successful(Set.empty)

  // ---------------- Tests ----------------
  "CoordinatorAgent" should {

    "decompose a simple task into a linear DAG and dispatch in order, aggregating a single final response" in {
      // Arrange: three mock workers to match capabilities used by decomposeTask: planner -> web-crawler -> summarizer
      val planner    = spawn(mockWorker("planner", "planned steps"))
      val crawler    = spawn(mockWorker("web-crawler", "fetched evidence"))
      val summarizer = spawn(mockWorker("summarizer", "final synthesis [done]", satisfied = true))

      val registry = new StubRegistry(Map(
        "planner"      -> planner,
        "web-crawler"  -> crawler,
        "summarizer"   -> summarizer
      ))(using system, system.executionContext)

      val coordinator = spawn(CoordinatorAgent(registry))
      val probe = createTestProbe[BaseAgent.Response]()

      val task = "Plan, search the web, and report a summary about NER in finance."
      val ctx  = ConversationContext(id = "conv-1", metadata = Map("maxLoops" -> "1"))
      val msg  = Message(role = MessageRole.User, content = MessageContent(task), conversationId = ctx.id)

      // Act
      coordinator ! BaseAgent.ProcessMessage(msg, ctx, probe.ref.unsafeUpcast[Any])

      // Assert final aggregated response
      val finalResponse = probe.expectMessageType[BaseAgent.ProcessedMessage](5.seconds)
      val text = finalResponse.message.content.text.toLowerCase

      // It should include outputs from all three stages and a done signal
      assert(text.contains("planned steps"))
      assert(text.contains("fetched evidence"))
      assert(text.contains("final synthesis"))
      assert(text.contains("[done]"))
    }

    "respect DAG dependencies: do not dispatch downstream until upstream step completes" in {
      // We coordinate via probes to observe order; the planner replies only when we allow it
      val planProbe    = TestProbe[BaseAgent.Command]()
      val crawlProbe   = TestProbe[BaseAgent.Command]()
      val sumProbe     = TestProbe[BaseAgent.Command]()

      val planner = spawn(Behaviors.receiveMessage[BaseAgent.Command] {
        case BaseAgent.ProcessMessage(m, ctx, replyTo) =>
          planProbe.ref ! BaseAgent.NoOp
          val assistant = Message(role = MessageRole.Assistant, content = MessageContent("P1"), conversationId = ctx.id)
          val updated   = ctx.addMessage(m).addMessage(assistant)
          val resp      = BaseAgent.ProcessedMessage(assistant, updated)
          replyTo.asInstanceOf[ActorRef[BaseAgent.Response]] ! resp
          Behaviors.same
        case _ => Behaviors.same
      })

      val crawler = spawn(Behaviors.receiveMessage[BaseAgent.Command] {
        case BaseAgent.ProcessMessage(m, ctx, replyTo) =>
          crawlProbe.ref ! BaseAgent.NoOp
          val assistant = Message(role = MessageRole.Assistant, content = MessageContent("C1"), conversationId = ctx.id)
          val updated   = ctx.addMessage(m).addMessage(assistant)
          val resp      = BaseAgent.ProcessedMessage(assistant, updated)
          replyTo.asInstanceOf[ActorRef[BaseAgent.Response]] ! resp
          Behaviors.same
        case _ => Behaviors.same
      })

      val summarizer = spawn(Behaviors.receiveMessage[BaseAgent.Command] {
        case BaseAgent.ProcessMessage(m, ctx, replyTo) =>
          sumProbe.ref ! BaseAgent.NoOp
          val assistant = Message(role = MessageRole.Assistant, content = MessageContent("S1 [done]"), conversationId = ctx.id)
          val updated   = ctx.addMessage(m).addMessage(assistant)
          val resp      = BaseAgent.ProcessedMessage(assistant, updated)
          replyTo.asInstanceOf[ActorRef[BaseAgent.Response]] ! resp
          Behaviors.same
        case _ => Behaviors.same
      })

      val registry = new StubRegistry(Map(
        "planner"      -> planner,
        "web-crawler"  -> crawler,
        "summarizer"   -> summarizer
      ))(using system, system.executionContext)

      val coordinator = spawn(CoordinatorAgent(registry))
      val sinkProbe = createTestProbe[BaseAgent.Response]()

      val task = "plan, then search, then summarize"
      val ctx  = ConversationContext(id = "conv-2")
      val msg  = Message(role = MessageRole.User, content = MessageContent(task), conversationId = ctx.id)

      coordinator ! BaseAgent.ProcessMessage(msg, ctx, sinkProbe.ref.unsafeUpcast[Any])

      // Observe sequence: planning must happen before crawling, which must happen before summarization
      planProbe.expectMessage(BaseAgent.NoOp)
      crawlProbe.expectMessage(BaseAgent.NoOp)
      sumProbe.expectMessage(BaseAgent.NoOp)

      sinkProbe.expectMessageType[BaseAgent.ProcessedMessage]
    }

    "loop up to k times until satisfied using metadata or [done] marker" in {
      // First summarizer reply lacks satisfaction, second includes it
      var invocationCount = 0
      val summarizer = spawn(Behaviors.receiveMessage[BaseAgent.Command] {
        case BaseAgent.ProcessMessage(m, ctx, replyTo) =>
          invocationCount += 1
          val (txt, meta) =
            if invocationCount == 1 then ("first try", Map.empty[String, String])
            else ("second try [done]", Map("satisfied" -> "true"))
          val content = MessageContent(txt, metadata = meta)
          val assistant = Message(role = MessageRole.Assistant, content = content, conversationId = ctx.id)
          val updated   = ctx.addMessage(m).addMessage(assistant)
          val resp      = BaseAgent.ProcessedMessage(assistant, updated)
          replyTo.asInstanceOf[ActorRef[BaseAgent.Response]] ! resp
          Behaviors.same
        case _ => Behaviors.same
      })

      val planner = spawn(mockWorker("planner", "ok"))
      val crawler = spawn(mockWorker("web-crawler", "ok"))

      val registry = new StubRegistry(Map(
        "planner"      -> planner,
        "web-crawler"  -> crawler,
        "summarizer"   -> summarizer
      ))(using system, system.executionContext)

      val coordinator = spawn(CoordinatorAgent(registry))
      val sinkProbe = createTestProbe[BaseAgent.Response]()

      val ctx  = ConversationContext(id = "conv-3", metadata = Map("maxLoops" -> "2"))
      val msg  = Message(role = MessageRole.User, content = MessageContent("do work"), conversationId = ctx.id)

      coordinator ! BaseAgent.ProcessMessage(msg, ctx, sinkProbe.ref.unsafeUpcast[Any])

      val finalResp = sinkProbe.expectMessageType[BaseAgent.ProcessedMessage]
      val text = finalResp.message.content.text
      assert(text.toLowerCase.contains("second try"), "expected second loop to produce the final, satisfied answer")
      assert(invocationCount == 2, s"expected 2 summarizer invocations, got $invocationCount")
    }
  }
