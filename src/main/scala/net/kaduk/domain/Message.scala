package net.kaduk.domain

import java.time.Instant
import java.util.UUID

enum MessageRole:
  case User, Assistant, System, Agent

case class MessageContent(text: String, metadata: Map[String, String] = Map.empty)

case class Message(
  id: String = UUID.randomUUID().toString,
  role: MessageRole,
  content: MessageContent,
  conversationId: String,
  timestamp: Instant = Instant.now(),
  agentId: Option[String] = None
)

case class StreamToken(
  content: String,
  messageId: String,
  isComplete: Boolean = false,
  error: Option[String] = None
)

case class ConversationContext(
  id: String,
  messages: Vector[Message] = Vector.empty,
  metadata: Map[String, String] = Map.empty
):
  def addMessage(msg: Message): ConversationContext =
    copy(messages = messages :+ msg)