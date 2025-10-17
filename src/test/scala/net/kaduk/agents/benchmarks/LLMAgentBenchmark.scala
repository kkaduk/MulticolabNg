package net.kaduk.agents.benchmarks

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.duration.*

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
class LLMAgentBenchmark:

  var testKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = _
  var agent: org.apache.pekko.actor.typed.ActorRef[BaseAgent.Command] = _
  var registry: AgentRegistry = _
  
  @Setup
  def setup(): Unit =
    testKit = org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit()
    given system = testKit.system
    given ec = system.executionContext
    
    registry = AgentRegistry()
    val provider = new MockLLMProvider()
    
    agent = testKit.spawn(
      LLMAgent(
        AgentCapability("bench", "Benchmark", Set("planning")),
        provider,
        registry,
        None,
        planningEnabled = false
      )
    )
  
  @TearDown
  def teardown(): Unit =
    testKit.shutdownTestKit()
  
  @Benchmark
  def directMessageProcessing(): Unit =
    val probe = testKit.createTestProbe[BaseAgent.Command]()
    val message = Message(
      role = MessageRole.User,
      content = MessageContent("Benchmark message"),
      conversationId = "bench-conv"
    )
    agent ! BaseAgent.ProcessMessage(message, ConversationContext("bench-conv"), probe.ref)
    probe.expectMessageType[BaseAgent.ProcessedMessage](5.seconds)
  
  @Benchmark
  def agentDiscovery(): Unit =
    given ec = testKit.system.executionContext
    val future = registry.findAgent("nonexistent")
    Await.result(future, 3.seconds)
  
  @Benchmark
  def planParsing(): Unit =
    val json = """{"steps":[{"id":"step-1","description":"test","requiredSkills":["test"],"dependencies":[]}],"strategy":"parallel"}"""
    val message = Message(MessageRole.User, MessageContent("test"), "test")
    LLMAgent.parsePlan(json, message, ConversationContext("test"))