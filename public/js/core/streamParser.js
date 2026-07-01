// Client-side agent adapter: translates Claude's raw stream-json events into
// neutral domain events the UI understands. Codex/Gemini get their own parser;
// the rest of the frontend never sees a vendor-specific event shape.

export function parseClaudeEvent(ev) {
  if (ev.type === "_gateway") {
    if (ev.subtype === "connected") return { kind: "connected" };
    if (ev.subtype === "turn_start") return { kind: "turnStart" };
    if (ev.subtype === "turn_end") return { kind: "turnEnd", title: ev.title };
    // Replay of an in-progress turn, sent when (re)subscribing to a working session.
    if (ev.subtype === "snapshot") return { kind: "snapshot", parts: ev.parts || [], busy: !!ev.busy };
    if (ev.subtype === "stopped") return { kind: "stopped" };
    if (ev.subtype === "permission_request") return { kind: "permission", id: ev.id, tool: ev.tool, input: ev.input };
    if (ev.subtype === "question_request") return { kind: "question", id: ev.id, questions: ev.input?.questions || [] };
    // A gated tool the gateway auto-approved (no card shown) — surface it so the UI
    // shows what the agent ran (a diff card for edits/Bash, a chip otherwise). Full
    // input is kept so the diff is renderable.
    if (ev.subtype === "tool") return { kind: "tool", tool: { name: ev.tool, input: ev.input, id: ev.id } };
    // Output of a tool (Bash stdout, Read contents, …), attached to its card by id.
    if (ev.subtype === "tool_result") return { kind: "toolResult", id: ev.id, output: ev.output, isError: ev.isError };
    if (ev.subtype === "file") return { kind: "file", file: { name: ev.name, caption: ev.caption, url: ev.url } };
    if (ev.subtype === "stderr") return { kind: "error", text: ev.text };
    return { kind: "ignore" };
  }
  if (ev.type === "stream_event" && ev.event?.type === "content_block_delta") {
    const d = ev.event.delta;
    if (d?.type === "text_delta") return { kind: "text", text: d.text };
    if (d?.type === "thinking_delta") return { kind: "thinking", text: d.thinking };
    return { kind: "ignore" };
  }
  if (ev.type === "assistant" && ev.message) {
    const content = ev.message.content || [];
    // Slash-command output (/cost, /context, …) arrives as one complete
    // "<synthetic>" message — not streamed deltas — so surface its text directly.
    if (ev.message.model === "<synthetic>") {
      const text = content.filter((b) => b.type === "text").map((b) => b.text).join("");
      return text ? { kind: "text", text } : { kind: "ignore" };
    }
    // AskUserQuestion is handled interactively via the gateway's question_request
    // (the PreToolUse hook forwards it to the phone and feeds the answer back), so
    // the assistant tool_use itself is suppressed here — see GATED in the controller.
    const tools = content
      .filter((b) => b.type === "tool_use")
      // Full input (not a truncated string) so the card header can show the path
      // and the body the real arguments. Read-only tool inputs are small.
      .map((b) => ({ name: b.name, input: b.input, id: b.id }));
    return tools.length ? { kind: "tools", tools } : { kind: "ignore" };
  }
  return { kind: "ignore" };
}
