// Agent control values: an agent's declared defaults, layered with the
// last-used config (localStorage) and any explicit overrides — plus healing of
// stale saved values so old model ids never break the picker.

import { KEYS, getPref, setPref, getJSON, setJSON } from "../core/prefs.js";

// The last agent + controls actually used, applied as defaults for new chats so
// they keep your model/effort/thinking/mode instead of snapping back to the
// agent's built-in default (e.g. Sonnet).
export const loadLastConfig = () => getJSON(KEYS.lastConfig);
export const saveLastConfig = (agentId, controls) => setJSON(KEYS.lastConfig, { agentId, controls });

// The user's last-chosen permission mode, remembered as a global default so new
// sessions open in the same posture (notes #5). Per-session changes still win.
export const rememberPermMode = (mode) => setPref(KEYS.permMode, mode);

// Build an agent's control values from its declared defaults, then layer on the
// last-used controls for that agent, then any explicit overrides.
export function defaultsFor(agent, extra) {
  const c = {};
  for (const [k, ctl] of Object.entries(agent?.controls || {})) c[k] = ctl.default ?? "";
  const last = loadLastConfig();
  if (last && last.agentId === agent?.id && last.controls) {
    for (const k of Object.keys(c)) if (last.controls[k] != null) c[k] = last.controls[k];
  }
  if ("permissionMode" in c) { const g = getPref(KEYS.permMode); if (g) c.permissionMode = g; }
  return Object.assign(c, extra || {});
}

// Guard against stale saved values: if a control's saved value (from localStorage
// or a session's stored controls) is no longer among its current options — e.g. a
// model id that changed to an alias — reset it to that control's default. Without
// this the picker can't match the value and falls back to showing the agent label.
export function normalizeControls(agent, controls) {
  const out = { ...controls };
  for (const [k, ctl] of Object.entries(agent?.controls || {})) {
    // Model-scoped options (e.g. Codex effort): validate against the selected
    // model's list. `model` is declared first, so out.model is already healed.
    const options = (ctl.optionsFor && ctl.optionsFor[out.model]) || ctl.options;
    if (!Array.isArray(options)) continue;
    if (options.some((o) => o.value === out[k])) continue; // already a valid option
    // Legacy heal: map an old full model id ("claude-<family>-…") back to its
    // family alias so the prior choice is preserved; else fall back to default.
    const m = typeof out[k] === "string" && out[k].match(/^claude-([a-z]+)-/);
    const alias = m && ctl.options.find((o) => o.value === m[1]);
    out[k] = alias ? alias.value : (ctl.default ?? "");
  }
  return out;
}
