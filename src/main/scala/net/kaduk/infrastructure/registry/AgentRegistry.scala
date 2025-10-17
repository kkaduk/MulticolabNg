package net.kaduk.infrastructure.registry

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.receptionist.Receptionist
import org.apache.pekko.actor.typed.receptionist.ServiceKey
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.Timeout
import net.kaduk.domain.{AgentCapability, AgentMetadata, AgentStatus}
import net.kaduk.agents.BaseAgent

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import java.util.UUID
import scala.collection.concurrent.TrieMap

class AgentRegistry(using system: ActorSystem[?], ec: ExecutionContext):

  private given Timeout = 5.seconds

  // Toggle local cache (subscription-based). If false, fall back to receptionist ask-only.
  private val useLocalCache: Boolean = true

  // ---------------------- Key helpers & normalization ----------------------
  private object Keys:
    private inline def norm(s: String) = s.trim.toLowerCase
    def capabilityKey(capability: String): ServiceKey[BaseAgent.Command] =
      ServiceKey[BaseAgent.Command](s"cap:${norm(capability)}")

  // ---------------------- Sanitization helpers ----------------------
  private def sanitizeForActorName(id: String): String =
    val allowed: Set[Char] = Set('-', '_', '.', '*', '$', '+', ':', '@', '&', '=', ',', '!', '~', '\'', ';')
    id.map { ch =>
      if ch.isLetterOrDigit || allowed.contains(ch) then ch else '-'
    }

  // ---------------------- Local cache (optional) ----------------------
  // Indices keyed by normalized id (e.g., "ner" or "planner") -> Set[ActorRef]
  private val capIndex   = TrieMap.empty[String, Set[ActorRef[BaseAgent.Command]]]
  private val agentCaps  = TrieMap.empty[ActorRef[BaseAgent.Command], AgentCapability]

  // Track active per-key subscribers to avoid duplicate subscriptions
  private val capSubscribers   = TrieMap.empty[String, ActorRef[Receptionist.Listing]]

  private def ensureSubscribedCap(
      key: ServiceKey[BaseAgent.Command],
      id: String
  ): Unit =
    if useLocalCache then
      if !capSubscribers.contains(id) then
        val safeId = sanitizeForActorName(id)
        val name = s"agent-registry-subscriber-cap-${safeId}-${UUID.randomUUID().toString.take(8)}"
        val subscriber: Behavior[Receptionist.Listing] = Behaviors.setup { _ =>
          Behaviors.receiveMessage { listing =>
            val instances = listing.serviceInstances(key)
            capIndex.put(id, instances)
            Behaviors.same
          }
        }
        val ref = system.systemActorOf(subscriber, name)
        system.receptionist ! Receptionist.Subscribe(key, ref)
        capSubscribers.put(id, ref)

  // ---------------------- Registration (backwards compatible) ----------------------
  def register(agentRef: ActorRef[BaseAgent.Command], capability: AgentCapability): Future[Unit] =
    val capId = capability.name.trim.toLowerCase
    val key = Keys.capabilityKey(capId)
    ensureSubscribedCap(key, capId)
    agentCaps.put(agentRef, capability)
    system.receptionist ! Receptionist.Register(key, agentRef)
    Future.successful(())

  // Extended registration with skills
  def register(
      agentRef: ActorRef[BaseAgent.Command],
      capability: AgentCapability,
      skills: Set[String]
  ): Future[Unit] =
    // Backward-compatible overload: ignore separate skill registration.
    // Merge any passed-in skills into the capability we track locally.
    val merged = capability.copy(skills = capability.skills ++ skills)
    register(agentRef, merged)



  // ---------------------- Deregistration ----------------------
  def deregister(agentRef: ActorRef[BaseAgent.Command], capability: AgentCapability): Future[Unit] =
    val id  = capability.name.trim.toLowerCase
    val key = Keys.capabilityKey(id)
    agentCaps.remove(agentRef)
    system.receptionist ! Receptionist.Deregister(key, agentRef)
    Future.successful(())



  // ---------------------- Capability lookups ----------------------
  def findAgent(capability: String): Future[Option[ActorRef[BaseAgent.Command]]] =
    val id  = capability.trim.toLowerCase
    // Strictly prefer agents registered through this registry instance (isolated per test)
    val local = agentCaps.collect {
      case (ref, cap) if cap.name.trim.toLowerCase == id => ref
    }.toSeq
    Future.successful(local.headOption)

  def findAllAgents(agentType: String): Future[Set[ActorRef[BaseAgent.Command]]] =
    val id  = agentType.trim.toLowerCase
    val local = agentCaps.collect {
      case (ref, cap) if cap.name.trim.toLowerCase == id => ref
    }.toSet
    Future.successful(local)

  // ---------------------- Skill lookups ----------------------
  /** All agents that declare a given skill. */
  def findAgentsBySkill(skill: String): Future[Set[ActorRef[BaseAgent.Command]]] =
    val id  = skill.trim.toLowerCase
    val refs = agentCaps.collect {
      case (ref, cap) if cap.skills.exists(_.trim.toLowerCase == id) => ref
    }.toSet
    Future.successful(refs)

  /** Union: agents that have ANY of the provided skills. */
  def findAgentsByAnySkill(skills: Set[String]): Future[Set[ActorRef[BaseAgent.Command]]] =
    val ids = skills.map(_.trim.toLowerCase).filter(_.nonEmpty)
    if ids.isEmpty then Future.successful(Set.empty)
    else
      val refs = agentCaps.collect {
        case (ref, cap) if cap.skills.exists(s => ids.contains(s.trim.toLowerCase)) => ref
      }.toSet
      Future.successful(refs)

  /** Intersection: agents that have ALL of the provided skills. */
  def findAgentsByAllSkills(skills: Set[String]): Future[Set[ActorRef[BaseAgent.Command]]] =
    val ids = skills.map(_.trim.toLowerCase).filter(_.nonEmpty)
    if ids.isEmpty then Future.successful(Set.empty)
    else
      val refs = agentCaps.collect {
        case (ref, cap) =>
          val skillSet = cap.skills.map(_.trim.toLowerCase)
          if ids.subsetOf(skillSet) then Some(ref) else None
      }.flatten.toSet
      Future.successful(refs)

object AgentRegistry:
  def apply()(using system: ActorSystem[?], ec: ExecutionContext): AgentRegistry =
    new AgentRegistry(using system, ec)
