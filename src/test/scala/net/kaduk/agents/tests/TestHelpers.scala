package net.kaduk.agents.test

import net.kaduk.domain.*
import net.kaduk.infrastructure.llm.LLMProvider
import scala.concurrent.{ExecutionContext, Future}
import org.apache.pekko.stream.scaladsl.Source

object TestHelpers:

  /** Configurable mock provider with response delays */
  class DelayedMockProvider(
    responses: Map[String, String],
    delay: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.Duration.Zero
  )(using ec: ExecutionContext) extends LLMProvider:
    
    override def completion(messages: Seq[Message], systemPrompt: String): Future[String] =
      Future {
        Thread.sleep(delay.toMillis)
        val lastMsg = messages.lastOption.map(_.content.text).getOrElse("")
        responses.getOrElse(lastMsg, "Default response")
      }
    
    override def streamCompletion(messages: Seq[Message], systemPrompt: String) =
      Source.single(StreamToken("test", "msg-1"))

  /** Builder for complex test plans */
  class PlanBuilder:
    private var steps = Seq.empty[PlanStep]
    
    def addStep(
      id: String,
      description: String,
      skills: Set[String],
      capability: Option[String] = None,
      dependencies: Set[String] = Set.empty
    ): PlanBuilder =
      steps = steps :+ PlanStep(id, description, skills, dependencies, capability)
      this
    
    def withParallelStrategy: PlanBuilder =
      this // Strategy set on build
    
    def build(conversationId: String, query: String): ExecutionPlan =
      ExecutionPlan(
        conversationId = conversationId,
        originalQuery = query,
        steps = steps,
        strategy = ExecutionStrategy.Parallel
      )
  
  /** Assertion helpers */
  def assertPlanValid(plan: ExecutionPlan): Unit =
    assert(plan.steps.nonEmpty, "Plan must have steps")
    assert(plan.steps.map(_.id).distinct.size == plan.steps.size, "Step IDs must be unique")
    
    // Check dependency references
    val stepIds = plan.steps.map(_.id).toSet
    plan.steps.foreach { step =>
      assert(
        step.dependencies.subsetOf(stepIds),
        s"Step ${step.id} has invalid dependencies: ${step.dependencies -- stepIds}"
      )
    }
  
  def assertNoCycles(plan: ExecutionPlan): Unit =
    def visit(stepId: String, visiting: Set[String], visited: Set[String]): Boolean =
      if visiting.contains(stepId) then true
      else if visited.contains(stepId) then false
      else
        val step = plan.steps.find(_.id == stepId).get
        step.dependencies.exists(dep => visit(dep, visiting + stepId, visited))
    
    val hasCycle = plan.steps.exists(s => visit(s.id, Set.empty, Set.empty))
    assert(!hasCycle, "Plan contains circular dependencies")