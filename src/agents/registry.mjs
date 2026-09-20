// Agent registry. Register an adapter once; routes resolve agents by id.
// Adding Codex/Gemini later = one import + one register() call.

import { claudeAgent } from "./claude.mjs";
import { claudeSdkAgent } from "./claude-sdk.mjs";
import { codexAgent } from "./codex.mjs";
import { antigravityAgent, antigravityAvailable } from "./antigravity.mjs";

const agents = new Map();

export function register(agent) {
  agents.set(agent.id, agent);
}
export function getAgent(id) {
  return agents.get(id);
}
/** Public manifest the UI uses to render per-agent control panels + slash menu. */
export function listAgents() {
  for (const a of agents.values()) if (typeof a.refresh === "function") a.refresh(); // let adapters update stale controls (e.g. codex's model cache)
  return [...agents.values()].map((a) => ({
    id: a.id,
    label: a.label,
    description: a.description || "",
    controls: a.controls,
    commands: typeof a.commands === "function" ? a.commands() : (a.commands || []),
  }));
}

register(claudeAgent);
register(claudeSdkAgent);
register(codexAgent);
if (antigravityAvailable) register(antigravityAgent); // only where the agy CLI is installed
