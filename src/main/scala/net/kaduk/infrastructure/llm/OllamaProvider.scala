package net.kaduk.infrastructure.llm

import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import io.cequence.openaiscala.service.{OpenAIChatCompletionService, OpenAIChatCompletionServiceFactory}
import io.cequence.openaiscala.domain.*
import io.cequence.openaiscala.domain.settings.CreateChatCompletionSettings
import net.kaduk.domain.{Message, StreamToken, MessageRole}
import scala.concurrent.{Future, ExecutionContext}

class OllamaProvider(
  baseUrl: String = "http://localhost:11434/v1/",
  model: String = "llama3"
)(using system: ActorSystem[?], ec: ExecutionContext) extends LLMProvider:

  private given akka.actor.ActorSystem = 
    akka.actor.ActorSystem("ollama-compat", system.settings.config)

  private val service: OpenAIChatCompletionService = 
    OpenAIChatCompletionServiceFactory(coreUrl = baseUrl)
  
  override val name: String = "ollama"

  override def streamCompletion(
    messages: Seq[Message],
    systemPrompt: String
  ): Source[StreamToken, NotUsed] =
    val ollamaMessages = toOllamaMessages(messages, systemPrompt)
    
    Source.future(
      service.createChatCompletion(
        messages = ollamaMessages,
        settings = CreateChatCompletionSettings(model = ModelId.gpt_3_5_turbo) // Use generic model
      ).map: completion =>
        StreamToken(
          content = completion.choices.head.message.content,
          messageId = completion.id,
          isComplete = true
        )
    )

  override def completion(messages: Seq[Message], systemPrompt: String): Future[String] =
    val ollamaMessages = toOllamaMessages(messages, systemPrompt)
    service.createChatCompletion(
      messages = ollamaMessages,
      settings = CreateChatCompletionSettings(model = ModelId.gpt_3_5_turbo)
    ).map(_.choices.head.message.content)

  private def toOllamaMessages(messages: Seq[Message], systemPrompt: String): Seq[BaseMessage] =
    val sysMsg = if systemPrompt.nonEmpty then Seq(SystemMessage(systemPrompt)) else Seq.empty
    sysMsg ++ messages.map: msg =>
      msg.role match
        case MessageRole.User => UserMessage(msg.content.text)
        case MessageRole.Assistant => AssistantMessage(msg.content.text)
        case MessageRole.System => SystemMessage(msg.content.text)
        case MessageRole.Agent => UserMessage(s"[Agent ${msg.agentId.getOrElse("unknown")}]: ${msg.content.text}")

  override def close(): Future[Unit] = 
    service.close()
    Future.unit