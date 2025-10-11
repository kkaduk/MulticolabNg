package net.kaduk.infrastructure.llm

import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
// import io.cequence.openaiscala.service.OpenAIServiceFactory
import io.cequence.openaiscala.domain.*
import io.cequence.openaiscala.domain.settings.CreateChatCompletionSettings
import net.kaduk.domain.{Message, StreamToken, MessageRole}
import scala.concurrent.{Future, ExecutionContext}

import io.cequence.openaiscala.anthropic.service.AnthropicServiceFactory
import io.cequence.openaiscala.anthropic.service.AnthropicService
import io.cequence.openaiscala.anthropic.domain.settings.AnthropicCreateMessageSettings

class ClaudeProvider(
    apiKey: String,
    model: String = "claude-3-5-sonnet-20240620"
)(using system: ActorSystem[?], ec: ExecutionContext)
    extends LLMProvider:

  // Create implicit materializer for the service
  private given akka.actor.ActorSystem =
    akka.actor.ActorSystem("claude-compat", system.settings.config)

  private val service = AnthropicServiceFactory.asOpenAI(apiKey)

  override val name: String = "claude"

  override def streamCompletion(
      messages: Seq[Message],
      systemPrompt: String
  ): Source[StreamToken, NotUsed] =
    val claudeMessages = toClaudeMessages(messages, systemPrompt)

    // Use non-streaming for now as streaming requires additional setup
    Source.future(
      service
        .createChatCompletion(
          messages = claudeMessages,
          settings = CreateChatCompletionSettings(model = model)
        )
        .map: completion =>
          StreamToken(
            content = completion.choices.head.message.content,
            messageId = completion.id,
            isComplete = true
          )
    )

  override def completion(
      messages: Seq[Message],
      systemPrompt: String
  ): Future[String] =
    val openAIMessages = toClaudeMessages(messages, systemPrompt)
    service
      .createChatCompletion(
        messages = openAIMessages,
        settings = CreateChatCompletionSettings(model = model)
      )
      .map(_.choices.head.message.content)

  private def toClaudeMessages(
      messages: Seq[Message],
      systemPrompt: String
  ): Seq[BaseMessage] =
    val sysMsg =
      if systemPrompt.nonEmpty then Seq(SystemMessage(systemPrompt))
      else Seq.empty
    sysMsg ++ messages.map: msg =>
      msg.role match
        case MessageRole.User      => UserMessage(msg.content.text)
        case MessageRole.Assistant => AssistantMessage(msg.content.text)
        case MessageRole.System    => SystemMessage(msg.content.text)
        case MessageRole.Agent =>
          UserMessage(
            s"[Agent ${msg.agentId.getOrElse("unknown")}]: ${msg.content.text}"
          )

  override def close(): Future[Unit] =
    Future.successful(service.close())
    akka.actor.ActorSystem.getClass // Keep reference
    Future.unit
