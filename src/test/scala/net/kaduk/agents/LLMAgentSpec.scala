package net.kaduk.agents

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import net.kaduk.domain.*
import net.kaduk.infrastructure.llm.LLMProvider
import net.kaduk.infrastructure.registry.AgentRegistry
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.NotUsed
import scala.concurrent.Future

class LLMAgentSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike:

  given scala.concurrent.ExecutionContext = system.executionContext

  class MockLLMProvider extends LLMProvider:
    override def name: String = "mock"
    
    override def streamCompletion(messages: Seq[Message], systemPrompt: String): Source[StreamToken, NotUsed] =
      Source.single(StreamToken("Mock response", "test-id", isComplete = true))
    
    override def completion(messages: Seq[Message], systemPrompt: String): Future[String] =
      val last = messages.lastOption.map(_.content.text).getOrElse("")
      if last.contains("Decompose this task") then
        Future.successful("""{"steps":[{"id":"step-1","description":"Do X","requiredSkills":["x"],"targetAgent":"x","dependencies":[]}],"strategy":"sequential"}""")
      else if last.contains("stepId") then
        Future.successful("Step result")
      else
        Future.successful("Mock completion response")
    
    override def close(): Future[Unit] = Future.successful(())

  "LLMAgent" should {
    "process messages and return responses" in {
      val capability = AgentCapability(
        name = "test-agent",
        agentType = AgentType.LLM,
        skills = Set("text-generation"),
        provider = "mock"
      )
      
      val provider = MockLLMProvider()
      val registry = AgentRegistry()(using system, summon[scala.concurrent.ExecutionContext])
      val agent = spawn(LLMAgent(capability, provider, registry, None, planningEnabled = false))
      val probe = createTestProbe[BaseAgent.Response]()
      
      val message = Message(
        role = MessageRole.User,
        content = MessageContent("Hello"),
        conversationId = "test-conv"
      )
      
      val context = ConversationContext("test-conv")
      
      agent ! BaseAgent.ProcessMessage(message, context, probe.ref.unsafeUpcast[Any])
      
      probe.expectMessageType[BaseAgent.ProcessedMessage]
    }

    "return to idle after completing a message and accept the next task" in {
      val capability = AgentCapability(
        name = "test-agent",
        agentType = AgentType.LLM,
        skills = Set("text-generation"),
        provider = "mock"
      )

      val provider = MockLLMProvider()
      val registry = AgentRegistry()(using system, summon[scala.concurrent.ExecutionContext])
      val agent = spawn(LLMAgent(capability, provider, registry, None, planningEnabled = false))
      val probe = createTestProbe[BaseAgent.Response]()

      val ctx1 = ConversationContext("conv-1")
      val ctx2 = ConversationContext("conv-2")

      val msg1 = Message(role = MessageRole.User, content = MessageContent("First"), conversationId = ctx1.id)
      val msg2 = Message(role = MessageRole.User, content = MessageContent("Second"), conversationId = ctx2.id)

      agent ! BaseAgent.ProcessMessage(msg1, ctx1, probe.ref.unsafeUpcast[Any])
      probe.expectMessageType[BaseAgent.ProcessedMessage]

      // If the agent correctly returned to idle, it should process a new message right away
      agent ! BaseAgent.ProcessMessage(msg2, ctx2, probe.ref.unsafeUpcast[Any])
      probe.expectMessageType[BaseAgent.ProcessedMessage]
    }

    "handle streaming, then return to idle and process a follow-up task" in {
      val capability = AgentCapability(
        name = "test-agent",
        agentType = AgentType.LLM,
        skills = Set("text-generation"),
        provider = "mock"
      )

      val provider = MockLLMProvider()
      val registry = AgentRegistry()(using system, summon[scala.concurrent.ExecutionContext])
      val agent = spawn(LLMAgent(capability, provider, registry, None, planningEnabled = false))
      val probe = createTestProbe[BaseAgent.Response]()
      val sProbe = createTestProbe[BaseAgent.StreamResponse]()

      val ctxStream = ConversationContext("conv-stream")
      val ctxNext   = ConversationContext("conv-next")

      val streamMsg = Message(role = MessageRole.User, content = MessageContent("Stream please"), conversationId = ctxStream.id)
      agent ! BaseAgent.StreamMessage(streamMsg, ctxStream, sProbe.ref.unsafeUpcast[Any])

      // Our MockLLMProvider emits a single token; expect chunk, then completion
      val chunk = sProbe.expectMessageType[BaseAgent.StreamChunk]
      assert(chunk.content == "Mock response")
      sProbe.expectMessageType[BaseAgent.StreamComplete]

      // After streaming completes, the agent should be back to idle and accept another task
      val nextMsg = Message(role = MessageRole.User, content = MessageContent("Normal task"), conversationId = ctxNext.id)
      agent ! BaseAgent.ProcessMessage(nextMsg, ctxNext, probe.ref.unsafeUpcast[Any])
      probe.expectMessageType[BaseAgent.ProcessedMessage]
    }

    "stop transitions the agent to terminated state (cleanup path)" in {
      val capability = AgentCapability(
        name = "test-agent",
        agentType = AgentType.LLM,
        skills = Set("text-generation"),
        provider = "mock"
      )

      val provider = MockLLMProvider()
      val registry = AgentRegistry()(using system, summon[scala.concurrent.ExecutionContext])
      val agent = spawn(LLMAgent(capability, provider, registry, None, planningEnabled = false))
      val termProbe = createTestProbe[Nothing]()

      agent ! BaseAgent.Stop
      termProbe.expectTerminated(agent)
    }
  }
