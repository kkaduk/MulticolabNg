package net.kaduk.infrastructure.llm

import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.NotUsed
import net.kaduk.domain.{Message, StreamToken}
import scala.concurrent.Future

trait LLMProvider:
  def name: String
  
  def streamCompletion(
    messages: Seq[Message],
    systemPrompt: String
  ): Source[StreamToken, NotUsed]
  
  def completion(messages: Seq[Message], systemPrompt: String): Future[String]
  
  def close(): Future[Unit]

trait LLMProviderFactory:
  def create(providerName: String, config: Map[String, String]): LLMProvider