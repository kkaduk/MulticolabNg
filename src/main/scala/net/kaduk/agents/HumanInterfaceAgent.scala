package net.kaduk.agents

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import net.kaduk.agents.BaseAgent

// Legacy HumanInterfaceAgent replaced by CoordinatorAgent + LLMAgent flow.
// Keeping a minimal no-op actor to maintain compatibility if referenced.
object HumanInterfaceAgent:
  def apply(): Behavior[BaseAgent.Command] =
    Behaviors.ignore
