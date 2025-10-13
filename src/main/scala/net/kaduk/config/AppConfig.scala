package net.kaduk.config

import com.typesafe.config.{Config, ConfigFactory}
import scala.jdk.CollectionConverters.*

case class LLMProviderConfig(
  apiKey: String,
  model: String,
  maxTokens: Option[Int] = None,
  temperature: Option[Double] = None
)

case class AgentConfig(
  agentType: String,
  provider: String,
  systemPrompt: String,
  name: Option[String] = None,
  skills: Set[String] = Set.empty,
  capability: Option[String] = None
)

case class AppConfig(
  llmProviders: Map[String, LLMProviderConfig],
  agents: Map[String, AgentConfig]
)

object AppConfig:
  def load(): AppConfig =
    val config = ConfigFactory.load()
    
    val llmProviders = config.getConfig("agents.llm-providers")
      .root()
      .keySet()
      .asScala
      .map: key =>
        val providerConfig = config.getConfig(s"agents.llm-providers.$key")
        key -> LLMProviderConfig(
          apiKey = providerConfig.getString("api-key"),
          model = providerConfig.getString("model"),
          maxTokens = if providerConfig.hasPath("max-tokens") then Some(providerConfig.getInt("max-tokens")) else None,
          temperature = if providerConfig.hasPath("temperature") then Some(providerConfig.getDouble("temperature")) else None
        )
      .toMap
    
    val agents = config.getConfig("agents.agents")
      .root()
      .keySet()
      .asScala
      .map: key =>
        val agentConfig = config.getConfig(s"agents.agents.$key")
        key -> AgentConfig(
          agentType = agentConfig.getString("type"),
          provider = if agentConfig.hasPath("provider") then agentConfig.getString("provider") else "",
          systemPrompt = if agentConfig.hasPath("system-prompt") then agentConfig.getString("system-prompt") else "",
          name = if agentConfig.hasPath("name") then Some(agentConfig.getString("name")) else None,
          skills = if agentConfig.hasPath("skills") then agentConfig.getStringList("skills").asScala.toSet else Set.empty,
          capability = if agentConfig.hasPath("capability") then Some(agentConfig.getString("capability")) else None
        )
      .toMap
    
    AppConfig(llmProviders, agents)
