package net.kaduk

import net.kaduk.protobuf.agent_service.*
import scala.concurrent.duration._
import scala.concurrent.Await

import java.util.Scanner

import io.grpc.{CallCredentials, Metadata}
import java.util.concurrent.Executor
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.grpc.GrpcClientSettings
import org.apache.pekko.stream.scaladsl.{Sink, Source}

object HumanAgentClient {
  def main(args: Array[String]): Unit = {

    implicit val sys: ActorSystem[Any] = ActorSystem(Behaviors.empty[Any], "HumanChatClientDemo")
    given org.apache.pekko.actor.ClassicActorSystemProvider = sys

    val clientSettings = GrpcClientSettings
      .connectToServiceAt("127.0.0.1", 6060)
      .withTls(false)
      .withBackend("pekko-http")
    val client: net.kaduk.protobuf.agent_service.AgentService =
      net.kaduk.protobuf.agent_service.AgentServiceClient(clientSettings)

    val scanner = new Scanner(System.in);
    while (true) {
      println("Enter a question for HumanAgent: ")
      val input = scanner.nextLine();
      testAgentUser(input)
    }

    def testAgentUser(input: String): Unit = {
      val msg = net.kaduk.protobuf.agent_service.ChatMessage(conversationId = "console", content = input, role = "user", metadata = Map.empty)
      val replies = client.chat(Source.single(msg))
      val first: net.kaduk.protobuf.agent_service.ChatResponse = Await.result(replies.runWith(Sink.head), 200.seconds)
      println("Answer from HumanAgent:  " + first.content)
    }
  }

  class CustomCallCredentials(token: String) extends CallCredentials {
    override def applyRequestMetadata(
        requestInfo: CallCredentials.RequestInfo,
        appExecutor: Executor,
        applier: CallCredentials.MetadataApplier
    ): Unit = {
      val headers = new Metadata()
      val authHeader =
        Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)
      headers.put(authHeader, s"Bearer $token")
      applier.apply(headers)
    }

    override def thisUsesUnstableApi(): Unit = {}
  }

}
