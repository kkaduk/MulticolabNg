package net.kaduk.infrastructure.llm

import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import net.kaduk.domain.{Message, StreamToken, MessageRole}
import scala.concurrent.{Future, ExecutionContext}

class VertexProvider(
  projectId: String,
  location: String = "us-central1",
  model: String = "gemini-1.5-flash-001"
)(using system: ActorSystem[?], ec: ExecutionContext) extends LLMProvider:

  // Vertex AI requires additional dependencies
  // Mock implementation for now
  override val name: String = "vertex"

  override def streamCompletion(
    messages: Seq[Message],
    systemPrompt: String
  ): Source[StreamToken, NotUsed] =
    Source.single(StreamToken(
      content = "Vertex AI provider not yet implemented. Please use OpenAI or Ollama.",
      messageId = "vertex-mock",
      isComplete = true
    ))

  override def completion(messages: Seq[Message], systemPrompt: String): Future[String] =
    Future.successful("Vertex AI provider not yet implemented. Please use OpenAI or Ollama.")

  override def close(): Future[Unit] = Future.unit