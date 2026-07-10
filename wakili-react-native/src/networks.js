// Saved networks (gateway computers), persisted in AsyncStorage. Each entry is
// one computer the phone can connect to: { id, name, url } — url is the full
// QR payload (http://host:port/?t=<token>), so every computer keeps its own
// token. One entry is "active": the one the WebView shows. Older builds stored
// a single URL under wakili.server; loadNetworks migrates it into a one-entry
// list on first run.

import AsyncStorage from "@react-native-async-storage/async-storage";

const NETWORKS_KEY = "wakili.networks";
const ACTIVE_KEY = "wakili.activeNetwork";
const LEGACY_SERVER_KEY = "wakili.server";

export const looksLikeServerUrl = (s) => /^https?:\/\/\S+/i.test((s || "").trim());

// Tiny URL helpers by regex — no dependence on the engine's URL implementation.
export const hostOf = (url) => { const m = /^https?:\/\/([^/?#]+)/i.exec(url || ""); return m ? m[1] : String(url || ""); };
const originOf = (url) => { const m = /^(https?:\/\/[^/?#]+)/i.exec(url || ""); return m ? m[1] : null; };
const tokenOf = (url) => { const m = /[?&]t=([^&#]+)/.exec(url || ""); return m ? decodeURIComponent(m[1]) : ""; };

const newId = () => `n${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;

// Names are derived, never typed: "<hostname>-<path>", e.g. ahmedpc-lan or
// ahmedpc-tailscale — the same computer added over two paths is two entries
// whose names say which path each one is. The path is inferred from the URL:
// Tailscale addresses are either MagicDNS (*.ts.net) or in the CGNAT range
// (100.64.0.0/10); Cloudflare quick tunnels are *.trycloudflare.com; anything
// else is a plain LAN address.
const pathSuffix = (url) => {
  const host = hostOf(url).replace(/:\d+$/, "");
  if (/\.ts\.net$/i.test(host)) return "tailscale";
  const m = /^100\.(\d+)\./.exec(host);
  if (m && +m[1] >= 64 && +m[1] <= 127) return "tailscale";
  if (/\.trycloudflare\.com$/i.test(host) || /\.cfargotunnel\.com$/i.test(host)) return "cloudflare";
  return "lan";
};
export const autoName = (url, hostname) => `${String(hostname).trim().toLowerCase()}-${pathSuffix(url)}`;

// -> { networks, activeId }. Never throws; storage trouble yields an empty list.
export async function loadNetworks() {
  try {
    const raw = await AsyncStorage.getItem(NETWORKS_KEY);
    if (raw) {
      const networks = (JSON.parse(raw) || []).filter((n) => n && n.id && looksLikeServerUrl(n.url));
      let activeId = await AsyncStorage.getItem(ACTIVE_KEY);
      if (!networks.some((n) => n.id === activeId)) activeId = networks.length ? networks[0].id : null;
      return { networks, activeId };
    }
    // First run on this build: migrate the legacy single-URL storage.
    const legacy = await AsyncStorage.getItem(LEGACY_SERVER_KEY);
    if (looksLikeServerUrl(legacy)) {
      const net = { id: newId(), name: hostOf(legacy), url: legacy.trim() };
      await persist([net], net.id);
      AsyncStorage.removeItem(LEGACY_SERVER_KEY).catch(() => {});
      return { networks: [net], activeId: net.id };
    }
  } catch { /* fall through */ }
  return { networks: [], activeId: null };
}

export async function persist(networks, activeId) {
  await AsyncStorage.setItem(NETWORKS_KEY, JSON.stringify(networks));
  if (activeId) await AsyncStorage.setItem(ACTIVE_KEY, activeId);
  else await AsyncStorage.removeItem(ACTIVE_KEY);
}

// Add a network by URL, deduplicating by origin: re-scanning a computer the
// phone already knows refreshes its URL/token instead of creating a duplicate
// (and keeps any custom name the user gave it).
export function upsert(networks, url, name) {
  const u = (url || "").trim();
  const origin = originOf(u);
  const existing = origin ? networks.find((n) => originOf(n.url) === origin) : null;
  if (existing) {
    const next = networks.map((n) => (n === existing ? { ...n, url: u, name: name || n.name } : n));
    return { networks: next, id: existing.id };
  }
  const net = { id: newId(), name: name || hostOf(u), url: u };
  return { networks: [...networks, net], id: net.id };
}

// Ask the gateway which addresses it is reachable on (LAN / Tailscale /
// Cloudflare, each URL carrying the token). Lets the hosts page offer the same
// computer's OTHER paths — e.g. its LAN address when only the Tailscale QR was
// ever scanned. Older gateways without /api/endpoints or unreachable ones
// yield an empty list.
export async function probeEndpoints(url) {
  const origin = originOf(url);
  if (!origin) return [];
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 4000);
  try {
    const r = await fetch(`${origin}/api/endpoints`, { headers: { "x-auth-token": tokenOf(url) }, signal: ctl.signal });
    if (!r.ok) return [];
    const j = await r.json();
    return Array.isArray(j) ? j.filter((e) => e && looksLikeServerUrl(e.url)) : [];
  } catch {
    return [];
  } finally {
    clearTimeout(timer);
  }
}

// Ask the gateway who it is, so a freshly added network gets labelled with the
// computer's real name ("AHMED-PC") instead of a bare ip:port. Gateways without
// /api/host (older versions) or unreachable ones just yield null.
export async function probeName(url) {
  const origin = originOf(url);
  if (!origin) return null;
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 4000);
  try {
    const r = await fetch(`${origin}/api/host`, { headers: { "x-auth-token": tokenOf(url) }, signal: ctl.signal });
    if (!r.ok) return null;
    const j = await r.json();
    return (j && typeof j.hostname === "string" && j.hostname.trim()) || null;
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}
