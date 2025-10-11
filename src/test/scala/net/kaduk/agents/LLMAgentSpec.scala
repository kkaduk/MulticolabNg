package net.kaduk.agents

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import net.kaduk.domain.*
import net.kaduk.infrastructure.llm.LLMProvider
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
      val agent = spawn(LLMAgent(capability, provider))
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
  }
