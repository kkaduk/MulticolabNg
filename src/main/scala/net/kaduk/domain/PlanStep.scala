package net.kaduk.domain

import java.time.Instant
import java.util.UUID

/** Represents a decomposed task step in an execution plan */
case class PlanStep(
  id: String = UUID.randomUUID().toString,
  description: String,
  requiredSkills: Set[String],
  dependencies: Set[String] = Set.empty, // Step IDs that must complete first
  targetCapability: Option[String] = None, // Preferred agent capability
  status: StepStatus = StepStatus.Pending,
  assignedAgent: Option[String] = None, // Agent actor path
  result: Option[String] = None,
  error: Option[String] = None,
  createdAt: Instant = Instant.now()
)

enum StepStatus:
  case Pending, Running, Completed, Failed, Skipped

/** Execution plan for a complex task */
case class ExecutionPlan(
  id: String = UUID.randomUUID().toString,
  conversationId: String,
  originalQuery: String,
  steps: Seq[PlanStep],
  strategy: ExecutionStrategy = ExecutionStrategy.Parallel,
  maxParallelism: Int = 3,
  createdAt: Instant = Instant.now()
)

enum ExecutionStrategy:
  case Sequential, Parallel, Adaptive

/** Planning request/response protocol */
case class PlanningRequest(
  query: String,
  context: ConversationContext,
  availableAgents: Map[String, Set[String]] // capability -> skills
)

case class PlanningResponse(
  plan: ExecutionPlan,
  reasoning: String,
  confidence: Double
)