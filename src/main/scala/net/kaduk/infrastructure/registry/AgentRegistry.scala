package net.kaduk.infrastructure.registry

import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.receptionist.{Receptionist, ServiceKey}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.util.Timeout
import net.kaduk.domain.{AgentCapability, AgentMetadata, AgentStatus}
import net.kaduk.agents.BaseAgent
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration.*

class AgentRegistry(using system: ActorSystem[?], ec: ExecutionContext):
  
  private given Timeout = 5.seconds
  
  def register(agentRef: ActorRef[BaseAgent.Command], capability: AgentCapability): Future[Unit] =
    val serviceKey = ServiceKey[BaseAgent.Command](capability.name)
    system.receptionist ! Receptionist.Register(serviceKey, agentRef)
    Future.successful(())

  def findAgent(capability: String): Future[Option[ActorRef[BaseAgent.Command]]] =
    system.receptionist.ask[Receptionist.Listing]: replyTo =>
      Receptionist.Find(ServiceKey[BaseAgent.Command](capability), replyTo)
    .map: listing =>
      listing.serviceInstances(ServiceKey[BaseAgent.Command](capability)).headOption

  def findAllAgents(agentType: String): Future[Set[ActorRef[BaseAgent.Command]]] =
    system.receptionist.ask[Receptionist.Listing]: replyTo =>
      Receptionist.Find(ServiceKey[BaseAgent.Command](agentType), replyTo)
    .map(_.allServiceInstances(ServiceKey[BaseAgent.Command](agentType)))

  def deregister(agentRef: ActorRef[BaseAgent.Command], capability: AgentCapability): Future[Unit] =
    val serviceKey = ServiceKey[BaseAgent.Command](capability.name)
    system.receptionist ! Receptionist.Deregister(serviceKey, agentRef)
    Future.successful(())
