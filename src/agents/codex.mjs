import { spawn } from "node:child_process";
import { config } from "../config.mjs";

// Codex adapter. Codex's `exec --json` emits whole items (no token deltas), so
// we translate its events into the same Claude-shaped events the gateway and UI
// already understand — one text_delta carrying the full reply (the client's
// typewriter still reveals it smoothly). Declares its own native control:
// `reasoning` (Codex's name), not Claude's `effort`/`thinking`.
//
// Note: Codex runs in its own sandbox; phone-interactive permissions and the
// send_to_user tool are Claude-only for now.

export const codexAgent = {
  id: "codex",
  label: "Codex",

  controls: {
    model: {
      label: "Model",
      default: "gpt-5.5",
      options: [
        { value: "gpt-5.5", label: "GPT-5.5" },
        { value: "gpt-5.4", label: "GPT-5.4" },
        { value: "gpt-5.4-mini", label: "GPT-5.4-Mini" },
      ],
    },
    reasoning: {
      label: "Reasoning",
      default: "",
      options: [
        { value: "", label: "Default" },
        { value: "low", label: "Low" },
        { value: "medium", label: "Medium" },
        { value: "high", label: "High" },
        { value: "xhigh", label: "Extra High" },
      ],
    },
  },

  run({ text, controls = {}, resumeId, cwd, onEvent, onError, onClose }) {
    const args = ["exec"];
    if (resumeId) args.push("resume", resumeId);
    args.push("--json", "--skip-git-repo-check");
    if (!resumeId) args.push("-s", "workspace-write"); // resume inherits the session's sandbox
    if (controls.model) args.push("-m", controls.model);
    if (controls.reasoning) args.push("-c", `model_reasoning_effort=${controls.reasoning}`);
    args.push("-"); // read the prompt from stdin

    const child = spawn("codex", args, { shell: config.isWin, cwd: cwd || undefined });

    let buf = "";
    child.stdout.on("data", (chunk) => {
      buf += chunk.toString();
      let nl;
      while ((nl = buf.indexOf("\n")) !== -1) {
        const line = buf.slice(0, nl).trim();
        buf = buf.slice(nl + 1);
        if (!line) continue;
        let evt;
        try { evt = JSON.parse(line); } catch { continue; }
        translate(evt, onEvent);
      }
    });
    child.stderr.on("data", (d) => onError && onError(d.toString()));
    child.on("close", (code) => onClose && onClose(code));

    child.stdin.write(text);
    child.stdin.end();
    return child;
  },
};

// Codex event -> Claude-shaped gateway event.
function translate(evt, onEvent) {
  if (evt.type === "thread.started" && evt.thread_id) {
    // carry the resume id the same way Claude's `result.session_id` does
    onEvent({ type: "result", session_id: evt.thread_id });
    return;
  }
  if (evt.type === "item.completed" && evt.item) {
    const item = evt.item;
    if (item.type === "agent_message" && typeof item.text === "string") {
      onEvent({ type: "stream_event", event: { type: "content_block_delta", delta: { type: "text_delta", text: item.text } } });
    } else if (item.type !== "reasoning") {
      // command runs, file edits, etc. -> show as a tool chip
      onEvent({ type: "assistant", message: { content: [{ type: "tool_use", name: item.type || "item", input: item }] } });
    }
  }
}
