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

    def skillKey(skill: String): ServiceKey[BaseAgent.Command] =
      ServiceKey[BaseAgent.Command](s"skill:${norm(skill)}")

  // ---------------------- Local cache (optional) ----------------------
  // Indices keyed by normalized id (e.g., "ner" or "planner") -> Set[ActorRef]
  private val capIndex   = TrieMap.empty[String, Set[ActorRef[BaseAgent.Command]]]
  private val skillIndex = TrieMap.empty[String, Set[ActorRef[BaseAgent.Command]]]

  // Track active per-key subscribers to avoid duplicate subscriptions
  private val capSubscribers   = TrieMap.empty[String, ActorRef[Receptionist.Listing]]
  private val skillSubscribers = TrieMap.empty[String, ActorRef[Receptionist.Listing]]

  private def ensureSubscribed(
      key: ServiceKey[BaseAgent.Command],
      id: String,
      isSkill: Boolean
  ): Unit =
    if useLocalCache then
      val subsMap = if isSkill then skillSubscribers else capSubscribers
      if !subsMap.contains(id) then
        val name = s"agent-registry-subscriber-${if isSkill then "skill" else "cap"}-${id}-${UUID.randomUUID().toString.take(8)}"
        val subscriber: Behavior[Receptionist.Listing] = Behaviors.setup { _ =>
          Behaviors.receiveMessage { listing =>
            val instances = listing.serviceInstances(key)
            if isSkill then skillIndex.put(id, instances) else capIndex.put(id, instances)
            Behaviors.same
          }
        }
        val ref = system.systemActorOf(subscriber, name)
        system.receptionist ! Receptionist.Subscribe(key, ref)
        subsMap.put(id, ref)

  // ---------------------- Registration (backwards compatible) ----------------------
  def register(agentRef: ActorRef[BaseAgent.Command], capability: AgentCapability): Future[Unit] =
    val key = Keys.capabilityKey(capability.name)
    ensureSubscribed(key, capability.name.trim.toLowerCase, isSkill = false)
    system.receptionist ! Receptionist.Register(key, agentRef)
    Future.successful(())

  // Extended registration with skills
  def register(
      agentRef: ActorRef[BaseAgent.Command],
      capability: AgentCapability,
      skills: Set[String]
  ): Future[Unit] =
    val capId = capability.name.trim.toLowerCase
    val capKey = Keys.capabilityKey(capId)
    ensureSubscribed(capKey, capId, isSkill = false)
    system.receptionist ! Receptionist.Register(capKey, agentRef)

    skills.foreach { s =>
      val id  = s.trim.toLowerCase
      val key = Keys.skillKey(id)
      ensureSubscribed(key, id, isSkill = true)
      system.receptionist ! Receptionist.Register(key, agentRef)
    }
    Future.successful(())

  def registerSkill(agentRef: ActorRef[BaseAgent.Command], skill: String): Future[Unit] =
    val id  = skill.trim.toLowerCase
    val key = Keys.skillKey(id)
    ensureSubscribed(key, id, isSkill = true)
    system.receptionist ! Receptionist.Register(key, agentRef)
    Future.successful(())

  def registerSkills(agentRef: ActorRef[BaseAgent.Command], skills: Set[String]): Future[Unit] =
    skills.foreach { s =>
      val id  = s.trim.toLowerCase
      val key = Keys.skillKey(id)
      ensureSubscribed(key, id, isSkill = true)
      system.receptionist ! Receptionist.Register(key, agentRef)
    }
    Future.successful(())

  // ---------------------- Deregistration ----------------------
  def deregister(agentRef: ActorRef[BaseAgent.Command], capability: AgentCapability): Future[Unit] =
    val id  = capability.name.trim.toLowerCase
    val key = Keys.capabilityKey(id)
    system.receptionist ! Receptionist.Deregister(key, agentRef)
    Future.successful(())

  def deregisterSkill(agentRef: ActorRef[BaseAgent.Command], skill: String): Future[Unit] =
    val id  = skill.trim.toLowerCase
    val key = Keys.skillKey(id)
    system.receptionist ! Receptionist.Deregister(key, agentRef)
    Future.successful(())

  def deregisterSkills(agentRef: ActorRef[BaseAgent.Command], skills: Set[String]): Future[Unit] =
    skills.foreach { s =>
      val id  = s.trim.toLowerCase
      val key = Keys.skillKey(id)
      system.receptionist ! Receptionist.Deregister(key, agentRef)
    }
    Future.successful(())

  // ---------------------- Capability lookups ----------------------
  def findAgent(capability: String): Future[Option[ActorRef[BaseAgent.Command]]] =
    val id  = capability.trim.toLowerCase
    val key = Keys.capabilityKey(id)
    if useLocalCache then
      ensureSubscribed(key, id, isSkill = false)
      Future.successful(capIndex.getOrElse(id, Set.empty).headOption)
    else
      system.receptionist
        .ask[Receptionist.Listing](Receptionist.Find(key, _))
        .map(_.serviceInstances(key).headOption)

  def findAllAgents(agentType: String): Future[Set[ActorRef[BaseAgent.Command]]] =
    val id  = agentType.trim.toLowerCase
    val key = Keys.capabilityKey(id)
    if useLocalCache then
      ensureSubscribed(key, id, isSkill = false)
      Future.successful(capIndex.getOrElse(id, Set.empty))
    else
      system.receptionist
        .ask[Receptionist.Listing](Receptionist.Find(key, _))
        .map(_.allServiceInstances(key))

  // ---------------------- Skill lookups ----------------------
  /** All agents that declare a given skill. */
  def findAgentsBySkill(skill: String): Future[Set[ActorRef[BaseAgent.Command]]] =
    val id  = skill.trim.toLowerCase
    val key = Keys.skillKey(id)
    if useLocalCache then
      ensureSubscribed(key, id, isSkill = true)
      Future.successful(skillIndex.getOrElse(id, Set.empty))
    else
      system.receptionist
        .ask[Receptionist.Listing](Receptionist.Find(key, _))
        .map(_.allServiceInstances(key))

  /** Union: agents that have ANY of the provided skills. */
  def findAgentsByAnySkill(skills: Set[String]): Future[Set[ActorRef[BaseAgent.Command]]] =
    val ids = skills.map(_.trim.toLowerCase).filter(_.nonEmpty)
    if ids.isEmpty then Future.successful(Set.empty)
    else if useLocalCache then
      ids.foreach(id => ensureSubscribed(Keys.skillKey(id), id, isSkill = true))
      Future.successful(ids.flatMap(id => skillIndex.getOrElse(id, Set.empty)).toSet)
    else
      Future
        .traverse(ids) { id =>
          val key = Keys.skillKey(id)
          system.receptionist
            .ask[Receptionist.Listing](Receptionist.Find(key, _))
            .map(_.allServiceInstances(key))
        }
        .map(_.foldLeft(Set.empty[ActorRef[BaseAgent.Command]])(_ ++ _))

  /** Intersection: agents that have ALL of the provided skills. */
  def findAgentsByAllSkills(skills: Set[String]): Future[Set[ActorRef[BaseAgent.Command]]] =
    val ids = skills.map(_.trim.toLowerCase).filter(_.nonEmpty)
    if ids.isEmpty then Future.successful(Set.empty)
    else if useLocalCache then
      ids.foreach(id => ensureSubscribed(Keys.skillKey(id), id, isSkill = true))
      val sets = ids.toList.map(id => skillIndex.getOrElse(id, Set.empty))
      Future.successful(sets.drop(1).foldLeft(sets.headOption.getOrElse(Set.empty))(_ intersect _))
    else
      Future
        .traverse(ids) { id =>
          val key = Keys.skillKey(id)
          system.receptionist
            .ask[Receptionist.Listing](Receptionist.Find(key, _))
            .map(_.allServiceInstances(key))
        }
        .map { sets => sets.drop(1).foldLeft(sets.headOption.getOrElse(Set.empty))(_ intersect _) }
