package net.kaduk.infrastructure.llm

import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import io.cequence.openaiscala.domain.*
import io.cequence.openaiscala.domain.settings.CreateChatCompletionSettings
import net.kaduk.domain.{Message, StreamToken, MessageRole}
import scala.concurrent.{Future, ExecutionContext}

class ClaudeProvider(
  apiKey: String,
  model: String = "claude-3-5-sonnet-20240620"
)(using system: ActorSystem[?], ec: ExecutionContext) extends LLMProvider:

  // Note: Anthropic support requires the anthropic module
  // For now, using a mock implementation
  override val name: String = "claude"

  override def streamCompletion(
    messages: Seq[Message],
    systemPrompt: String
  ): Source[StreamToken, NotUsed] =
    Source.single(StreamToken(
      content = "Claude provider not yet implemented. Please use OpenAI or Ollama.",
      messageId = "claude-mock",
      isComplete = true
    ))

  override def completion(messages: Seq[Message], systemPrompt: String): Future[String] =
    Future.successful("Claude provider not yet implemented. Please use OpenAI or Ollama.")

  override def close(): Future[Unit] = Future.unit