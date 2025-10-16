import React from "react";

function formatTime(ts) {
  try {
    const d = new Date(ts);
    return d.toLocaleTimeString();
  } catch {
    return String(ts || "");
  }
}

export default function AgentPanels({ agents }) {
  if (!agents || !(agents instanceof Map)) {
    return <div className="empty">No agent data.</div>;
  }

  const items = Array.from(agents.entries()).map(([name, data]) => {
    const evs = (data && data.events) || [];
    const last = evs.slice(-5).reverse(); // last 5, newest first
    return (
      <div className="agentPanel" key={name}>
        <div className="agentPanel__header">
          <span className="badge agent">{name}</span>
          <span className="agentPanel__meta">
            events: {evs.length}
            {data.lastStepId ? <>, last step: <code className="mono">{data.lastStepId}</code></> : null}
          </span>
        </div>
        <ul className="agentPanel__events">
          {last.length === 0 ? (
            <li className="muted">No recent events</li>
          ) : (
            last.map((e, idx) => {
              return (
                <li key={idx}>
                  <span className={`pill pill--${e.type || "info"}`}>{e.type || "event"}</span>{" "}
                  <span className="time">{formatTime(e.at)}</span>{" "}
                  {e.stepId ? (
                    <>
                      step=<code className="mono">{e.stepId}</code>{" "}
                    </>
                  ) : null}
                  {e.messageId ? (
                    <>
                      msg=<code className="mono">{e.messageId}</code>{" "}
                    </>
                  ) : null}
                  {"textLength" in e ? <span>len={e.textLength}</span> : null}
                  {e.message ? <div className="err">{e.message}</div> : null}
                </li>
              );
            })
          )}
        </ul>
      </div>
    );
  });

  return <div className="agentPanels">{items}</div>;
}
