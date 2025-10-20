package net.kaduk

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import net.kaduk.agents.{LLMAgent, WebCrawlerAgent, RssReaderAgent}
import net.kaduk.agents.BaseAgent
import net.kaduk.infrastructure.llm.{OpenAIProvider, ClaudeProvider, OllamaProvider, VertexProvider}
import net.kaduk.infrastructure.registry.AgentRegistry
import net.kaduk.infrastructure.grpc.AgentServiceImpl
import net.kaduk.protobuf.agent_service.AgentServiceHandler
import net.kaduk.config.AppConfig
import net.kaduk.domain.{AgentCapability, AgentType}
import net.kaduk.telemetry.{UiEventBus, TelemetryRoutes}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure}

object MainApp:
  
  def main(args: Array[String]): Unit =
    val rootBehavior = Behaviors.setup[Nothing]: ctx =>
      given ActorSystem[Nothing] = ctx.system
      given ExecutionContext = ctx.executionContext
      
      val config = AppConfig.load()
      val registry = AgentRegistry()
      val uiBus    = ctx.spawn(UiEventBus(), "ui-bus")
      var agentMainOpt: Option[ActorRef[BaseAgent.Command]] = None
      // Spawn LLM agents
      config.agents.foreach: (name, agentConfig) =>
          val providerConfig = config.llmProviders(agentConfig.provider)
          val provider = agentConfig.provider match
            case "openai" => OpenAIProvider(providerConfig.apiKey, providerConfig.model)
            case "claude" => ClaudeProvider(providerConfig.apiKey, providerConfig.model)
            case "ollama" => OllamaProvider(config.llmProviders("ollama").apiKey, providerConfig.model)
            case "vertex" => VertexProvider(providerConfig.apiKey, "us-central1", providerConfig.model)
            case _ => throw new IllegalArgumentException(s"Unknown provider: ${agentConfig.provider}")
          
          val actorName = agentConfig.name.getOrElse(name)
          val capabilityName = agentConfig.capability.getOrElse(name)
          val skills =
            if agentConfig.skills.nonEmpty then agentConfig.skills
            else
              agentConfig.agentType match
                case "web-crawler" => Set("search", "summarization")
                case "rss-reader"  => Set("research", "summarization", "monitoring")
                case _             => Set("text-generation")

          val baseConfig =
            if agentConfig.systemPrompt.nonEmpty then
              Map("systemPrompt" -> agentConfig.systemPrompt)
            else Map.empty[String, String]

          val agentTypeEnum = agentConfig.agentType match
            case "llm-main"    => AgentType.Orchestrator
            case "web-crawler" => AgentType.Specialist
            case "rss-reader"  => AgentType.Specialist
            case _             => AgentType.LLM

          val capability = AgentCapability(
            name = capabilityName,
            agentType = agentTypeEnum,
            skills = skills,
            provider = agentConfig.provider,
            config = baseConfig ++ agentConfig.config
          )
          agentConfig.agentType match
            case "llm" =>
              ctx.log.info(s"Spawning LLM agent: $name")
              ctx.spawn(
                LLMAgent(capability, provider, registry, Some(uiBus), false),
                actorName
              )

            case "llm-main" =>
              ctx.log.info(s"Spawning LLM-coordinator agent: $name")
              agentMainOpt = Some(
                ctx.spawn(
                  LLMAgent(capability, provider, registry, Some(uiBus)),
                  actorName
                )
              )

            case "web-crawler" =>
              ctx.log.info(s"Spawning WebCrawler agent: $name")
              ctx.spawn(
                WebCrawlerAgent(capability, provider, registry, Some(uiBus)),
                actorName
              )

            case "rss-reader" =>
              ctx.log.info(s"Spawning RSS reader agent: $name")
              ctx.spawn(
                RssReaderAgent(capability, provider, registry, Some(uiBus)),
                actorName
              )

            case other =>
              ctx.log.warn(s"Unsupported agent type: $other")
        
          

      
      val mainAgent = agentMainOpt.getOrElse {
        ctx.log.error("No llm-main agent configured in application.conf")
        throw new IllegalStateException("No llm-main agent configured")
      }
      
      // Spawn coordinator
      // val coordinatorRef = ctx.spawn(CoordinatorAgent(registry, Some(uiBus)), "coordinator")
      
      // Start gRPC server
      val service: HttpRequest => Future[HttpResponse] =
        AgentServiceHandler(AgentServiceImpl(mainAgent))
      
      Http().newServerAt("0.0.0.0", 6060).bind(service).onComplete:
        case Success(binding) =>
          ctx.system.log.info(s"gRPC server bound to ${binding.localAddress}")
        case Failure(ex) =>
          ctx.system.log.error("Failed to bind gRPC server", ex)
          ctx.system.terminate()
      
      // UI WebSocket + demo trigger server (ws://localhost:6061/ws, GET /demo)
      Http().newServerAt("0.0.0.0", 6061).bind(TelemetryRoutes.routes(uiBus, mainAgent)).onComplete:
        case Success(binding) =>
          ctx.system.log.info(s"UI server bound to ${binding.localAddress}")
        case Failure(ex) =>
          ctx.system.log.error("Failed to bind UI server", ex)
      
      Behaviors.empty
    
    ActorSystem[Nothing](rootBehavior, "AgentSystem")
