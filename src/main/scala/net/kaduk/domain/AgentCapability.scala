package net.kaduk.domain

enum AgentType:
  case LLM, Tool, Coordinator, Human, Worker, Specialist, Orchestrator, Monitor

case class AgentCapability(
  name: String,
  agentType: AgentType,
  skills: Set[String],
  provider: String,
  config: Map[String, String] = Map.empty
)

case class AgentMetadata(
  id: String,
  capability: AgentCapability,
  status: AgentStatus,
  currentLoad: Int = 0
)

enum AgentStatus:
  case Idle, Busy, Offline, Failed
