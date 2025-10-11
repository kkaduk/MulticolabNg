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
import com.typesafe.config.ConfigFactory

object HumanAgentClient {
  def main(args: Array[String]): Unit = {

    implicit val sys: ActorSystem[Any] = ActorSystem(
      Behaviors.empty[Any],
      "HumanChatClientDemo",
      ConfigFactory.parseString(
        """
        pekko.actor.provider = "local"
        pekko.remote.artery.canonical.port = 0
        """
      ).withFallback(ConfigFactory.load())
    )
    given org.apache.pekko.actor.ClassicActorSystemProvider = sys

    val targetPort = Option(System.getProperty("grpc.port")).flatMap(p => scala.util.Try(p.toInt).toOption).getOrElse(6060)
    val clientSettings = GrpcClientSettings
      .connectToServiceAt("127.0.0.1", targetPort)
      .withTls(false)
      .withBackend("pekko-http")
    val client: net.kaduk.protobuf.agent_service.AgentService =
      net.kaduk.protobuf.agent_service.AgentServiceClient(clientSettings)

    def testAgentUser(input: String): Unit = {
      val msg = net.kaduk.protobuf.agent_service.ChatMessage(conversationId = "console", content = input, role = "user", metadata = Map.empty)
      val replies = client.chat(Source.single(msg))
      val first: net.kaduk.protobuf.agent_service.ChatResponse = Await.result(replies.runWith(Sink.head), 200.seconds)
      if (first.error != null && first.error.nonEmpty) {
        println("Error from server: " + first.error)
      } else if (first.content == null || first.content.isEmpty) {
        println("No response content received")
      } else {
        println("Answer from HumanAgent:  " + first.content)
      }
    }

    if (args.nonEmpty) {
      val input = args.mkString(" ")
      testAgentUser(input)
      sys.terminate()
      return
    }

    // Interactive loop using StdIn; prints a visible prompt and banner; Ctrl+D to exit
    println("HumanAgentClient ready. Type your message and press Enter. Ctrl+D to exit.")
    var continue = true
    while (continue) {
      print("> ")
      Console.out.flush()
      val line = scala.io.StdIn.readLine()
      if (line == null) {
        continue = false
      } else {
        val input = line.trim
        if (input.nonEmpty) testAgentUser(input)
      }
    }
    sys.terminate()

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
