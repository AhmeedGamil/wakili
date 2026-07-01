// Agent registry. Register an adapter once; routes resolve agents by id.
// Adding Codex/Gemini later = one import + one register() call.

import { claudeAgent } from "./claude.mjs";
import { codexAgent } from "./codex.mjs";

const agents = new Map();

export function register(agent) {
  agents.set(agent.id, agent);
}
export function getAgent(id) {
  return agents.get(id);
}
/** Public manifest the UI uses to render per-agent control panels + slash menu. */
export function listAgents() {
  return [...agents.values()].map((a) => ({
    id: a.id,
    label: a.label,
    controls: a.controls,
    commands: typeof a.commands === "function" ? a.commands() : (a.commands || []),
  }));
}

register(claudeAgent);
register(codexAgent);
