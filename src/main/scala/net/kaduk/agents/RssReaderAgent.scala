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

import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}
import scala.xml.{Elem, Node, XML}
import java.util.UUID

object RssReaderAgent:

  private val log = LoggerFactory.getLogger("RssReaderAgent")

  private case class FeedItem(title: String, link: String, summary: String)

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
        s"[${capability.name}] RssReaderAgent online. Skills: ${capability.skills.mkString(", ")}"
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
        handleFeed(message, context, replyTo, capability, provider, http, uiBus)
        Behaviors.same

      case BaseAgent.StreamMessage(_, _, _) =>
        ctx.log.warn(s"[${capability.name}] Streaming not supported for RSS reader")
        Behaviors.same

      case BaseAgent.GetStatus =>
        ctx.log.debug(s"[${capability.name}] Status requested (rss-reader idle)")
        Behaviors.same

      case BaseAgent.Stop =>
        ctx.log.info(s"[${capability.name}] Shutting down RSS reader agent")
        registry.deregister(ctx.self, capability)
        Behaviors.stopped

      case BaseAgent.NoOp =>
        Behaviors.same

      case other =>
        ctx.log.warn(
          s"[${capability.name}] Received unsupported command: ${other.getClass.getSimpleName}"
        )
        Behaviors.same
    }

  private def handleFeed(
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
    val stepId = message.content.metadata.getOrElse("stepId", "rss")
    ctx.log.info(s"[${capability.name}] Processing RSS request step=$stepId")

    val attempt = message.content.metadata
      .get("clarificationAttempt")
      .flatMap(v => Try(v.toInt).toOption)
      .getOrElse(0)

    val maxAttempts = capability.config
      .get("maxClarificationRounds")
      .flatMap(v => Try(v.toInt).toOption)
      .getOrElse(3)

    val workingContext = integrateFeedClarification(message, context)

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

    resolveFeedUrl(message, workingContext, capability) match
      case Left(err) =>
        if attempt < maxAttempts then
          val request = buildFeedClarificationRequest(
            capability,
            workingContext,
            stepId,
            attempt + 1,
            err
          )
          ctx.log.info(
            s"[${capability.name}] Requesting RSS URL clarification (attempt ${attempt + 1})"
          )
          replyTo ! request
        else
          ctx.log.warn(s"[${capability.name}] Unable to resolve feed URL: $err")
          uiBus.foreach(
            _ ! UiEventBus.Publish(UiEventBus.ErrorEvent(context.id, err))
          )
          replyTo ! BaseAgent.ProcessingFailed(err, message.id)

      case Right(url) =>
        val enrichedContext =
          workingContext.copy(
            metadata = workingContext.metadata + ("feedUrl" -> url)
          )
        val fetchFuture =
          for
            xmlString <- fetchFeed(http, url)
            feedXml <- Future.fromTry(parseXml(xmlString))
            items = extractItems(feedXml, capability)
            summary <- summarizeFeed(url, items, provider, enrichedContext, capability)
          yield (items, summary)

        fetchFuture.onComplete {
          case Success((items, summary)) =>
            val responseMsg = Message(
              role = MessageRole.Assistant,
              content = MessageContent(
                formatResponse(url, items, summary),
                metadata = Map("sourceUrl" -> url, "satisfied" -> "true")
              ),
              conversationId = enrichedContext.id,
              agentId = Some(capability.name)
            )

            uiBus.foreach { bus =>
              bus ! UiEventBus.Publish(
                UiEventBus.ChatMessage(
                  enrichedContext.id,
                  responseMsg.role.toString,
                  responseMsg.id,
                  responseMsg.content.text,
                  responseMsg.agentId
                )
              )
              bus ! UiEventBus.Publish(
                UiEventBus.AgentComplete(
                  enrichedContext.id,
                  capability.name,
                  stepId,
                  responseMsg.id,
                  responseMsg.content.text.length
                )
              )
            }

            replyTo ! BaseAgent.ProcessedMessage(
              responseMsg,
              enrichedContext
                .addMessage(message)
                .addMessage(responseMsg)
            )

          case Failure(ex) =>
            val msg = s"RSS fetch failed: ${ex.getMessage}"
            ctx.log.error(msg, ex)
            uiBus.foreach(
              _ ! UiEventBus.Publish(UiEventBus.ErrorEvent(context.id, msg))
            )
            replyTo ! BaseAgent.ProcessingFailed(msg, message.id)
        }

  private def resolveFeedUrl(
      message: Message,
      context: ConversationContext,
      capability: AgentCapability
  ): Either[String, String] =
    val metaUrl = message.content.metadata
      .get("feedUrl")
      .orElse(message.content.metadata.get("url"))
    val textUrl = extractUrlFromText(message.content.text)
    val contextUrl =
      context.metadata
        .get("feedUrl")
        .orElse(context.metadata.get("targetUrl"))
    val defaultUrl = capability.config
      .get("feed-url")
      .orElse(capability.config.get("default-url"))

    val resolved =
      metaUrl.orElse(textUrl).orElse(contextUrl).orElse(defaultUrl)

    resolved match
      case None => Left("No RSS feed URL provided.")
      case Some(candidate) =>
        normalizeUrl(candidate).flatMap { normalized =>
          validateDomain(normalized, capability)
        }

  private val urlRegex =
    "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)".r

  private def extractUrlFromText(text: String): Option[String] =
    urlRegex.findFirstIn(text)

  private def integrateFeedClarification(
      message: Message,
      context: ConversationContext
  ): ConversationContext =
    val clarified =
      message.content.metadata
        .get("clarificationTopic")
        .filter(_ == "rss-url")
        .flatMap(_ => extractUrlFromText(message.content.text))

    val metadataUrl =
      message.content.metadata
        .get("feedUrl")
        .orElse(message.content.metadata.get("url"))

    clarified
      .orElse(metadataUrl)
      .filter(_.nonEmpty)
      .map(url => context.copy(metadata = context.metadata + ("feedUrl" -> url)))
      .getOrElse(context)

  private def buildFeedClarificationRequest(
      capability: AgentCapability,
      context: ConversationContext,
      stepId: String,
      attempt: Int,
      reason: String
  ): BaseAgent.ClarificationRequest =
    val clarificationId = UUID.randomUUID().toString
    val questionText =
      s"""Provide the RSS or Atom feed URL required for this task.
         |- Return only a single URL using http(s).
         |- If the feed is unavailable, reply with "clarification-needed".
         |
         |Reason: $reason
         |""".stripMargin

    val metadata = Map(
      "clarificationTopic" -> "rss-url",
      "clarificationId" -> clarificationId,
      "clarificationCapability" -> "creator",
      "clarificationSkill" -> "research,monitoring",
      "clarificationAttempt" -> attempt.toString,
      "requestedBy" -> capability.name
    )

    val question = Message(
      role = MessageRole.Agent,
      content = MessageContent(questionText, metadata),
      conversationId = context.id,
      agentId = Some(capability.name)
    )

    BaseAgent.ClarificationRequest(
      originalStepId = stepId,
      question = question,
      context = context,
      targetCapabilities = Set("creator"),
      targetSkills = Set("research", "monitoring"),
      metadata = metadata
    )

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
            Left(s"Feed host $h not allowed. Expected domain ending with $domain.")
          case None => Left("Unable to determine feed host.")

  private def fetchFeed(
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
            s"Unexpected status ${status.intValue()} while fetching feed $url"
          )
        )

  private def parseXml(xml: String): Try[Elem] =
    Try(XML.loadString(xml))

  private def extractItems(
      feed: Elem,
      capability: AgentCapability
  ): Seq[FeedItem] =
    val maxItems = capability.config
      .get("max-items")
      .flatMap(v => Try(v.toInt).toOption)
      .getOrElse(5)

    val channelItems = (feed \\ "item").take(maxItems)
    if channelItems.nonEmpty then
      channelItems.flatMap(parseItem)
    else
      // Atom feed fallback
      (feed \\ "entry").take(maxItems).flatMap(parseEntry)

  private def parseItem(node: Node): Option[FeedItem] =
    val title = (node \ "title").text.trim
    val link = (node \ "link").text.trim
    val description = (node \ "description").text.trim

    if title.nonEmpty then
      Some(
        FeedItem(
          title = title,
          link = if link.nonEmpty then link else (node \ "@url").text.trim,
          summary = if description.nonEmpty then description else title
        )
      )
    else None

  private def parseEntry(node: Node): Option[FeedItem] =
    val title = (node \ "title").text.trim
    val link = (node \ "link" \ "@href").text.trim
    val summary = (node \ "summary").text.trim

    if title.nonEmpty then
      Some(
        FeedItem(
          title = title,
          link = link,
          summary = if summary.nonEmpty then summary else title
        )
      )
    else None

  private def summarizeFeed(
      url: String,
      items: Seq[FeedItem],
      provider: LLMProvider,
      context: ConversationContext,
      capability: AgentCapability
  )(using ec: ExecutionContext): Future[String] =
    val systemPrompt = capability.config.getOrElse(
      "systemPrompt",
      "You are a specialist that reviews RSS feeds and highlights actionable insights."
    )

    val bulletList = items.zipWithIndex
      .map { case (item, idx) =>
        s"${idx + 1}. ${item.title}\n   Link: ${item.link}\n   Summary: ${item.summary.take(500)}"
      }
      .mkString("\n\n")

    val userPrompt =
      s"""You have fetched RSS feed items from $url.
         |
         |Entries:
         |$bulletList
         |
         |Produce a concise briefing:
         |- Provide 3-4 bullet highlights with implications.
         |- Call out significant figures, risks, or emerging themes.
         |- Suggest next actions if relevant.
         |
         |Respond in markdown.
       """.stripMargin

    val userMessage = Message(
      role = MessageRole.User,
      content = MessageContent(userPrompt),
      conversationId = context.id
    )

    provider.completion(Seq(userMessage), systemPrompt)

  private def formatResponse(
      url: String,
      items: Seq[FeedItem],
      summary: String
  ): String =
    val latest =
      if items.isEmpty then "No entries found."
      else
        items
          .map(item => s"- [${item.title}](${item.link})\n  ${item.summary.take(160)}")
          .mkString("\n")

    s"""## RSS Briefing for $url
       |
       |$summary
       |
       |---
       |**Latest entries**
       |
       |$latest
       |""".stripMargin
