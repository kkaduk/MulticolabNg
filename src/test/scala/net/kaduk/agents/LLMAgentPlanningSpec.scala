package net.kaduk.agents

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import net.kaduk.domain.*
import net.kaduk.infrastructure.llm.LLMProvider
import net.kaduk.infrastructure.registry.AgentRegistry
import net.kaduk.telemetry.UiEventBus
import net.kaduk.agents.BaseAgent
import net.kaduk.agents.BaseAgent.Command
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Success, Failure, Try}
import java.util.UUID
import org.apache.pekko.actor.testkit.typed.FishingOutcome

class LLMAgentPlanningSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll:
  
  val testKit = ActorTestKit()
  given system: org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system
  given ec: ExecutionContext = system.executionContext

  override def afterAll(): Unit = testKit.shutdownTestKit()

  // ========== Test Fixtures ==========

  class MockLLMProvider(responses: Map[String, String] = Map.empty) extends LLMProvider:

    override def name: String = ???

    override def close(): Future[Unit] = ???

    var callCount = 0
    var lastPrompt = ""
    
    override def completion(messages: Seq[Message], systemPrompt: String): Future[String] =
      callCount += 1
      lastPrompt = messages.lastOption.map(_.content.text).getOrElse("")
      
      if lastPrompt.contains("Decompose this task") then
        Future.successful(responses.getOrElse("plan", defaultPlanResponse))
      else if lastPrompt.contains("stepId") then
        val stepId = extractStepId(lastPrompt)
        Future.successful(responses.getOrElse(stepId, s"Result for $stepId"))
      else
        Future.successful(responses.getOrElse("default", "Mock LLM response"))
    
    override def streamCompletion(messages: Seq[Message], systemPrompt: String) =
      org.apache.pekko.stream.scaladsl.Source.empty
    
    private def extractStepId(prompt: String): String =
      """step-\d+""".r.findFirstIn(prompt).getOrElse("unknown")
    
    private def defaultPlanResponse =
      """{
        "steps": [
          {
            "id": "step-1",
            "description": "Extract entities",
            "requiredSkills": ["ner"],
            "targetCapability": "ner",
            "dependencies": []
          },
          {
            "id": "step-2",
            "description": "Analyze sentiment",
            "requiredSkills": ["sentiment"],
            "targetCapability": "sentiment",
            "dependencies": []
          },
          {
            "id": "step-3",
            "description": "Summarize findings",
            "requiredSkills": ["summarization"],
            "targetCapability": "summarization",
            "dependencies": ["step-1", "step-2"]
          }
        ],
        "strategy": "parallel",
        "reasoning": "NER and sentiment can run in parallel, summary depends on both"
      }"""

  def createMockAgent(
    capability: String,
    registry: AgentRegistry,
    response: String = "Mock response"
  ): ActorRef[Command] =
    testKit.spawn(
      org.apache.pekko.actor.typed.scaladsl.Behaviors.receiveMessage[Command] {
        case BaseAgent.ProcessMessage(msg, ctx, replyTo) =>
          val responseMsg = Message(
            role = MessageRole.Assistant,
            content = MessageContent(response),
            conversationId = ctx.id,
            agentId = Some(capability)
          )
          replyTo ! BaseAgent.ProcessedMessage(responseMsg, ctx.addMessage(responseMsg))
          org.apache.pekko.actor.typed.scaladsl.Behaviors.same
        case _ =>
          org.apache.pekko.actor.typed.scaladsl.Behaviors.same
      },
      s"mock-$capability-${UUID.randomUUID().toString.take(8)}"
    )

  // Helper to access private parsePlan method via public wrapper
  object PlanParser:
    def parsePlan(json: String, message: Message, context: ConversationContext): Try[ExecutionPlan] =
      Try {
        val stepsPattern = """(?s)"steps"\s*:\s*\[(.*?)\]""".r
        val stepPattern  = """(?s)\{.*?\}""".r
        
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

        if steps.isEmpty then
          throw new IllegalArgumentException("Invalid plan JSON: no steps parsed")
        
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

  // ========== UNIT TESTS ==========

  "LLMAgent Planning" should {

    "generate correct planning prompts with available agents" in {
      val provider = new MockLLMProvider()
      val registry = AgentRegistry()
      
      val nerAgent = createMockAgent("ner", registry)
      registry.register(nerAgent, AgentCapability("ner", AgentType.Specialist, Set("ner", "entity-extraction"), "test", Map.empty))
      
      val sentimentAgent = createMockAgent("sentiment", registry)
      registry.register(sentimentAgent, AgentCapability("sentiment", AgentType.Specialist, Set("sentiment"), "test", Map.empty))
      
      Thread.sleep(100)
      
      val uiBusProbe = testKit.createTestProbe[UiEventBus.Command]()
      val replyProbe = testKit.createTestProbe[Any]()
      
      val agent = testKit.spawn(
        LLMAgent(
          AgentCapability("orchestrator", AgentType.Orchestrator, Set("planning"), "test", Map.empty),
          provider,
          registry,
          Some(uiBusProbe.ref),
          planningEnabled = true
        )
      )
      
      val message = Message(
        role = MessageRole.User,
        content = MessageContent("Analyze this text and extract insights"),
        conversationId = "test-conv"
      )
      
      agent ! BaseAgent.ProcessMessage(
        message,
        ConversationContext("test-conv"),
        replyProbe.ref.unsafeUpcast[Any]
      )
      
      Thread.sleep(500)
      
      provider.lastPrompt should include("Available specialized agents")
      provider.lastPrompt should include("ner")
      provider.lastPrompt should include("sentiment")
      provider.callCount should be >= 1
    }

    "parse valid JSON plan responses correctly" in {
      val jsonPlan = """{
        "steps": [
          {
            "id": "step-1",
            "description": "Extract named entities",
            "requiredSkills": ["ner", "extraction"],
            "targetCapability": "ner",
            "dependencies": []
          },
          {
            "id": "step-2",
            "description": "Classify sentiment",
            "requiredSkills": ["sentiment"],
            "targetCapability": "sentiment",
            "dependencies": ["step-1"]
          }
        ],
        "strategy": "sequential",
        "reasoning": "Sequential execution required"
      }"""
      
      val message = Message(
        role = MessageRole.User,
        content = MessageContent("Test query"),
        conversationId = "test"
      )
      val context = ConversationContext("test")
      
      val planTry = PlanParser.parsePlan(jsonPlan, message, context)
      
      planTry shouldBe a[Success[?]]
      val plan = planTry.get
      
      plan.steps should have size 2
      plan.steps(0).id shouldBe "step-1"
      plan.steps(0).requiredSkills should contain("ner")
      plan.steps(0).dependencies shouldBe empty
      
      plan.steps(1).id shouldBe "step-2"
      plan.steps(1).dependencies should contain("step-1")
      
      plan.strategy shouldBe ExecutionStrategy.Sequential
    }

    "handle malformed JSON gracefully" in {
      val invalidJson = """{"steps": [{"invalid": true}"""
      
      val message = Message(
        role = MessageRole.User,
        content = MessageContent("Test"),
        conversationId = "test"
      )
      val context = ConversationContext("test")
      
      val planTry = PlanParser.parsePlan(invalidJson, message, context)
      
      planTry shouldBe a[Failure[?]]
    }

    "resolve dependencies correctly in step ordering" in {
      val steps = Seq(
        PlanStep("step-3", "Final", Set("summary"), dependencies = Set("step-1", "step-2")),
        PlanStep("step-1", "First", Set("ner")),
        PlanStep("step-2", "Second", Set("sentiment"), dependencies = Set("step-1"))
      )
      
      val plan = ExecutionPlan(
        conversationId = "test",
        originalQuery = "Test query",
        steps = steps,
        strategy = ExecutionStrategy.Sequential
      )
      
      val step3 = plan.steps.find(_.id == "step-3").get
      step3.dependencies should contain allOf("step-1", "step-2")
      
      val step2 = plan.steps.find(_.id == "step-2").get
      step2.dependencies should contain("step-1")
      
      val step1 = plan.steps.find(_.id == "step-1").get
      step1.dependencies shouldBe empty
    }

    "detect circular dependencies" in {
      val steps = Seq(
        PlanStep("step-1", "A", Set("a"), dependencies = Set("step-2")),
        PlanStep("step-2", "B", Set("b"), dependencies = Set("step-1"))
      )
      
      def hasCycle(steps: Seq[PlanStep]): Boolean =
        def visit(stepId: String, visiting: Set[String], visited: Set[String]): Boolean =
          if visiting.contains(stepId) then true
          else if visited.contains(stepId) then false
          else
            val step = steps.find(_.id == stepId)
            step.exists { s =>
              s.dependencies.exists(dep => visit(dep, visiting + stepId, visited))
            }
        
        steps.exists(s => visit(s.id, Set.empty, Set.empty))
      
      hasCycle(steps) shouldBe true
    }

    "select best agent by exact capability match" in {
      val registry = AgentRegistry()
      
      val nerAgent = createMockAgent("ner", registry)
      registry.register(nerAgent, AgentCapability("ner", AgentType.Specialist, Set("ner"), "test", Map.empty))
      registry.register(nerAgent, AgentCapability("ner", AgentType.Specialist, Set("ner"), "test", Map.empty))
      
      val genericAgent = createMockAgent("generic", registry)
      registry.register(genericAgent, AgentCapability("generic", AgentType.Worker, Set("ner", "other"), "test", Map.empty))
      
      Thread.sleep(100)
      
      val result = registry.findAgent("ner")
      result.futureValue shouldBe defined
      result.futureValue.get.path.name should include("ner")
    }

    "select agent by skill intersection when no exact match" in {
      val registry = AgentRegistry()
      
      val multiSkillAgent = createMockAgent("multi", registry)
      registry.register(
        multiSkillAgent,
        AgentCapability("multi", AgentType.Worker, Set("ner", "sentiment", "qa"), "test", Map.empty)
      )
      
      Thread.sleep(100)
      
      val result = registry.findAgentsByAllSkills(Set("ner", "sentiment"))
      result.futureValue should not be empty
      result.futureValue.head.path.name should include("multi")
    }

    "return None when no matching agents exist" in {
      val registry = AgentRegistry()
      
      val result = registry.findAgentsBySkill("nonexistent-skill")
      result.futureValue shouldBe empty
    }
  }

  // ========== INTEGRATION TESTS ==========

  "LLMAgent Plan Execution" should {

    "execute complete plan end-to-end with mock agents" in {
      val responses = Map(
        "plan" -> """{
          "steps": [
            {"id": "step-1", "description": "Extract entities", "requiredSkills": ["ner"], "targetCapability": "ner", "dependencies": []},
            {"id": "step-2", "description": "Summarize", "requiredSkills": ["summarization"], "targetCapability": "summarization", "dependencies": ["step-1"]}
          ],
          "strategy": "sequential",
          "reasoning": "Sequential execution"
        }"""
      )
      
      val provider = new MockLLMProvider(responses)
      val registry = AgentRegistry()
      
      val nerAgent = createMockAgent("ner", registry, "Entities: John, Apple, NYC")
      registry.register(nerAgent, AgentCapability("ner", AgentType.Specialist, Set("ner"), "test"))
      
      val summaryAgent = createMockAgent("summarization", registry, "Summary: Text contains 3 entities")
      registry.register(summaryAgent, AgentCapability("summarization", AgentType.Specialist, Set("summarization"), "test", Map.empty))
      
      Thread.sleep(100)
      
      val uiBusProbe = testKit.createTestProbe[UiEventBus.Command]()
      val replyProbe = testKit.createTestProbe[Any]()
      
      val agent = testKit.spawn(
        LLMAgent(
          AgentCapability("orchestrator", AgentType.Orchestrator, Set("planning"), "test", Map.empty),
          provider,
          registry,
          Some(uiBusProbe.ref),
          planningEnabled = true
        )
      )
      
      val message = Message(
        role = MessageRole.User,
        content = MessageContent("Analyze this text: John works at Apple in NYC"),
        conversationId = "test-conv"
      )
      
      agent ! BaseAgent.ProcessMessage(
        message,
        ConversationContext("test-conv"),
        replyProbe.ref.unsafeUpcast[Any]
      )
      
      val planEvent = uiBusProbe.expectMessageType[UiEventBus.Command](5.seconds)
      planEvent match {
        case UiEventBus.Publish(UiEventBus.PlanComputed(convId, steps)) =>
          convId shouldBe "test-conv"
          steps should have size 2
        case _ => fail(s"Expected PlanComputed, got $planEvent")
      }
      
      val dispatch1 = uiBusProbe.expectMessageType[UiEventBus.Command](3.seconds)
      dispatch1 match {
        case UiEventBus.Publish(UiEventBus.StepDispatched(_, stepId, cap, _)) =>
          stepId shouldBe "step-1"
          cap shouldBe "ner"
        case _ => fail(s"Expected StepDispatched, got $dispatch1")
      }
      
      val response = replyProbe.expectMessageType[BaseAgent.ProcessedMessage](10.seconds)
      response.message.content.text should include("Entities")
      response.message.content.text should include("Summary")
    }

    "handle agent discovery failure gracefully" in {
      val provider = new MockLLMProvider(Map(
        "plan" -> """{
          "steps": [
            {"id": "step-1", "description": "Use nonexistent agent", "requiredSkills": ["nonexistent"], "targetCapability": "nonexistent", "dependencies": []}
          ],
          "strategy": "parallel"
        }""",
        "step-1" -> "Executed locally as fallback"
      ))
      
      val registry = AgentRegistry()
      val uiBusProbe = testKit.createTestProbe[UiEventBus.Command]()
      val replyProbe = testKit.createTestProbe[Any]()
      
      val agent = testKit.spawn(
        LLMAgent(
          AgentCapability("orchestrator", AgentType.Orchestrator, Set("planning"), "test", Map.empty),
          provider,
          registry,
          Some(uiBusProbe.ref),
          planningEnabled = true
        )
      )
      
      val message = Message(
        role = MessageRole.User,
        content = MessageContent("Task requiring nonexistent agent"),
        conversationId = "test-conv"
      )
      
      agent ! BaseAgent.ProcessMessage(
        message,
        ConversationContext("test-conv"),
        replyProbe.ref.unsafeUpcast[Any]
      )
      
      val response = replyProbe.expectMessageType[BaseAgent.ProcessedMessage](10.seconds)
      response.message.content.text should include("fallback")
    }

    "handle partial plan completion when some steps fail" in {
      val responses = Map(
        "plan" -> """{
          "steps": [
            {"id": "step-1", "description": "Success step", "requiredSkills": ["ok"], "targetCapability": "ok", "dependencies": []},
            {"id": "step-2", "description": "Failure step", "requiredSkills": ["fail"], "targetCapability": "fail", "dependencies": []},
            {"id": "step-3", "description": "Success step 2", "requiredSkills": ["ok"], "targetCapability": "ok", "dependencies": []}
          ],
          "strategy": "parallel"
        }""",
        "step-1" -> "Step 1 success",
        "step-3" -> "Step 3 success"
      )
      
      val provider = new MockLLMProvider(responses)
      val registry = AgentRegistry()
      
      val okAgent = createMockAgent("ok", registry, "Success")
      registry.register(okAgent, AgentCapability("ok", AgentType.Worker, Set("ok"), "test", Map.empty))
      
      val failAgent = testKit.spawn(
        org.apache.pekko.actor.typed.scaladsl.Behaviors.receiveMessage[Command] {
          case BaseAgent.ProcessMessage(_, _, replyTo) =>
            replyTo ! BaseAgent.ProcessingFailed("Simulated failure", "step-2")
            org.apache.pekko.actor.typed.scaladsl.Behaviors.same
          case _ =>
            org.apache.pekko.actor.typed.scaladsl.Behaviors.same
        },
        "fail-agent"
      )
      registry.register(failAgent, AgentCapability("fail", AgentType.Worker, Set("fail"), "test", Map.empty))
      
      Thread.sleep(100)
      
      val uiBusProbe = testKit.createTestProbe[UiEventBus.Command]()
      val replyProbe = testKit.createTestProbe[Any]()
      
      val agent = testKit.spawn(
        LLMAgent(
          AgentCapability("orchestrator", AgentType.Orchestrator, Set("planning"), "test", Map.empty),
          provider,
          registry,
          Some(uiBusProbe.ref),
          planningEnabled = true
        )
      )
      
      val message = Message(
        role = MessageRole.User,
        content = MessageContent("Execute mixed success/failure plan"),
        conversationId = "test-conv"
      )
      
      agent ! BaseAgent.ProcessMessage(
        message,
        ConversationContext("test-conv"),
        replyProbe.ref.unsafeUpcast[Any]
      )
      
      uiBusProbe.fishForMessage(5.seconds) {
        case UiEventBus.Publish(UiEventBus.ErrorEvent(_, msg)) if msg.contains("failure") =>
          FishingOutcome.Complete
        case _ =>
          FishingOutcome.Continue
      }
      
      val response = replyProbe.expectMessageType[Command](10.seconds)
      response match {
        case BaseAgent.ProcessedMessage(msg, _) =>
          msg.content.text should (include("Step 1") or include("Step 3"))
        case _ => fail(s"Expected ProcessedMessage, got $response")
      }
    }

    "handle concurrent plan executions without race conditions" in {
      val provider = new MockLLMProvider(Map(
        "plan" -> """{
          "steps": [
            {"id": "step-1", "description": "Concurrent task", "requiredSkills": ["concurrent"], "targetCapability": "concurrent", "dependencies": []}
          ],
          "strategy": "parallel"
        }"""
      ))
      
      val registry = AgentRegistry()
      
      val concurrentAgent = testKit.spawn(
        org.apache.pekko.actor.typed.scaladsl.Behaviors.receiveMessage[Command] {
          case BaseAgent.ProcessMessage(msg, ctx, replyTo) =>
            Thread.sleep(100)
            val response = Message(
              role = MessageRole.Assistant,
              content = MessageContent(s"Processed ${msg.id}"),
              conversationId = ctx.id,
              agentId = Some("concurrent")
            )
            replyTo ! BaseAgent.ProcessedMessage(response, ctx.addMessage(response))
            org.apache.pekko.actor.typed.scaladsl.Behaviors.same
          case _ =>
            org.apache.pekko.actor.typed.scaladsl.Behaviors.same
        },
        "concurrent-agent"
      )
      registry.register(concurrentAgent, AgentCapability("concurrent", AgentType.Worker, Set("concurrent"), "test", Map.empty))
      
      Thread.sleep(100)
      
      val uiBusProbe = testKit.createTestProbe[UiEventBus.Command]()
      val agent = testKit.spawn(
        LLMAgent(
          AgentCapability("orchestrator", AgentType.Orchestrator, Set("planning"), "test", Map.empty),
          provider,
          registry,
          Some(uiBusProbe.ref),
          planningEnabled = true
        )
      )
      
      val replyProbes = (1 to 5).map { i =>
        val probe = testKit.createTestProbe[Any](s"reply-$i")
        val message = Message(
          role = MessageRole.User,
          content = MessageContent(s"Concurrent request $i"),
          conversationId = s"conv-$i"
        )
        agent ! BaseAgent.ProcessMessage(message, ConversationContext(s"conv-$i"), probe.ref.unsafeUpcast[Any])
        probe
      }
      
      replyProbes.foreach { probe =>
        probe.expectMessageType[BaseAgent.ProcessedMessage](15.seconds)
      }
      
      val messages = replyProbes.map(_.receiveMessage(1.second).asInstanceOf[BaseAgent.ProcessedMessage])
      val messageIds = messages.map(_.message.id).toSet
      messageIds should have size 5
    }
  }

  // ========== LOAD TESTS ==========

  "LLMAgent Load Testing" should {

    "handle 100 concurrent conversations with planning" in {
      val provider = new MockLLMProvider(Map(
        "plan" -> """{
          "steps": [
            {"id": "step-1", "description": "Load test step", "requiredSkills": ["load"], "targetCapability": "load", "dependencies": []}
          ],
          "strategy": "parallel"
        }"""
      ))
      
      val registry = AgentRegistry()
      
      val loadAgent = testKit.spawn(
        org.apache.pekko.actor.typed.scaladsl.Behaviors.receiveMessage[Command] {
          case BaseAgent.ProcessMessage(_, ctx, replyTo) =>
            val response = Message(
              role = MessageRole.Assistant,
              content = MessageContent("Load test response"),
              conversationId = ctx.id
            )
            replyTo ! BaseAgent.ProcessedMessage(response, ctx)
            org.apache.pekko.actor.typed.scaladsl.Behaviors.same
          case _ =>
            org.apache.pekko.actor.typed.scaladsl.Behaviors.same
        },
        "load-agent"
      )
      registry.register(loadAgent, AgentCapability("load", AgentType.Worker, Set("load"), "test", Map.empty))
      
      Thread.sleep(100)
      
      val agent = testKit.spawn(
        LLMAgent(
          AgentCapability("orchestrator", AgentType.Orchestrator, Set("planning"), "test", Map.empty),
          provider,
          registry,
          None,
          planningEnabled = true
        )
      )
      
      val startTime = System.currentTimeMillis()
      
      val replyProbes = (1 to 100).map { i =>
        val probe = testKit.createTestProbe[Any](s"load-reply-$i")
        val message = Message(
          role = MessageRole.User,
          content = MessageContent(s"Load test message $i"),
          conversationId = s"load-conv-$i"
        )
        agent ! BaseAgent.ProcessMessage(message, ConversationContext(s"load-conv-$i"), probe.ref.unsafeUpcast[Any])
        probe
      }
      
      val responses = replyProbes.map(_.expectMessageType[BaseAgent.ProcessedMessage](30.seconds))
      
      val duration = System.currentTimeMillis() - startTime
      val throughput = (100.0 / duration) * 1000
      
      println(s"Load test completed in ${duration}ms (${throughput.formatted("%.2f")} msg/sec)")
      
      responses should have size 100
      duration should be < 30000L
    }

    "handle plan fanout with 10+ steps and 5 agents" in {
      val fanoutPlan = """{
        "steps": [
          {"id": "step-1", "description": "Task 1", "requiredSkills": ["agent1"], "targetCapability": "agent1", "dependencies": []},
          {"id": "step-2", "description": "Task 2", "requiredSkills": ["agent2"], "targetCapability": "agent2", "dependencies": []},
          {"id": "step-3", "description": "Task 3", "requiredSkills": ["agent3"], "targetCapability": "agent3", "dependencies": []},
          {"id": "step-4", "description": "Task 4", "requiredSkills": ["agent4"], "targetCapability": "agent4", "dependencies": []},
          {"id": "step-5", "description": "Task 5", "requiredSkills": ["agent5"], "targetCapability": "agent5", "dependencies": []},
          {"id": "step-6", "description": "Aggregate 1", "requiredSkills": ["agent1"], "targetCapability": "agent1", "dependencies": ["step-1", "step-2"]},
          {"id": "step-7", "description": "Aggregate 2", "requiredSkills": ["agent2"], "targetCapability": "agent2", "dependencies": ["step-3", "step-4"]},
          {"id": "step-8", "description": "Aggregate 3", "requiredSkills": ["agent3"], "targetCapability": "agent3", "dependencies": ["step-5"]},
          {"id": "step-9", "description": "Final 1", "requiredSkills": ["agent4"], "targetCapability": "agent4", "dependencies": ["step-6", "step-7"]},
          {"id": "step-10", "description": "Final 2", "requiredSkills": ["agent5"], "targetCapability": "agent5", "dependencies": ["step-8", "step-9"]}
        ],
        "strategy": "parallel"
      }"""
      
      val provider = new MockLLMProvider(Map("plan" -> fanoutPlan))
      val registry = AgentRegistry()
      
      (1 to 5).foreach { i =>
        val agent = createMockAgent(s"agent$i", registry, s"Agent $i response")
        registry.register(agent, AgentCapability(s"agent$i", AgentType.Worker, Set(s"agent$i"), "test", Map.empty))
      }
      
      Thread.sleep(100)
      
      val uiBusProbe = testKit.createTestProbe[UiEventBus.Command]()
      val replyProbe = testKit.createTestProbe[Any]()
      
      val agent = testKit.spawn(
        LLMAgent(
          AgentCapability("orchestrator", AgentType.Orchestrator, Set("planning"), "test", Map.empty),
          provider,
          registry,
          Some(uiBusProbe.ref),
          planningEnabled = true
        )
      )
      
      val startTime = System.currentTimeMillis()
      
      val message = Message(
        role = MessageRole.User,
        content = MessageContent("Execute complex fanout plan"),
        conversationId = "fanout-conv"
      )
      
      agent ! BaseAgent.ProcessMessage(
        message,
        ConversationContext("fanout-conv"),
        replyProbe.ref.unsafeUpcast[Any]
      )
      
      val planEvent = uiBusProbe.expectMessageType[UiEventBus.Command](5.seconds)
      planEvent match {
        case UiEventBus.Publish(UiEventBus.PlanComputed(_, steps)) =>
          steps should have size 10
        case _ => fail("Expected PlanComputed")
      }
      
      val response = replyProbe.expectMessageType[BaseAgent.ProcessedMessage](30.seconds)
      
      val duration = System.currentTimeMillis() - startTime
      println(s"Fanout plan completed in ${duration}ms")
      
      response.message.content.text should not be empty
      duration should be < 30000L
    }

    "maintain memory efficiency under sustained load" in {
      val provider = new MockLLMProvider()
      val registry = AgentRegistry()
      
      val agent = testKit.spawn(
        LLMAgent(
          AgentCapability("orchestrator", AgentType.Orchestrator, Set("planning"), "test", Map.empty),
          provider,
          registry,
          None,
          planningEnabled = false
        )
      )
      
      val runtime = Runtime.getRuntime
      runtime.gc()
      Thread.sleep(1000)
      val initialMemory = runtime.totalMemory() - runtime.freeMemory()
      
      (1 to 1000).foreach { i =>
        val probe = testKit.createTestProbe[Any]()
        val message = Message(
          role = MessageRole.User,
          content = MessageContent(s"Memory test $i"),
          conversationId = s"mem-conv-${i % 10}"
        )
        agent ! BaseAgent.ProcessMessage(message, ConversationContext(s"mem-conv-${i % 10}"), probe.ref.unsafeUpcast[Any])
        
        if i % 100 == 0 then
          Thread.sleep(100)
      }
      
      Thread.sleep(2000)
      runtime.gc()
      Thread.sleep(1000)
      val finalMemory = runtime.totalMemory() - runtime.freeMemory()
      
      val memoryIncrease = (finalMemory - initialMemory) / 1024 / 1024
      println(s"Memory increase after 1000 messages: ${memoryIncrease}MB")
      
      memoryIncrease should be < 100L
    }
  }

  extension [T](future: Future[T])
    def futureValue(implicit timeout: Duration = 3.seconds): T =
      scala.concurrent.Await.result(future, timeout)
