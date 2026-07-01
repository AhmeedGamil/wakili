// Cloudflare bridge — opt-in, additive, touches no existing files.
//
// Cloudflare buffers Server-Sent Events, so the live stream never reaches the
// phone over a Cloudflare tunnel. This bridge sits in front of the gateway:
//   • it reverse-proxies ALL http to the gateway (:8730) unchanged, and
//   • adds ONE WebSocket endpoint (/cf-ws) that relays the gateway's SSE — read
//     on localhost where nothing buffers — to the phone as WebSocket messages,
//     which Cloudflare does NOT buffer.
// It also launches a Cloudflare quick tunnel pointed at itself and prints the
// ready-to-open cf.html URL (+ QR).
//
// Run it ALONGSIDE the gateway (the gateway is unchanged):
//     1) node server.mjs        # the gateway, exactly as before (no --tunnel)
//     2) node cf-bridge.mjs      # this bridge: proxy + WS relay + cloudflared
// Then open the printed  https://<...>.trycloudflare.com/cf.html?t=<token>

import http from "node:http";
import fs from "node:fs";
import crypto from "node:crypto";
import { spawn } from "node:child_process";
import { config } from "./src/config.mjs";
import { qrTerminal } from "./src/qr.mjs";

const PORT = Number(process.env.CF_BRIDGE_PORT) || 8731;
const GW_HOST = "127.0.0.1";
const GW_PORT = config.port; // the gateway, 8730

// ── reverse proxy: everything (app, /api, uploads, downloads) → gateway ──
const server = http.createServer((req, res) => {
  const preq = http.request(
    { host: GW_HOST, port: GW_PORT, method: req.method, path: req.url, headers: req.headers },
    (pres) => { res.writeHead(pres.statusCode || 502, pres.headers); pres.pipe(res); }
  );
  preq.on("error", () => { try { res.writeHead(502); res.end("bridge: gateway not reachable on :" + GW_PORT); } catch {} });
  req.pipe(preq);
});

// ── WebSocket endpoint: relay the gateway's SSE to the phone, unbuffered ──
const WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

server.on("upgrade", (req, socket) => {
  let u;
  try { u = new URL(req.url, "http://x"); } catch { socket.destroy(); return; }
  if (u.pathname !== "/cf-ws") { socket.destroy(); return; }
  const key = req.headers["sec-websocket-key"];
  if (!key) { socket.destroy(); return; }
  const accept = crypto.createHash("sha1").update(key + WS_MAGIC).digest("base64");
  socket.write(
    "HTTP/1.1 101 Switching Protocols\r\n" +
    "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
    "Sec-WebSocket-Accept: " + accept + "\r\n\r\n"
  );
  relay(socket, u.searchParams.get("path") || "/");
});

// Encode a server→client frame (unmasked, per RFC 6455).
function wsFrame(str, opcode) {
  const payload = Buffer.from(str, "utf8");
  const n = payload.length;
  let head;
  if (n < 126) head = Buffer.from([0x80 | opcode, n]);
  else if (n < 65536) { head = Buffer.alloc(4); head[0] = 0x80 | opcode; head[1] = 126; head.writeUInt16BE(n, 2); }
  else { head = Buffer.alloc(10); head[0] = 0x80 | opcode; head[1] = 127; head.writeUInt32BE(Math.floor(n / 4294967296), 2); head.writeUInt32BE(n >>> 0, 6); }
  return Buffer.concat([head, payload]);
}
const sendText = (socket, str) => { try { socket.write(wsFrame(str, 0x1)); } catch {} };
const sendPing = (socket) => { try { socket.write(Buffer.from([0x89, 0])); } catch {} };
const sendClose = (socket) => { try { socket.write(Buffer.from([0x88, 0])); } catch {} };

function relay(socket, path) {
  // open the gateway's SSE for this session (auth token rides in `path`'s query)
  const greq = http.request(
    { host: GW_HOST, port: GW_PORT, method: "GET", path, headers: { Accept: "text/event-stream" } },
    (gres) => {
      gres.setEncoding("utf8");
      let buf = "";
      gres.on("data", (chunk) => {
        buf += chunk;
        let i;
        while ((i = buf.indexOf("\n\n")) !== -1) {
          const block = buf.slice(0, i); buf = buf.slice(i + 2);
          // forward only the data: payload (EventSource semantics); skip :comments/pings
          const data = block.split("\n").filter((l) => l.startsWith("data:")).map((l) => l.slice(5).replace(/^ /, "")).join("\n");
          if (data) sendText(socket, data);
        }
      });
      gres.on("end", () => { sendClose(socket); try { socket.end(); } catch {} });
    }
  );
  greq.on("error", () => { sendClose(socket); try { socket.end(); } catch {} });
  greq.end();

  const ping = setInterval(() => sendPing(socket), 20000); // keep the connection warm through the proxy
  const cleanup = () => { clearInterval(ping); try { greq.destroy(); } catch {} };
  socket.on("close", cleanup);
  socket.on("error", cleanup);
  // tear down promptly on a client close frame (opcode 0x8)
  socket.on("data", (d) => { if (d.length && (d[0] & 0x0f) === 0x8) { sendClose(socket); try { socket.end(); } catch {} } });
}

// ── Cloudflare quick tunnel pointed at this bridge ──
function cloudflaredBin() {
  const win = "C:\\Program Files (x86)\\cloudflared\\cloudflared.exe";
  if (process.platform === "win32" && fs.existsSync(win)) return win;
  return "cloudflared";
}

// Tell the gateway our live (random) tunnel URL so the page's connection
// switcher can offer Cloudflare. Fire-and-forget; the gateway guards /internal.
function registerCfUrl(base) {
  const body = JSON.stringify({ url: base });
  const r = http.request(
    { host: GW_HOST, port: GW_PORT, method: "POST", path: "/internal/cf-url",
      headers: { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(body), "x-auth-token": config.token } },
    (res) => res.resume());
  r.on("error", () => {});
  r.write(body); r.end();
}

let cf = null;
function startTunnel() {
  try { cf = spawn(cloudflaredBin(), ["tunnel", "--url", "http://localhost:" + PORT]); }
  catch (e) { console.log("  Could not start cloudflared:", e.message); return; }
  let announced = false;
  const scan = (b) => {
    const m = /https:\/\/[a-z0-9-]+\.trycloudflare\.com/.exec(b.toString());
    if (m && !announced) {
      announced = true;
      registerCfUrl(m[0]); // base URL; the gateway appends /cf.html?t=…
      const url = `${m[0]}/cf.html?t=${config.token}`;
      console.log("\n  Cloudflare (WebSocket — no buffering). Open on your phone:\n");
      console.log("  " + url + "\n");
      try { console.log(qrTerminal(url).replace(/^/gm, "  ")); } catch { /* url too long for QR; skip */ }
      console.log("\n  ⚠ Public URL — the token guards it; change admin/admin before relying on it.\n");
    }
  };
  cf.stdout.on("data", scan);
  cf.stderr.on("data", scan);
  cf.on("close", () => console.log("  cloudflared exited."));
}

server.listen(PORT, () => {
  console.log(`\n  CF bridge on :${PORT}  →  proxying gateway :${GW_PORT}`);
  console.log("  (make sure the gateway is running:  node server.mjs)");
  startTunnel();
});

function shutdown() { if (cf) { try { cf.kill(); } catch {} } process.exit(0); }
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
