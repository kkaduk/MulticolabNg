package net.kaduk.agents

import net.kaduk.domain.*
import net.kaduk.infrastructure.llm.LLMProvider
import net.kaduk.infrastructure.registry.AgentRegistry
import net.kaduk.telemetry.UiEventBus

import org.apache.pekko
import pekko.actor.typed.{ActorRef, Behavior}
import pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import pekko.actor.typed.scaladsl.adapter.*
import pekko.http.scaladsl.{Http, HttpExt}
import pekko.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import pekko.stream.Materializer

import org.jsoup.Jsoup
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

object WebCrawlerAgent:

  private val log = LoggerFactory.getLogger("WebCrawlerAgent")

  def apply(
      capability: AgentCapability,
      provider: LLMProvider,
      registry: AgentRegistry,
      uiBus: Option[ActorRef[UiEventBus.Command]] = None
  )(using ec: ExecutionContext): Behavior[BaseAgent.Command] =
    Behaviors.setup { ctx =>
      given ActorContext[BaseAgent.Command] = ctx

      registry.register(ctx.self, capability, capability.skills)

      given Materializer = Materializer(ctx.system)
      val http = Http()(ctx.system.classicSystem)

      ctx.log.info(
        s"[${capability.name}] WebCrawlerAgent online. Skills: ${capability.skills.mkString(", ")}"
      )

      idle(capability, provider, registry, http, uiBus)
    }

  private def idle(
      capability: AgentCapability,
      provider: LLMProvider,
      registry: AgentRegistry,
      http: HttpExt,
      uiBus: Option[ActorRef[UiEventBus.Command]]
  )(using
      ctx: ActorContext[BaseAgent.Command],
      ec: ExecutionContext,
      mat: Materializer
  ): Behavior[BaseAgent.Command] =
    Behaviors.receiveMessage {
      case BaseAgent.ProcessMessage(message, context, replyTo) =>
        handleCrawl(message, context, replyTo, capability, provider, http, uiBus)
        Behaviors.same

      case BaseAgent.StreamMessage(_, _, _) =>
        ctx.log.warn(s"[${capability.name}] Streaming not supported for crawler")
        Behaviors.same

      case BaseAgent.Stop =>
        ctx.log.info(s"[${capability.name}] Shutting down crawler agent")
        registry.deregister(ctx.self, capability)
        Behaviors.stopped

      case BaseAgent.GetStatus =>
        replyWithStatus(capability, ctx)
        Behaviors.same

      case BaseAgent.NoOp =>
        Behaviors.same

      case other =>
        ctx.log.warn(
          s"[${capability.name}] Received unsupported command: ${other.getClass.getSimpleName}"
        )
        Behaviors.same
    }

  private def handleCrawl(
      message: Message,
      context: ConversationContext,
      replyTo: ActorRef[Any],
      capability: AgentCapability,
      provider: LLMProvider,
      http: HttpExt,
      uiBus: Option[ActorRef[UiEventBus.Command]]
  )(using
      ctx: ActorContext[BaseAgent.Command],
      ec: ExecutionContext,
      mat: Materializer
  ): Unit =
    val stepId = message.content.metadata.getOrElse("stepId", "crawl")
    ctx.log.info(s"[${capability.name}] Processing crawl request step=$stepId")

    uiBus.foreach(
      _ ! UiEventBus.Publish(
        UiEventBus.AgentStart(context.id, capability.name, stepId, message.id, false)
      )
    )
    uiBus.foreach(
      _ ! UiEventBus.Publish(
        UiEventBus.ChatMessage(
          context.id,
          message.role.toString,
          message.id,
          message.content.text,
          message.agentId
        )
      )
    )

    resolveTargetUrl(message, capability) match
      case Left(err) =>
        ctx.log.warn(s"[${capability.name}] Unable to resolve URL: $err")
        uiBus.foreach(
          _ ! UiEventBus.Publish(UiEventBus.ErrorEvent(context.id, err))
        )
        replyTo ! BaseAgent.ProcessingFailed(err, message.id)

      case Right(url) =>
        val crawlFut =
          for
            rawHtml <- fetchUrl(http, url)
            text = sanitizeHtml(rawHtml)
            limited = applyContentLimit(text, capability)
            summary <- summarize(url, limited, provider, context, capability)
          yield (limited, summary)

        crawlFut.onComplete {
          case Success((content, summary)) =>
            val combined = formatResponse(url, summary, content.take(600))
            val responseMsg = Message(
              role = MessageRole.Assistant,
              content = MessageContent(
                combined,
                metadata = Map("sourceUrl" -> url, "satisfied" -> "true")
              ),
              conversationId = context.id,
              agentId = Some(capability.name)
            )

            uiBus.foreach { bus =>
              bus ! UiEventBus.Publish(
                UiEventBus.ChatMessage(
                  context.id,
                  responseMsg.role.toString,
                  responseMsg.id,
                  responseMsg.content.text,
                  responseMsg.agentId
                )
              )
              bus ! UiEventBus.Publish(
                UiEventBus.AgentComplete(
                  context.id,
                  capability.name,
                  stepId,
                  responseMsg.id,
                  responseMsg.content.text.length
                )
              )
            }

            replyTo ! BaseAgent.ProcessedMessage(
              responseMsg,
              context
                .addMessage(message)
                .addMessage(responseMsg)
            )

          case Failure(ex) =>
            val msg = s"Crawl failed for $url: ${ex.getMessage}"
            ctx.log.error(msg, ex)
            uiBus.foreach(
              _ ! UiEventBus.Publish(UiEventBus.ErrorEvent(context.id, msg))
            )
            replyTo ! BaseAgent.ProcessingFailed(msg, message.id)
        }

  private def resolveTargetUrl(
      message: Message,
      capability: AgentCapability
  ): Either[String, String] =
    val metadataUrl = message.content.metadata
      .get("url")
      .orElse(message.content.metadata.get("targetUrl"))

    val textUrl = extractUrlFromText(message.content.text)
    val defaultUrl = capability.config
      .get("default-url")
      .orElse(capability.config.get("base-url"))

    val resolved = metadataUrl.orElse(textUrl).orElse(defaultUrl)

    resolved match
      case None => Left("No URL provided for crawling.")
      case Some(candidate) =>
        normalizeUrl(candidate).flatMap { normalized =>
          validateDomain(normalized, capability)
        }

  private def normalizeUrl(url: String): Either[String, String] =
    val trimmed = url.trim
    val prefixed =
      if trimmed.startsWith("http://") || trimmed.startsWith("https://")
      then trimmed
      else s"https://$trimmed"

    Try(java.net.URI(prefixed)).toEither.left.map(_.getMessage).flatMap { uri =>
      Option(uri.getScheme) match
        case Some(scheme) if scheme == "http" || scheme == "https" =>
          Right(uri.toString)
        case Some(scheme) =>
          Left(s"Unsupported URL scheme: $scheme")
        case None =>
          Left("URL missing scheme")
    }

  private def validateDomain(
      url: String,
      capability: AgentCapability
  ): Either[String, String] =
    val allowed =
      capability.config
        .get("allowed-host")
        .orElse(capability.config.get("allowed-domain"))
        .map(_.toLowerCase)

    allowed match
      case None => Right(url)
      case Some(domain) =>
        val host = Try(java.net.URI(url).getHost).toOption
        host match
          case Some(h) if h.toLowerCase.endsWith(domain) => Right(url)
          case Some(h) =>
            Left(s"URL host $h not allowed. Expected domain ending with $domain.")
          case None => Left("Unable to determine URL host.")

  private val urlRegex =
    "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)".r

  private def extractUrlFromText(text: String): Option[String] =
    urlRegex.findFirstIn(text)

  private def fetchUrl(
      http: HttpExt,
      url: String
  )(using mat: Materializer, ec: ExecutionContext): Future[String] =
    val request = HttpRequest(uri = url)
    http
      .singleRequest(request)
      .flatMap { response =>
        handleResponse(url, response)
      }

  private def handleResponse(
      url: String,
      response: HttpResponse
  )(using mat: Materializer, ec: ExecutionContext): Future[String] =
    response.status match
      case StatusCodes.OK =>
        response.entity
          .toStrict(10.seconds)
          .map { strict =>
            response.discardEntityBytes()
            strict.data.utf8String
          }
      case status =>
        response.discardEntityBytes()
        Future.failed(
          new RuntimeException(
            s"Unexpected status ${status.intValue()} while fetching $url"
          )
        )

  private def sanitizeHtml(html: String): String =
    val doc = Jsoup.parse(html)
    doc.select("script, style, noscript").forEach(_.remove())
    doc.text()

  private def applyContentLimit(
      text: String,
      capability: AgentCapability
  ): String =
    val limit = capability.config
      .get("max-chars")
      .flatMap(v => Try(v.toInt).toOption)
      .getOrElse(6000)
    text.take(limit)

  private def summarize(
      url: String,
      content: String,
      provider: LLMProvider,
      context: ConversationContext,
      capability: AgentCapability
  )(using ec: ExecutionContext): Future[String] =
    val systemPrompt = capability.config.getOrElse(
      "systemPrompt",
      "You are a focused research assistant that extracts key findings from webpages."
    )
    val userPrompt =
      s"""You have crawled the following webpage: $url
         |
         |Content snapshot (truncated):
         |${content}
         |
         |Produce a concise summary with:
         |- 3-5 key bullet points
         |- Important facts, metrics, or conclusions from the page
         |- Any caveats or missing information
         |
         |Return markdown formatted output.
       """.stripMargin

    val userMessage = Message(
      role = MessageRole.User,
      content = MessageContent(userPrompt),
      conversationId = context.id
    )

    provider.completion(Seq(userMessage), systemPrompt)

  private def formatResponse(
      url: String,
      summary: String,
      preview: String
  ): String =
    s"""## Summary for $url
       |
       |$summary
       |
       |---
       |_Preview_: ${preview.trim}
       |""".stripMargin

  private def replyWithStatus(
      capability: AgentCapability,
      ctx: ActorContext[BaseAgent.Command]
  ): Unit =
    ctx.log.debug(s"[${capability.name}] Status requested (crawler idle)")
