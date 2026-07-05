// PreToolUse hook. Claude Code runs this before a gated tool, passing the tool
// name + input on stdin. We ask the gateway (which asks the phone) and return
// the allow/deny decision. Uses node:http (no fetch dependency) and denies on
// any error/timeout so a missing phone never silently allows.

import http from "node:http";

let data = "";
process.stdin.on("data", (c) => (data += c));
process.stdin.on("end", () => {
  let info = {};
  try { info = JSON.parse(data); } catch { /* ignore */ }

  const sessionId = process.env.WAKILI_SESSION || "";
  const gateway = process.env.WAKILI_GATEWAY || "http://127.0.0.1:8730";
  const token = process.env.WAKILI_TOKEN || "";
  const u = new URL(gateway + "/internal/permission");
  const payload = JSON.stringify({ sessionId, tool: info.tool_name, input: info.tool_input });

  // For an AskUserQuestion the gateway returns a "deny" decision whose reason IS
  // the user's answer: a denied tool's reason is surfaced to the model, so this
  // is how we inject the phone's answer back into a headless turn. A custom
  // reason (when present) overrides the default allow/deny text.
  const emit = (decision, reason) => {
    process.stdout.write(JSON.stringify({
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        permissionDecision: decision,
        permissionDecisionReason: reason || (decision === "allow" ? "Approved on phone" : "Denied on phone (or timed out)"),
      },
    }));
    process.exit(0);
  };

  const req = http.request(
    {
      hostname: u.hostname,
      port: u.port,
      path: u.pathname,
      method: "POST",
      headers: { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(payload), "x-auth-token": token },
    },
    (res) => {
      let b = "";
      res.on("data", (c) => (b += c));
      res.on("end", () => {
        let decision = "deny", reason = null;
        try { const j = JSON.parse(b); decision = j.decision === "allow" ? "allow" : "deny"; reason = j.reason || null; } catch { /* deny */ }
        emit(decision, reason);
      });
    }
  );
  req.on("error", () => emit("deny"));
  req.setTimeout(125000, () => { req.destroy(); emit("deny"); });
  req.write(payload);
  req.end();
});
