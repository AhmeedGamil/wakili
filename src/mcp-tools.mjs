// Minimal MCP stdio server (no SDK) exposing tools the agent can call to reach
// the user's phone. Currently: send_to_user(path) — hands a local file to the
// gateway, which serves it for download and notifies the phone. Spawned by
// Claude Code per turn; session id + gateway url arrive via env.

import http from "node:http";
import fs from "node:fs";
import path from "node:path";

const SESSION = process.env.WAKILI_SESSION || "";
const GATEWAY = process.env.WAKILI_GATEWAY || "http://127.0.0.1:8730";
const TOKEN = process.env.WAKILI_TOKEN || "";

const send = (msg) => process.stdout.write(JSON.stringify(msg) + "\n");

// The tool descriptions double as the phone directives: they are the one channel
// every client (Claude CLI, Claude SDK, Codex) reliably puts in front of the
// model, without polluting the prompt text — so the "user is on a phone, you
// MUST ..." instructions live HERE, not in per-adapter prompt injection.
const SEND_TOOL = {
  name: "send_to_user",
  description:
    "Send a file (image, APK, PDF, screenshot, anything) directly to the user's phone. " +
    "The user is chatting from a phone with NO filesystem access — a file path printed " +
    "in your reply is useless to them. Whenever you create, build, generate, screenshot, " +
    "or otherwise end up with a file the user would want, you MUST call this tool with " +
    "the file's absolute path to deliver it. Do it proactively as soon as the file " +
    "exists — do not wait to be asked, and never just print the path and stop.",
  inputSchema: {
    type: "object",
    properties: {
      path: { type: "string", description: "Absolute path to the file to send." },
      caption: { type: "string", description: "Optional short caption shown with the file." },
    },
    required: ["path"],
  },
};

const ASK_TOOL = {
  name: "ask_options",
  description:
    "Ask the user one or more multiple-choice questions and WAIT for their answer. The user is " +
    "on a phone: whenever you need them to make a choice, a decision, a confirmation, or to pick " +
    "between options, you MUST use this tool — never ask by writing the question as plain text, " +
    "because the phone cannot turn prose into an interactive card. It shows tappable buttons and " +
    "pauses the turn until they answer, then returns their choice. An 'Other' choice with a " +
    "free-text box is added to every question automatically, so the user can always type a custom " +
    "answer. Only ask in plain prose for genuinely open-ended input that has no discrete options.",
  inputSchema: {
    type: "object",
    properties: {
      questions: {
        type: "array",
        description: "1–4 questions to ask the user.",
        items: {
          type: "object",
          properties: {
            question: { type: "string", description: "The question to ask." },
            header: { type: "string", description: "Short category label shown above the question." },
            multiSelect: { type: "boolean", description: "Allow selecting more than one option." },
            options: { type: "array", items: { type: "string" }, description: "The choices to offer (an 'Other' box is added automatically)." },
          },
          required: ["question", "options"],
        },
      },
    },
    required: ["questions"],
  },
};

let buf = "";
process.stdin.on("data", (c) => {
  buf += c.toString();
  let nl;
  while ((nl = buf.indexOf("\n")) !== -1) {
    const line = buf.slice(0, nl).trim();
    buf = buf.slice(nl + 1);
    if (!line) continue;
    let msg;
    try { msg = JSON.parse(line); } catch { continue; }
    handle(msg);
  }
});

function handle(msg) {
  const { id, method, params } = msg;
  if (method === "initialize") {
    send({
      jsonrpc: "2.0",
      id,
      result: {
        protocolVersion: params?.protocolVersion || "2024-11-05",
        capabilities: { tools: {} },
        serverInfo: { name: "wakili", version: "1.0.0" },
      },
    });
  } else if (method === "tools/list") {
    send({ jsonrpc: "2.0", id, result: { tools: [SEND_TOOL, ASK_TOOL] } });
  } else if (method === "tools/call") {
    handleCall(id, params);
  } else if (method === "notifications/initialized" || method?.startsWith("notifications/")) {
    // notifications take no response
  } else if (id != null) {
    send({ jsonrpc: "2.0", id, error: { code: -32601, message: "method not found" } });
  }
}

function handleCall(id, params) {
  const name = params?.name;
  const args = params?.arguments || {};
  if (name === "send_to_user") return handleSend(id, args);
  if (name === "ask_options") return handleAsk(id, args);
  send({ jsonrpc: "2.0", id, result: { content: [{ type: "text", text: "unknown tool: " + name }], isError: true } });
}

async function handleSend(id, args) {
  const filePath = args.path;
  try {
    if (!filePath || !fs.existsSync(filePath)) throw new Error("file not found: " + filePath);
    await postJson(GATEWAY + "/internal/file", JSON.stringify({ sessionId: SESSION, path: filePath, caption: args.caption || "" }));
    send({ jsonrpc: "2.0", id, result: { content: [{ type: "text", text: "Delivered to the user's phone: " + path.basename(filePath) }] } });
  } catch (e) {
    send({ jsonrpc: "2.0", id, result: { content: [{ type: "text", text: "Failed to send: " + e.message }], isError: true } });
  }
}

// Ask the user multiple-choice question(s) and block until they answer on the phone.
async function handleAsk(id, args) {
  try {
    const questions = Array.isArray(args.questions) ? args.questions : [];
    if (!questions.length) throw new Error("no questions provided");
    const r = await postJson(GATEWAY + "/internal/question", JSON.stringify({ sessionId: SESSION, questions }), 310000);
    const answer = (r && r.answer) ? r.answer : "(the user did not answer)";
    send({ jsonrpc: "2.0", id, result: { content: [{ type: "text", text: "The user answered:\n" + answer }] } });
  } catch (e) {
    send({ jsonrpc: "2.0", id, result: { content: [{ type: "text", text: "ask_options failed: " + e.message }], isError: true } });
  }
}

// POST JSON and resolve with the parsed response body (the question flow needs the reply).
function postJson(url, body, timeoutMs) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const req = http.request(
      { hostname: u.hostname, port: u.port, path: u.pathname, method: "POST", headers: { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(body), "x-auth-token": TOKEN } },
      (res) => {
        let b = "";
        res.on("data", (c) => (b += c));
        res.on("end", () => {
          if (res.statusCode < 200 || res.statusCode >= 300) {
            return reject(new Error(`gateway responded ${res.statusCode}: ${b.slice(0, 200)}`));
          }
          try { resolve(JSON.parse(b)); } catch { resolve({}); }
        });
      }
    );
    req.on("error", reject);
    if (timeoutMs) req.setTimeout(timeoutMs, () => { req.destroy(new Error("timed out waiting for answer")); });
    req.write(body);
    req.end();
  });
}
