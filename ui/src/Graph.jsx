import { useEffect, useMemo, useRef } from "react";
import { Network } from "vis-network";
import "vis-network/styles/vis-network.css";

function toArrays(conv) {
  if (!conv) return { nodes: [], edges: [] };

  // Step nodes
  const stepEntries = Array.from(conv.steps.entries ? conv.steps.entries() : []);
  const stepIds = new Set(stepEntries.map(([id]) => id));

  const statusColor = (status) => {
    switch (status) {
      case "completed":
        return "#2ecc71"; // green
      case "dispatched":
        return "#f39c12"; // orange
      case "ready":
      default:
        return "#95a5a6"; // gray
    }
  };

  const stepNodes = stepEntries.map(([id, s]) => ({
    id,
    label: `${id}\n(${s.capability || "step"})`,
    group: "step",
    color: {
      background: statusColor(s.status),
      border: "#2c3e50",
    },
    shape: "box",
    font: { multi: true, face: "monospace", size: 12 },
  }));

  // Agent nodes (from edges that aren't steps and not coordinator)
  const agentNames = new Set();
  for (const e of conv.edges || []) {
    if (e.from && e.from !== "coordinator" && !stepIds.has(e.from)) agentNames.add(e.from);
    if (e.to && e.to !== "coordinator" && !stepIds.has(e.to)) agentNames.add(e.to);
  }

  const agentNodes = Array.from(agentNames).map((name) => ({
    id: name,
    label: name,
    group: "agent",
    color: { background: "#3498db", border: "#1f618d" },
    shape: "ellipse",
    font: { face: "monospace", size: 12 },
  }));

  // Coordinator node
  const coordNode = {
    id: "coordinator",
    label: "coordinator",
    group: "coordinator",
    color: { background: "#9b59b6", border: "#6c3483" },
    shape: "star",
    font: { face: "monospace", size: 12 },
  };

  // Edges
  const edges =
    (conv.edges || []).map((e) => ({
      from: e.from,
      to: e.to,
      arrows: { to: { enabled: true, scaleFactor: 0.7 } },
      label: e.label || "",
      font: { align: "horizontal", face: "monospace", size: 10 },
      color: { color: "#7f8c8d" },
      smooth: { type: "continuous" },
    })) || [];

  const nodes = [coordNode, ...agentNodes, ...stepNodes];
  return { nodes, edges };
}

export default function Graph({ conversationId, conv }) {
  const containerRef = useRef(null);
  const networkRef = useRef(null);

  const data = useMemo(() => toArrays(conv), [conv]);

  useEffect(() => {
    if (!containerRef.current) return;

    const options = {
      physics: {
        enabled: true,
        solver: "forceAtlas2Based",
        stabilization: { iterations: 150 },
        forceAtlas2Based: { gravitationalConstant: -40 },
      },
      layout: {
        improvedLayout: true,
      },
      nodes: {
        borderWidth: 2,
      },
      interaction: {
        hover: true,
        tooltipDelay: 100,
        hideEdgesOnDrag: false,
        hideEdgesOnZoom: false,
      },
      groups: {
        step: { shape: "box" },
        agent: { shape: "ellipse" },
        coordinator: { shape: "star" },
      },
    };

    if (!networkRef.current) {
      networkRef.current = new Network(containerRef.current, data, options);
    } else {
      networkRef.current.setData(data);
      networkRef.current.setOptions(options);
    }

    // Fit view to content
    const timer = setTimeout(() => {
      try {
        networkRef.current && networkRef.current.fit({ animation: { duration: 300, easingFunction: "easeInOutQuad" } });
      } catch {}
    }, 50);

    return () => clearTimeout(timer);
  }, [data]);

  return (
    <div className="graphContainer">
      <div className="graphHeader">
        <span className="mono">Conversation:</span>{" "}
        <span className="mono">{conversationId}</span>
      </div>
      <div ref={containerRef} className="graphCanvas" />
    </div>
  );
}
