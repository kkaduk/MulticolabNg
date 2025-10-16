package net.kaduk.telemetry

import org.apache.pekko
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.http.scaladsl.server.{Directives, Route}
import org.apache.pekko.http.scaladsl.server.Directives._
import net.kaduk.agents.BaseAgent
import net.kaduk.domain.{Message => DomMessage, MessageRole, MessageContent, ConversationContext}
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source, Merge}
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.NotUsed

import scala.concurrent.{ExecutionContext}
import scala.concurrent.duration.*
import java.util.UUID

object TelemetryRoutes extends Directives {

  // Creates a WebSocket flow that streams UiEventBus events as JSON to the client.
  private def telemetryFlow(uiBus: ActorRef[UiEventBus.Command])(using system: ActorSystem[?], ec: ExecutionContext): Flow[Message, Message, NotUsed] = {
    // Outgoing source towards the client
    val (queue, src) = Source
      .queue[String](bufferSize = 512, OverflowStrategy.dropHead)
      .preMaterialize()

    // Per-connection subscriber actor that forwards UiEventBus.UiEvent to the queue
    val subscriberName = s"ui-ws-subscriber-${UUID.randomUUID().toString.take(8)}"
    val subscriber = system.systemActorOf(
      Behaviors.receiveMessage[UiEventBus.UiEvent] { ev =>
        // Best-effort enqueue; drop if full
        queue.offer(UiEventBus.toJson(ev))
        Behaviors.same
      },
      subscriberName
    )

    // Subscribe to the bus
    uiBus ! UiEventBus.Subscribe(subscriber)

    // Outgoing stream with heartbeat pings so connections stay alive even if there are no events
    val outgoing: Source[Message, NotUsed] =
      Source.combine(
        src.map(TextMessage(_)),
        Source.tick(10.seconds, 10.seconds, TextMessage("""{"type":"ping"}"""))
      )(Merge(_))

    // Accept and ignore any client messages; coerce materialized value to NotUsed
    val incoming: Sink[Message, NotUsed] =
      Sink.ignore.contramap[Message](_ => ()).mapMaterializedValue(_ => NotUsed)

    Flow.fromSinkAndSourceCoupled(incoming, outgoing)
      .watchTermination() { (_, done) =>
        // On client disconnect, cleanup subscription and stop actor
        done.onComplete { _ =>
          uiBus ! UiEventBus.Unsubscribe(subscriber)
          // subscriber will be garbage-collected; no explicit stop on ActorSystem
        }(ec)
        NotUsed
      }
  }

  // Public route: /ws endpoint that upgrades to WebSocket and streams telemetry
  def websocketRoute(uiBus: ActorRef[UiEventBus.Command])(using system: ActorSystem[?], ec: ExecutionContext): Route = {
    path("ws") {
      handleWebSocketMessages(telemetryFlow(uiBus))
    } ~
    // Simple health endpoint
    path("health") {
      get {
        complete("ok")
      }
    }
  }

  // Combined UI routes including a simple demo trigger:
  // - GET /demo?task=...&convId=...  will send a task to Coordinator to generate telemetry
  def routes(
    uiBus: ActorRef[UiEventBus.Command],
    coordinator: ActorRef[BaseAgent.Command]
  )(using system: ActorSystem[?], ec: ExecutionContext): Route = {
    websocketRoute(uiBus) ~
    path("demo") {
      get {
        parameters("task".withDefault("Plan and summarize: current AI news."), "convId".?) { (task, convOpt) =>
          val convId = convOpt.getOrElse(s"ui-demo-${java.util.UUID.randomUUID().toString.take(8)}")

          // Temporary sink for the final response
          val sink = system.systemActorOf(
            Behaviors.receiveMessage[BaseAgent.Response] { _ =>
              Behaviors.same
            },
            s"ui-demo-sink-${java.util.UUID.randomUUID().toString.take(6)}"
          )

          val ctx  = ConversationContext(id = convId, metadata = Map("maxLoops" -> "1"))
          val msg  = DomMessage(role = MessageRole.User, content = MessageContent(task), conversationId = convId)

          coordinator ! BaseAgent.ProcessMessage(msg, ctx, sink.asInstanceOf[ActorRef[Any]])
          complete(s"Demo started for conversationId=$convId")
        }
      }
    }
  }
}
