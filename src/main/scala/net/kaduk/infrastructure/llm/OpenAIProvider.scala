package net.kaduk.infrastructure.llm

import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import io.cequence.openaiscala.service.OpenAIServiceFactory
import io.cequence.openaiscala.domain.*
import io.cequence.openaiscala.domain.settings.CreateChatCompletionSettings
import net.kaduk.domain.{Message, StreamToken, MessageRole}
import scala.concurrent.{Future, ExecutionContext}

class OpenAIProvider(
  apiKey: String,
  model: String = "gpt-4"
)(using system: ActorSystem[?], ec: ExecutionContext) extends LLMProvider:

  // Create implicit materializer for the service (Akka classic needed by cequence client)
  private given akka.actor.ActorSystem =
    akka.actor.ActorSystem("openai-compat", system.settings.config)
  private given akka.stream.Materializer =
    akka.stream.SystemMaterializer(summon[akka.actor.ActorSystem]).materializer

  private val service = OpenAIServiceFactory(apiKey)
  
  override val name: String = "openai"

  override def streamCompletion(
    messages: Seq[Message],
    systemPrompt: String
  ): Source[StreamToken, NotUsed] =
    val openAIMessages = toOpenAIMessages(messages, systemPrompt)
    
    // Use non-streaming for now as streaming requires additional setup
    Source.future(
      service.createChatCompletion(
        messages = openAIMessages,
        settings = CreateChatCompletionSettings(model = ModelId.gpt_4)
      ).map: completion =>
        StreamToken(
          content = completion.choices.head.message.content,
          messageId = completion.id,
          isComplete = true
        )
    )

  override def completion(messages: Seq[Message], systemPrompt: String): Future[String] =
    val openAIMessages = toOpenAIMessages(messages, systemPrompt)
    service.createChatCompletion(
      messages = openAIMessages,
      settings = CreateChatCompletionSettings(model = ModelId.gpt_4)
    ).map(_.choices.head.message.content)

  private def toOpenAIMessages(messages: Seq[Message], systemPrompt: String): Seq[BaseMessage] =
    val sysMsg = if systemPrompt.nonEmpty then Seq(SystemMessage(systemPrompt)) else Seq.empty
    sysMsg ++ messages.map: msg =>
      msg.role match
        case MessageRole.User => UserMessage(msg.content.text)
        case MessageRole.Assistant => AssistantMessage(msg.content.text)
        case MessageRole.System => SystemMessage(msg.content.text)
        case MessageRole.Agent => UserMessage(s"[Agent ${msg.agentId.getOrElse("unknown")}]: ${msg.content.text}")

  override def close(): Future[Unit] =
    Future.successful(service.close())
