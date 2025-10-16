import { useEffect, useMemo, useRef, useState } from "react";
import AgentPanels from "./AgentPanels.jsx";
import Graph from "./Graph.jsx";
import "./app.css";

function useTelemetry() {
  const [connected, setConnected] = useState(false);
  const [events, setEvents] = useState([]);
  const wsRef = useRef(null);

  useEffect(() => {
    const url =
      import.meta.env.VITE_TELEMETRY_WS || "ws://localhost:6061/ws";
    const ws = new WebSocket(url);
    wsRef.current = ws;

    ws.onopen = () => setConnected(true);
    ws.onclose = () => setConnected(false);
    ws.onerror = () => setConnected(false);
    ws.onmessage = (msg) => {
      try {
        const ev = JSON.parse(msg.data);
        setEvents((prev) => [...prev, ev]);
      } catch (e) {
        // Ignore malformed payload
      }
    };

    return () => {
      try {
        ws.close();
      } catch (_) {}
    };
  }, []);

  return { connected, events };
}

function buildModel(events) {
  // Aggregate by conversation
  const conversations = new Map(); // id -> { steps: Map, agents: Map, edges: [] }
  // Global agent activity summary
  const agents = new Map(); // agentName -> { events: [{type,timestamp,details}], lastStepId, lastMsgId }

  for (const ev of events) {
    const cid = ev.conversationId || "n/a";
    if (!conversations.has(cid)) {
      conversations.set(cid, {
        steps: new Map(), // stepId -> { capability, status: 'ready'|'dispatched'|'completed', messageId? }
        edges: [], // { from, to, label }
        chat: [], // [{ role, text, messageId, agent }]
        // Nodes are derived from steps + agents + coordinator
      });
    }
    const conv = conversations.get(cid);

    const ts = Date.now();
    const addAgentEvent = (agent, payload) => {
      const a = agents.get(agent) || { events: [], lastStepId: null, lastMsgId: null };
      a.events.push({ at: ts, ...payload });
      if (payload.stepId) a.lastStepId = payload.stepId;
      if (payload.messageId) a.lastMsgId = payload.messageId;
      agents.set(agent, a);
    };

    switch (ev.type) {
      case "plan": {
        // ev.steps: [{id, capability, dependencies:[]}]
        for (const s of ev.steps || []) {
          conv.steps.set(s.id, {
            capability: s.capability,
            status: "ready",
          });
          // Record dependencies as edges between steps
          for (const dep of s.dependencies || []) {
            conv.edges.push({
              from: dep,
              to: s.id,
              label: "dep",
            });
          }
        }
        break;
      }
      case "dispatch": {
        const { stepId, capability, messageId } = ev;
        const s = conv.steps.get(stepId) || { capability, status: "ready" };
        s.capability = capability || s.capability;
        s.status = "dispatched";
        s.messageId = messageId;
        conv.steps.set(stepId, s);
        // Edge: coordinator -> capability (agent)
        conv.edges.push({
          from: "coordinator",
          to: capability,
          label: stepId,
        });
        break;
      }
      case "stepCompleted": {
        const { stepId } = ev;
        const s = conv.steps.get(stepId);
        if (s) {
          s.status = "completed";
          conv.steps.set(stepId, s);
        }
        break;
      }
      case "aggregate": {
        // final aggregate done (no-op for graph, could add special marker)
        break;
      }
      case "agentStart": {
        const { agent, stepId, messageId, refinement } = ev;
        addAgentEvent(agent, {
          type: "start",
          stepId,
          messageId,
          refinement,
        });
        // agent node emits work (agent -> step)
        conv.edges.push({
          from: agent,
          to: stepId,
          label: "start",
        });
        break;
      }
      case "agentComplete": {
        const { agent, stepId, responseMessageId, textLength } = ev;
        addAgentEvent(agent, {
          type: "complete",
          stepId,
          responseMessageId,
          textLength,
        });
        // step -> coordinator (result)
        conv.edges.push({
          from: stepId,
          to: "coordinator",
          label: "done",
        });
        break;
      }
      case "chat": {
        const { role, messageId, text, agent } = ev;
        conv.chat.push({ role, messageId, text, agent: agent || null });
        break;
      }
      case "error": {
        const { message } = ev;
        addAgentEvent("coordinator", { type: "error", message });
        break;
      }
      default:
        break;
    }
  }

  // Collect unique agent names from steps/edges and agent activity
  const allAgentNames = new Set(
    Array.from(agents.keys())
  );
  for (const [, conv] of conversations) {
    for (const [, s] of conv.steps) {
      if (s.capability) allAgentNames.add(s.capability);
    }
    for (const e of conv.edges) {
      if (e.from !== "coordinator" && !conv.steps.has(e.from)) {
        allAgentNames.add(e.from);
      }
      if (e.to !== "coordinator" && !conv.steps.has(e.to)) {
        allAgentNames.add(e.to);
      }
    }
  }

  return {
    agents: new Map(
      Array.from(allAgentNames).map((name) => {
        return [name, agents.get(name) || { events: [], lastStepId: null, lastMsgId: null }];
      })
    ),
    conversations,
  };
}

// Draggable floating steps panel
function StepPanel({ conv }) {
  const panelRef = useRef(null);
  const draggingRef = useRef(false);
  const offsetRef = useRef({ x: 0, y: 0 });

  const [pos, setPos] = useState(() => {
    try {
      const saved = localStorage.getItem("stepPanelPos");
      if (saved) return JSON.parse(saved);
    } catch {}
    const w = typeof window !== "undefined" ? window.innerWidth : 1200;
    const h = typeof window !== "undefined" ? window.innerHeight : 800;
    return { x: Math.max(16, w - 560), y: Math.max(80, h - 360) };
  });

  useEffect(() => {
    function onMove(e) {
      if (!draggingRef.current) return;
      const node = panelRef.current;
      const w = window.innerWidth;
      const h = window.innerHeight;
      const rectW = node ? node.offsetWidth : 520;
      const rectH = node ? node.offsetHeight : 280;

      let nx = e.clientX - offsetRef.current.x;
      let ny = e.clientY - offsetRef.current.y;

      nx = Math.min(Math.max(8, nx), Math.max(8, w - rectW - 8));
      ny = Math.min(Math.max(56, ny), Math.max(56, h - rectH - 8));

      setPos({ x: nx, y: ny });
    }
    function onUp() {
      if (!draggingRef.current) return;
      draggingRef.current = false;
      try {
        localStorage.setItem("stepPanelPos", JSON.stringify(pos));
      } catch {}
    }
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
    return () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
  }, [pos]);

  const onMouseDown = (e) => {
    const node = panelRef.current;
    if (!node) return;
    draggingRef.current = true;
    offsetRef.current = {
      x: e.clientX - (pos?.x ?? 0),
      y: e.clientY - (pos?.y ?? 0),
    };
    e.preventDefault();
  };

  if (!conv) return null;

  return (
    <div
      ref={panelRef}
      className="stepPanel"
      style={{ left: `${pos.x}px`, top: `${pos.y}px` }}
    >
      <div className="stepPanel__header" onMouseDown={onMouseDown} title="Drag to reposition">
        <h3>Steps</h3>
        <span className="dragHint">Drag</span>
      </div>
      <div className="stepPanel__body">
        <table>
          <thead>
            <tr>
              <th>Step Id</th>
              <th>Capability</th>
              <th>Status</th>
              <th>Message Id</th>
            </tr>
          </thead>
          <tbody>
            {Array.from(conv.steps.entries()).map(([id, s]) => (
              <tr key={id}>
                <td>{id}</td>
                <td>{s.capability}</td>
                <td className={`status status--${s.status}`}>{s.status}</td>
                <td className="mono">{s.messageId || "-"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/* Draggable floating conversation panel */
function ConversationPanel({ conv }) {
  const panelRef = useRef(null);
  const draggingRef = useRef(false);
  const offsetRef = useRef({ x: 0, y: 0 });

  const [pos, setPos] = useState(() => {
    try {
      const saved = localStorage.getItem("consolePanelPos");
      if (saved) return JSON.parse(saved);
    } catch {}
    const w = typeof window !== "undefined" ? window.innerWidth : 1200;
    return { x: Math.max(16, w - 620), y: 80 };
  });

  useEffect(() => {
    function onMove(e) {
      if (!draggingRef.current) return;
      const node = panelRef.current;
      const w = window.innerWidth;
      const h = window.innerHeight;
      const rectW = node ? node.offsetWidth : 600;
      const rectH = node ? node.offsetHeight : 320;

      let nx = e.clientX - offsetRef.current.x;
      let ny = e.clientY - offsetRef.current.y;

      nx = Math.min(Math.max(8, nx), Math.max(8, w - rectW - 8));
      ny = Math.min(Math.max(56, ny), Math.max(56, h - rectH - 8));

      setPos({ x: nx, y: ny });
    }
    function onUp() {
      if (!draggingRef.current) return;
      draggingRef.current = false;
      try {
        localStorage.setItem("consolePanelPos", JSON.stringify(pos));
      } catch {}
    }
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
    return () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
  }, [pos]);

  const onMouseDown = (e) => {
    draggingRef.current = true;
    offsetRef.current = {
      x: e.clientX - (pos?.x ?? 0),
      y: e.clientY - (pos?.y ?? 0),
    };
    e.preventDefault();
  };

  if (!conv) return null;

  const items = (conv.chat || []).map((m, idx) => (
    <div key={m.messageId || idx} className={`msgRow msgRow--${m.role}`}>
      <div className={`bubble bubble--${m.role}`}>
        <div className="meta">
          <span className="role">{m.role}</span>
          {m.agent ? <span className="agent mono">@{m.agent}</span> : null}
          {m.messageId ? <span className="msgid mono">{m.messageId}</span> : null}
        </div>
        <div className="text">{m.text}</div>
      </div>
    </div>
  ));

  return (
    <div
      ref={panelRef}
      className="consolePanel"
      style={{ left: `${pos.x}px`, top: `${pos.y}px` }}
    >
      <div className="consolePanel__header" onMouseDown={onMouseDown} title="Drag to reposition">
        <h3>Conversation</h3>
        <span className="dragHint">Drag</span>
      </div>
      <div className="consolePanel__body">
        {items.length ? items : <div className="muted">No messages yet.</div>}
      </div>
    </div>
  );
}

/* Docked tabs attached to main canvas: Conversation and Steps with scroll */
function DockPanels({ conv }) {
  const [tab, setTab] = useState(() => {
    try {
      return localStorage.getItem("dockTab") || "conversation";
    } catch {
      return "conversation";
    }
  });

  useEffect(() => {
    try {
      localStorage.setItem("dockTab", tab);
    } catch {}
  }, [tab]);

  if (!conv) return null;

  const chatItems = (conv.chat || []).map((m, idx) => (
    <div key={m.messageId || idx} className={`msgRow msgRow--${m.role}`}>
      <div className={`bubble bubble--${m.role}`}>
        <div className="meta">
          <span className="role">{m.role}</span>
          {m.agent ? <span className="agent mono">@{m.agent}</span> : null}
          {m.messageId ? <span className="msgid mono">{m.messageId}</span> : null}
        </div>
        <div className="text">{m.text}</div>
      </div>
    </div>
  ));

  return (
    <div className="dock">
      <div className="dock__tabs">
        <button
          className={`dock__tab ${tab === "conversation" ? "dock__tab--active" : ""}`}
          onClick={() => setTab("conversation")}
        >
          Conversation
        </button>
        <button
          className={`dock__tab ${tab === "steps" ? "dock__tab--active" : ""}`}
          onClick={() => setTab("steps")}
        >
          Steps
        </button>
      </div>
      <div className="dock__body">
        {tab === "conversation" ? (
          <div className="dock__pane">
            {chatItems.length ? chatItems : <div className="muted">No messages yet.</div>}
          </div>
        ) : (
          <div className="dock__pane">
            <table>
              <thead>
                <tr>
                  <th>Step Id</th>
                  <th>Capability</th>
                  <th>Status</th>
                  <th>Message Id</th>
                </tr>
              </thead>
              <tbody>
                {Array.from(conv.steps.entries()).map(([id, s]) => (
                  <tr key={id}>
                    <td>{id}</td>
                    <td>{s.capability}</td>
                    <td className={`status status--${s.status}`}>{s.status}</td>
                    <td className="mono">{s.messageId || "-"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

export default function App() {
  const { connected, events } = useTelemetry();
  const model = useMemo(() => buildModel(events), [events]);

  // Compute tabs for conversations
  const convIds = useMemo(() => Array.from(model.conversations.keys()), [model]);
  const [activeConv, setActiveConv] = useState(convIds[0] || null);

  useEffect(() => {
    if (!activeConv && convIds.length > 0) {
      setActiveConv(convIds[0]);
    }
  }, [convIds, activeConv]);

  const conv = activeConv ? model.conversations.get(activeConv) : null;

  return (
    <div className="app">
      <header className="app__header">
        <h1>Multi-Agent Orchestrator</h1>
        <div className={`conn conn--${connected ? "ok" : "down"}`}>
          WS {connected ? "Connected" : "Disconnected"}
        </div>
      </header>

      <div className="app__content">
        <aside className="sidebar">
          <h2>Agents</h2>
          <AgentPanels agents={model.agents} />
        </aside>

        <main className="main">
          <div className="tabs">
            {convIds.map((cid) => (
              <button
                key={cid}
                className={`tab ${activeConv === cid ? "tab--active" : ""}`}
                onClick={() => setActiveConv(cid)}
                title={cid}
              >
                {cid}
              </button>
            ))}
          </div>

          {!conv ? (
            <div className="empty">No conversations yet. Trigger a task to see activity.</div>
          ) : (
            <>
              <div className="graphSection">
                <Graph conversationId={activeConv} conv={conv} />
              </div>
              <DockPanels conv={conv} />
            </>
          )}
        </main>
      </div>
    </div>
  );
}
