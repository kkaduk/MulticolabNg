package net.kaduk

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import net.kaduk.agents.{CoordinatorAgent, LLMAgent}
import net.kaduk.infrastructure.llm.{OpenAIProvider, ClaudeProvider, OllamaProvider, VertexProvider}
import net.kaduk.infrastructure.registry.AgentRegistry
import net.kaduk.infrastructure.grpc.AgentServiceImpl
import net.kaduk.protobuf.agent_service.AgentServiceHandler
import net.kaduk.config.AppConfig
import net.kaduk.domain.{AgentCapability, AgentType}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure}

object MainApp:
  
  def main(args: Array[String]): Unit =
    val rootBehavior = Behaviors.setup[Nothing]: ctx =>
      given ActorSystem[Nothing] = ctx.system
      given ExecutionContext = ctx.executionContext
      
      val config = AppConfig.load()
      val registry = AgentRegistry()
      
      // Spawn LLM agents
      config.agents.foreach: (name, agentConfig) =>
        if agentConfig.agentType == "llm" then
          ctx.log.info(s"Spawning LLM agent: $name")
          
          val providerConfig = config.llmProviders(agentConfig.provider)
          val provider = agentConfig.provider match
            case "openai" => OpenAIProvider(providerConfig.apiKey, providerConfig.model)
            case "claude" => ClaudeProvider(providerConfig.apiKey, providerConfig.model)
            case "ollama" => OllamaProvider(config.llmProviders("ollama").apiKey, providerConfig.model)
            case "vertex" => VertexProvider(providerConfig.apiKey, "us-central1", providerConfig.model)
            case _ => throw new IllegalArgumentException(s"Unknown provider: ${agentConfig.provider}")
          
          val capability = AgentCapability(
            name = name,
            agentType = AgentType.LLM,
            skills = Set("text-generation"),
            provider = agentConfig.provider,
            config = Map("systemPrompt" -> agentConfig.systemPrompt)
          )
          
          val agentRef = ctx.spawn(LLMAgent(capability, provider), name)
          registry.register(agentRef, capability)
      
      // Spawn coordinator
      val coordinatorRef = ctx.spawn(CoordinatorAgent(registry), "coordinator")
      
      // Start gRPC server
      val service: HttpRequest => Future[HttpResponse] =
        AgentServiceHandler(AgentServiceImpl(coordinatorRef))
      
      Http().newServerAt("0.0.0.0", 6060).bind(service).onComplete:
        case Success(binding) =>
          ctx.system.log.info(s"gRPC server bound to ${binding.localAddress}")
        case Failure(ex) =>
          ctx.system.log.error("Failed to bind gRPC server", ex)
          ctx.system.terminate()
      
      Behaviors.empty
    
    ActorSystem[Nothing](rootBehavior, "AgentSystem")
