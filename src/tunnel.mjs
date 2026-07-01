// Outside-home access. Two ways, both optional and chosen at launch:
//   - Tailscale (private): both devices on your tailnet; the phone reaches the
//     laptop's 100.x address. Nothing is exposed publicly. We just detect the IP.
//   - Cloudflare quick tunnel (public): spawn `cloudflared` to get a throwaway
//     https://*.trycloudflare.com URL reachable from any network. Because it's
//     public, the gateway's token gate (config.token) is what keeps it private.
//
// Neither is a dependency: if the tool isn't installed we return null / reject
// with a friendly message and the server still serves the LAN.

import { spawn, execFile } from "node:child_process";
import { config } from "./config.mjs";

// Detect a Tailscale IPv4 (100.64.0.0/10 CGNAT range). Returns a base URL or null.
export function tailscaleUrl(port) {
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => { if (!done) { done = true; resolve(v); } };
    try {
      execFile("tailscale", ["ip", "-4"], { shell: config.isWin, timeout: 5000 }, (err, stdout) => {
        if (err) return finish(null);
        const ip = String(stdout)
          .split(/\r?\n/)
          .map((s) => s.trim())
          .find((s) => /^\d{1,3}(\.\d{1,3}){3}$/.test(s));
        finish(ip ? `http://${ip}:${port}` : null);
      });
    } catch { finish(null); }
  });
}

// Start a Cloudflare quick tunnel. Resolves with { url, child } once the public
// URL appears (cloudflared prints it to stderr). Rejects if cloudflared is
// missing or no URL shows up in time. Caller owns killing `child` on shutdown.
export function startCloudflare(port) {
  return new Promise((resolve, reject) => {
    let child;
    try {
      child = spawn("cloudflared", ["tunnel", "--url", `http://localhost:${port}`], { shell: config.isWin });
    } catch (e) {
      return reject(new Error("cloudflared not found (install it, or use Tailscale): " + e.message));
    }

    let settled = false;
    const onData = (chunk) => {
      const m = chunk.toString().match(/https:\/\/[a-z0-9-]+\.trycloudflare\.com/i);
      if (m && !settled) {
        settled = true;
        clearTimeout(timer);
        resolve({ url: m[0], child });
      }
    };
    child.stdout.on("data", onData);
    child.stderr.on("data", onData); // cloudflared logs the URL on stderr
    child.on("error", (e) => {
      if (!settled) { settled = true; clearTimeout(timer); reject(new Error("cloudflared failed to start (is it installed?): " + e.message)); }
    });
    child.on("close", () => {
      if (!settled) { settled = true; clearTimeout(timer); reject(new Error("cloudflared exited before printing a URL")); }
    });
    const timer = setTimeout(() => {
      if (!settled) { settled = true; try { child.kill(); } catch {} reject(new Error("timed out waiting for the cloudflared URL")); }
    }, 30000);
  });
}
