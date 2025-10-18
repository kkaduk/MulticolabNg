package net.kaduk.agents

import org.apache.pekko
import org.apache.pekko.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.NotUsed
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers
import net.kaduk.domain.*
import net.kaduk.infrastructure.llm.LLMProvider
import net.kaduk.infrastructure.registry.AgentRegistry
import net.kaduk.telemetry.UiEventBus

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import java.util.UUID
import scala.util.Try

class LLMAgentTelemetrySpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {

  given ExecutionContext = system.executionContext

  // Simple configurable mock LLM provider
  final class MockLLMProvider(responses: Map[String, String] = Map.empty) extends LLMProvider {
    override def name: String = "mock-llm"
    override def close(): Future[Unit] = Future.successful(())

    var lastCompletionMessages: Seq[Message] = Seq.empty
    var lastSystemPrompt: String = ""

    override def completion(messages: Seq[Message], systemPrompt: String): Future[String] = {
      lastCompletionMessages = messages
      lastSystemPrompt = systemPrompt
      val last = messages.lastOption.map(_.content.text).getOrElse("")
      if (last.contains("Decompose this task")) {
        // Planning turn
        Future.successful(responses.getOrElse("plan", defaultPlan))
      } else if (last.contains("stepId")) {
        // Step-specific prompt; synthesize by step id
        val stepId = """step-\d+""".r.findFirstIn(last).getOrElse("unknown")
        Future.successful(responses.getOrElse(stepId, s"Result for $stepId"))
      } else {
        // Direct execution
        Future.successful(responses.getOrElse("default", "DIRECT-RESPONSE"))
      }
    }

    override def streamCompletion(messages: Seq[Message], systemPrompt: String): Source[StreamToken, NotUsed] = {
      Source(List(
        StreamToken("Hello ", UUID.randomUUID().toString),
        StreamToken("World", UUID.randomUUID().toString),
        StreamToken("", UUID.randomUUID().toString, isComplete = true)
      ))
    }

    private def defaultPlan: String =
      """{
        "steps": [
          {"id": "step-1", "description": "Do A", "requiredSkills": ["a"], "targetAgent": "a1", "dependencies": []},
          {"id": "step-2", "description": "Do B", "requiredSkills": ["b"], "targetAgent": "a2", "dependencies": ["step-1"]}
        ],
        "strategy": "sequential",
        "reasoning": "A then B"
      }"""
  }

  private def spawnEchoAgent(capability: String, replyText: String = "ok"): ActorRef[BaseAgent.Command] = {
    spawn(Behaviors.receiveMessage[BaseAgent.Command] {
      case BaseAgent.ProcessMessage(msg, ctx, replyTo) =>
        val responseMsg = Message(
          role = MessageRole.Assistant,
          content = MessageContent(s"[$capability] $replyText"),
          conversationId = ctx.id,
          agentId = Some(capability)
        )
        replyTo.asInstanceOf[ActorRef[BaseAgent.Response]] ! BaseAgent.ProcessedMessage(responseMsg, ctx.addMessage(responseMsg))
        Behaviors.same
      case _ => Behaviors.same
    }, s"agent-$capability-${UUID.randomUUID().toString.take(6)}")
  }

  "LLMAgent observability" should {

    "publish ChatMessage and lifecycle events for direct execution" in {
      val uiProbe = createTestProbe[UiEventBus.Command]()
      val replies = createTestProbe[Any]()

      val capability = AgentCapability(
        name = "director",
        agentType = AgentType.LLM,
        skills = Set("text-generation"),
        provider = "mock",
        config = Map("systemPrompt" -> "You are a helpful assistant (DIRECT)")
      )
      val provider = new MockLLMProvider(Map("default" -> "DIRECT-OK"))
      val registry = AgentRegistry()(using system, system.executionContext)

      val agent = spawn(LLMAgent(capability, provider, registry, Some(uiProbe.ref), planningEnabled = false))

      val msg = Message(role = MessageRole.User, content = MessageContent("Hi"), conversationId = "c-direct")
      val ctx = ConversationContext("c-direct")

      agent ! BaseAgent.ProcessMessage(msg, ctx, replies.ref.unsafeUpcast[Any])

      // Expect a set of UI bus publishes; order can vary slightly due to async, so fish for types
      val seen = scala.collection.mutable.Set[String]()
      uiProbe.fishForMessage(5.seconds) {
        case UiEventBus.Publish(ev) =>
          ev match {
            case UiEventBus.ChatMessage(cid, role, _, _, _) if cid == "c-direct" && role == "User" =>
              seen += "user"
            case UiEventBus.AgentStart(cid, agentName, _, _, _) if cid == "c-direct" && agentName == "director" =>
              seen += "start"
            case UiEventBus.ChatMessage(cid, role, _, text, _) if cid == "c-direct" && role == "System" && text.contains("helpful assistant (DIRECT)") =>
              seen += "sys"
            case UiEventBus.ChatMessage(cid, role, _, text, _) if cid == "c-direct" && role == "Assistant" && text == "DIRECT-OK" =>
              seen += "assistant"
            case UiEventBus.AgentComplete(cid, agentName, _, _, len) if cid == "c-direct" && agentName == "director" && len == "DIRECT-OK".length =>
              seen += "complete"
            case _ =>
          }
          // Wait until we see the completion as well
          if (seen.contains("user") && seen.contains("start") && seen.contains("assistant") && seen.contains("complete"))
            pekko.actor.testkit.typed.FishingOutcome.Complete
          else pekko.actor.testkit.typed.FishingOutcome.Continue
      }

      replies.expectMessageType[BaseAgent.ProcessedMessage]
      seen should contain allOf ("user", "start", "assistant", "complete")
      // system prompt may be empty in some configs; don't strictly require it
    }

    "publish streaming lifecycle and messages" in {
      val uiProbe = createTestProbe[UiEventBus.Command]()
      val streamProbe = createTestProbe[BaseAgent.StreamResponse]()

      val capability = AgentCapability(
        name = "streamer",
        agentType = AgentType.LLM,
        skills = Set("text-generation"),
        provider = "mock",
        config = Map.empty // no system prompt -> optional sys ChatMessage
      )
      val provider = new MockLLMProvider()
      val registry = AgentRegistry()(using system, system.executionContext)

      val agent = spawn(LLMAgent(capability, provider, registry, Some(uiProbe.ref), planningEnabled = false))

      val msg = Message(role = MessageRole.User, content = MessageContent("Stream it"), conversationId = "c-stream")
      val ctx = ConversationContext("c-stream")

      agent ! BaseAgent.StreamMessage(msg, ctx, streamProbe.ref.unsafeUpcast[Any])

      // Expect chunk(s) then completion; allow multiple chunks
      val first = streamProbe.expectMessageType[BaseAgent.StreamChunk](3.seconds)
      first.content should not be empty
      // Wait for completion while allowing more chunks
      streamProbe.fishForMessage(3.seconds) {
        case _: BaseAgent.StreamComplete => pekko.actor.testkit.typed.FishingOutcome.Complete
        case _: BaseAgent.StreamChunk    => pekko.actor.testkit.typed.FishingOutcome.Continue
        case _                           => pekko.actor.testkit.typed.FishingOutcome.Continue
      }

      // UI lifecycle
      val seen = scala.collection.mutable.Set[String]()
      uiProbe.fishForMessage(5.seconds) {
        case UiEventBus.Publish(ev) =>
          ev match {
            case UiEventBus.AgentStart("c-stream", "streamer", "stream", _, _) => seen += "start"
            case UiEventBus.ChatMessage("c-stream", "User", _, _, _)          => seen += "user"
            case UiEventBus.ChatMessage("c-stream", "Assistant", _, text, _) if text.contains("Hello ") || text.contains("World") =>
              seen += "assistant"
            case UiEventBus.AgentComplete("c-stream", "streamer", "stream", _, _) =>
              seen += "complete"
            case _ =>
          }
          if (seen.contains("start") && seen.contains("user") && seen.contains("assistant") && seen.contains("complete"))
            pekko.actor.testkit.typed.FishingOutcome.Complete
          else pekko.actor.testkit.typed.FishingOutcome.Continue
      }
    }

    "planning emits plan and step lifecycle events with chat around plan" in {
      val uiProbe = createTestProbe[UiEventBus.Command]()
      val replies = createTestProbe[Any]()

      val capability = AgentCapability(
        name = "orchestrator",
        agentType = AgentType.Orchestrator,
        skills = Set("planning"),
        provider = "mock",
        config = Map.empty
      )
      val provider = new MockLLMProvider(Map(
        "plan" ->
          """{
            "steps": [
              {"id": "step-1", "description": "A", "requiredSkills": ["a"], "targetAgent": "a1", "dependencies": []},
              {"id": "step-2", "description": "B", "requiredSkills": ["b"], "targetAgent": "a2", "dependencies": ["step-1"]}
            ],
            "strategy": "sequential",
            "reasoning": "A then B"
          }"""
      ))
      val registry = AgentRegistry()(using system, system.executionContext)
      // register agents a1 and a2
      val a1 = spawnEchoAgent("a1", "A-OK")
      val a2 = spawnEchoAgent("a2", "B-OK")
      registry.register(a1, AgentCapability("a1", AgentType.Worker, Set("a"), "test", Map.empty))
      registry.register(a2, AgentCapability("a2", AgentType.Worker, Set("b"), "test", Map.empty))

      val agent = spawn(LLMAgent(capability, provider, registry, Some(uiProbe.ref), planningEnabled = true))

      val msg = Message(role = MessageRole.User, content = MessageContent("Please plan"), conversationId = "c-plan")
      val ctx = ConversationContext("c-plan")

      agent ! BaseAgent.ProcessMessage(msg, ctx, replies.ref.unsafeUpcast[Any])

      // Expect plan computed and step lifecycle + aggregate
      var sawPlan = false
      var sawDispatch1 = false
      var sawComplete1 = false
      var sawDispatch2 = false
      var sawComplete2 = false
      var sawAggregate = false

      uiProbe.fishForMessage(10.seconds) {
        case UiEventBus.Publish(ev) =>
          ev match {
            case UiEventBus.PlanComputed("c-plan", steps) =>
              sawPlan = true
              steps.map(_.id).toSet should contain allOf("step-1", "step-2")
            case UiEventBus.StepDispatched("c-plan", "step-1", cap, _) =>
              sawDispatch1 = true
              cap shouldBe "a1"
            case UiEventBus.StepCompleted("c-plan", "step-1") => sawComplete1 = true
            case UiEventBus.StepDispatched("c-plan", "step-2", cap, _) =>
              sawDispatch2 = true
              cap shouldBe "a2"
            case UiEventBus.StepCompleted("c-plan", "step-2") => sawComplete2 = true
            case UiEventBus.AggregateCompleted("c-plan", len) if len > 0 => sawAggregate = true
            case _ =>
          }
          if (sawPlan && sawDispatch1 && sawComplete1 && sawDispatch2 && sawComplete2 && sawAggregate)
            pekko.actor.testkit.typed.FishingOutcome.Complete
          else pekko.actor.testkit.typed.FishingOutcome.Continue
      }

      val finalResp = replies.expectMessageType[BaseAgent.ProcessedMessage](10.seconds)
      finalResp.message.role shouldBe MessageRole.Assistant
      finalResp.message.content.text should include ("A-OK")
      finalResp.message.content.text should include ("B-OK")
    }

    "falls back to self-contained step when no agent is found" in {
      val uiProbe = createTestProbe[UiEventBus.Command]()
      val replies = createTestProbe[Any]()

      val capability = AgentCapability(
        name = "orchestrator",
        agentType = AgentType.Orchestrator,
        skills = Set("planning"),
        provider = "mock",
        config = Map.empty
      )
      val provider = new MockLLMProvider(Map(
        "plan" ->
          """{
            "steps": [
              {"id": "step-1", "description": "No agent available", "requiredSkills": ["x"], "targetAgent": "nonexistent", "dependencies": []}
            ],
            "strategy": "sequential"
          }""",
        "step-1" -> "LOCAL-EXECUTED"
      ))
      val registry = AgentRegistry()(using system, system.executionContext)

      val agent = spawn(LLMAgent(capability, provider, registry, Some(uiProbe.ref), planningEnabled = true))

      val msg = Message(role = MessageRole.User, content = MessageContent("Plan with missing agent"), conversationId = "c-fallback")
      val ctx = ConversationContext("c-fallback")

      agent ! BaseAgent.ProcessMessage(msg, ctx, replies.ref.unsafeUpcast[Any])

      // Expect a StepDispatched using orchestrator capability (self-contained), completion and aggregate
      var sawDispatch = false
      var sawStepCompleted = false
      var sawAggregate = false

      uiProbe.fishForMessage(7.seconds) {
        case UiEventBus.Publish(ev) =>
          ev match {
            case UiEventBus.StepDispatched("c-fallback", "step-1", cap, _) =>
              // Self-contained path tags capability as orchestrator name
              cap shouldBe "orchestrator"
              sawDispatch = true
            case UiEventBus.StepCompleted("c-fallback", "step-1") =>
              sawStepCompleted = true
            case UiEventBus.AggregateCompleted("c-fallback", _) =>
              sawAggregate = true
            case _ =>
          }
          if (sawDispatch && sawStepCompleted && sawAggregate)
            pekko.actor.testkit.typed.FishingOutcome.Complete
          else pekko.actor.testkit.typed.FishingOutcome.Continue
      }

      val finalResp = replies.expectMessageType[BaseAgent.ProcessedMessage](10.seconds)
      finalResp.message.content.text should include ("LOCAL-EXECUTED")
    }

    "parallel strategy aggregates partial success and emits ErrorEvent for failed steps" in {
      val uiProbe = createTestProbe[UiEventBus.Command]()
      val replies = createTestProbe[Any]()

      val capability = AgentCapability(
        name = "orchestrator",
        agentType = AgentType.Orchestrator,
        skills = Set("planning"),
        provider = "mock",
        config = Map.empty
      )
      val provider = new MockLLMProvider(Map(
        "plan" ->
          """{
            "steps": [
              {"id": "step-1", "description": "OK step", "requiredSkills": ["ok"], "targetAgent": "ok", "dependencies": []},
              {"id": "step-2", "description": "Fail step", "requiredSkills": ["fail"], "targetAgent": "fail", "dependencies": []}
            ],
            "strategy": "parallel"
          }"""
      ))
      val registry = AgentRegistry()(using system, system.executionContext)

      val okAgent = spawnEchoAgent("ok", "OK")
      registry.register(okAgent, AgentCapability("ok", AgentType.Worker, Set("ok"), "test", Map.empty))

      val failAgent = spawn(Behaviors.receiveMessage[BaseAgent.Command] {
        case BaseAgent.ProcessMessage(_, ctx, replyTo) =>
          replyTo.asInstanceOf[ActorRef[BaseAgent.Response]] ! BaseAgent.ProcessingFailed("Simulated failure", ctx.id)
          Behaviors.same
        case _ => Behaviors.same
      }, "fail-agent")
      registry.register(failAgent, AgentCapability("fail", AgentType.Worker, Set("fail"), "test", Map.empty))

      val agent = spawn(LLMAgent(capability, provider, registry, Some(uiProbe.ref), planningEnabled = true))

      val msg = Message(role = MessageRole.User, content = MessageContent("Run parallel"), conversationId = "c-par")
      val ctx = ConversationContext("c-par")

      agent ! BaseAgent.ProcessMessage(msg, ctx, replies.ref.unsafeUpcast[Any])

      var sawError = false
      var sawAggregate = false

      uiProbe.fishForMessage(10.seconds) {
        case UiEventBus.Publish(ev) =>
          ev match {
            case UiEventBus.ErrorEvent("c-par", m) if m.toLowerCase.contains("step") || m.toLowerCase.contains("failure") =>
              sawError = true
            case UiEventBus.AggregateCompleted("c-par", _) =>
              sawAggregate = true
            case _ =>
          }
          if (sawError && sawAggregate)
            pekko.actor.testkit.typed.FishingOutcome.Complete
          else pekko.actor.testkit.typed.FishingOutcome.Continue
      }

      val finalResp = replies.expectMessageType[BaseAgent.ProcessedMessage](10.seconds)
      finalResp.message.content.text.toLowerCase should include ("ok step")
      // It may omit failed step content; ensure we still delivered a result
      finalResp.message.content.text.length should be > 0
    }
  }
}
