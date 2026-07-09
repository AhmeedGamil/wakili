<p align="center">
  <img src="public/icons/logo-mask.png" alt="Wakili logo" width="120" />
</p>

<h1 align="center">Wakili</h1>

<h3 align="center">Your AI agents, in your pocket.</h3>
<p align="center">Control Claude Code and Codex from your phone, wherever work needs you.</p>

<p align="center">
  <img src="https://img.shields.io/badge/node-%3E%3D20-339933?logo=node.js&logoColor=white" alt="Node.js >=20" />
  <img src="https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-8250df?logo=windows&logoColor=white" alt="Platform: Windows | macOS | Linux" />
  <img src="https://img.shields.io/badge/agent-Claude%20Code-d97757?logo=anthropic&logoColor=white" alt="Agent: Claude Code" />
  <img src="https://img.shields.io/badge/agent-Codex-000000" alt="Agent: Codex" />
  <img src="https://img.shields.io/badge/access-LAN%20%C2%B7%20Tailscale%20%C2%B7%20Cloudflare-2563eb?logo=cloudflare&logoColor=white" alt="Access: LAN · Tailscale · Cloudflare" />
</p>

Control and chat with Claude Code and Codex from your phone using the Wakili app. Run it on your
computer and drive your agent sessions remotely — over your LAN, securely via Tailscale, or
publicly via a Cloudflare tunnel. Pick your agent per message — Claude Code, Claude (official
Agent SDK), or Codex — and choose its model (Opus 4.8, GPT‑5.5, …), reasoning effort, and
approval mode.

<p align="center">
  <img src="docs/screenshots/chat.jpg" alt="Chat with streaming output and tool cards" width="28%" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/agent-picker.jpg" alt="Agent, model, reasoning and approval picker" width="28%" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/permission-card.jpg" alt="Tap-to-approve permission card" width="28%" />
</p>

<p align="center">
  <img src="docs/screenshots/sessions.jpg" alt="Sessions sidebar and project switcher" width="28%" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/terminal.jpg" alt="Built-in terminal with tabs" width="28%" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/claude-agent.jpg" alt="Claude agent session" width="28%" />
</p>

## Features

- **Streaming output and tool/permission cards** — watch the agent work live and approve
  or deny its actions with a tap, or turn on per-chat auto-approval
- **Hand a conversation off to another agent** mid-thread — the transcript travels with it
- **File upload/download** — send photos and files to the agent with live progress, pull
  results back to the phone
- **Built-in terminal** — multiple tabs, one per project, with recallable command history
- **Browse and switch project folders** from the phone, including creating new ones
- **Remote power controls** — lock the screen, blank the display, keep the machine awake,
  or shut the computer down
- **Connection switcher** — hop in-app between LAN, Tailscale and Cloudflare without
  losing your sessions
- **Starts with the computer** — after the first run the gateway is always up in the
  background; toggle it from the app
- **Proxy support** for firewalled or blocked networks (`HTTPS_PROXY` in `.env`)
- **Dark/light themes** with accent colors, and installable as a PWA
- **Sessions organized by project** — grouped by folder, rename/delete with a long-press,
  and every chat resumes exactly where it left off
- **Queue messages while the agent is busy** — send now, delivered the moment the turn ends

---

## Prerequisites

- **Node.js 20+**
- **Claude Code or Codex installed** — on your `PATH` and authenticated (per that tool's own docs).
- **Outside the LAN:** Tailscale or cloudflared.

---

## Install

```bash
npm install -g wakili
```

That's it — the `wakili` command now works from any directory.

Or work from a clone instead, using the [npm scripts](#commands) in place of the global
commands:

```bash
git clone https://github.com/ahmeedgamil/wakili.git
cd wakili
npm install
```

---

## Quick start

1. **Install Tailscale** on both the computer and the phone, and **sign in with the same
   account** on each (this puts both devices on your private tailnet).
2. **Start the gateway** on the computer:
   ```bash
   wakili
   ```
3. **Open Wakili on your phone**, any of:
   - **Android app** — [download the latest APK](https://github.com/ahmeedgamil/wakili/releases/latest/download/wakili.apk).
   - **Build it yourself** — from [`wakili-react-native/`](wakili-react-native/).
   - **Web (Android & iPhone)** — point the phone camera at the QR code printed in the
     terminal to open the app in the browser. On iPhone this is the way to use Wakili —
     and you can add it to the home screen for an app-like feel.

---

## Connection modes

Pick a mode by **which command you run** on the computer. Every mode prints one or more
URLs **and a scannable QR code** in the terminal — on the phone you can either scan the
QR with the camera or open the URL by hand. Every URL already contains the access token
(`?t=…`), which the phone saves after the first open, so there's nothing to type.

Quick reference:

| Mode | Reaches from | Command | Phone opens |
|------|--------------|----------------|-------------|
| **Local network** | same Wi‑Fi only | `wakili` | `http://<lan-ip>:8730/?t=…` |
| **Tailscale** | anywhere (private) | `wakili` or `wakili --tunnel tailscale` | `http://100.x.x.x:8730/?t=…` |
| **Cloudflare** | anywhere (public) | `wakili-cloudflare` | `https://<name>.trycloudflare.com/cf.html?t=…` |

---

### Mode 1 — Local network (same Wi‑Fi)

The simplest mode: the phone talks to the computer directly over your Wi‑Fi. Nothing to
install.

1. Put the **computer and phone on the same Wi‑Fi network**.
2. On the computer: `wakili`.
3. The terminal prints a **`Phone:` line** (`http://<lan-ip>:8730/?t=…`) and a **QR code**
   under *"Scan to open on your phone (same Wi‑Fi)"*. (The `Computer:` `localhost` line is
   for opening on the computer itself.)
4. On the phone: **scan the QR** with the camera, or type the `Phone:` URL into the browser.
5. Done — the page loads and the token is saved.

> If it won't load: you're not on the same Wi‑Fi, or the computer's firewall is blocking
> port `8730`. When Windows first pops the *"Windows Defender Firewall has blocked
> Node.js"* dialog, tick **Private networks** and click **Allow access**. If you dismissed
> it, add the rule from an **elevated PowerShell**:
>
> ```powershell
> New-NetFirewallRule -DisplayName "Wakili 8730" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8730
> ```
>
> On macOS/Linux, allow Node (or port `8730`) through the OS firewall. This only affects
> Local network mode — Tailscale and Cloudflare tunnel out and need no inbound rule.

---

### Mode 2 — Tailscale (private, from anywhere)

Tailscale builds a **private network** between your devices, so the phone reaches the
computer from **any network (cellular, another Wi‑Fi)** without exposing anything publicly.
Both devices must be signed into the **same Tailscale account**.

1. **Install Tailscale on the computer** — download from `tailscale.com/download`, install,
   and **sign in** (Google/Microsoft/GitHub/email — remember which account).
2. **Install the Tailscale app on the phone** — from the App Store / Google Play — and
   **sign in with the *same* account** as the computer.
3. **Turn Tailscale ON on both devices** (the app toggle must be connected/green). Now
   both are on the same private "tailnet".
4. On the computer: `wakili` (Tailscale is auto‑detected) — or `wakili --tunnel tailscale`.
5. The terminal prints a **`Tunnel:` line** (`http://100.x.x.x:8730/?t=…`, a `100.x`
   address) and a **QR code** under *"Scan to connect from anywhere (Tailscale)"*.
6. On the phone, **with the Tailscale app still turned ON**, scan that QR or open the
   `100.x` URL.

> Common misses: signing into **different** accounts on the two devices; forgetting to
> **toggle Tailscale on** on the phone before opening the link; or using the `100.x` URL
> while the phone's Tailscale is off. All three break the connection. Nothing is public —
> only your tailnet devices can reach it.

---

### Mode 3 — Cloudflare (public, from anywhere, no account)

Cloudflare gives a **public https URL** reachable from any network with **no shared
account** — handy when you can't use Tailscale. The access **token** is what keeps it
private, so guard the URL.

1. **Install `cloudflared` on the computer** — from Cloudflare's downloads page. **No login
   or Cloudflare account is needed** for a quick tunnel.
2. On the computer: `wakili-cloudflare`. This starts the gateway **and** the Cloudflare
   bridge together (one command).
3. The terminal prints a **`https://<name>.trycloudflare.com/cf.html?t=…` URL** and a
   **QR code**. Note it ends in **`/cf.html`** — that's the correct page for this mode.
4. On the phone — **any network, Wi‑Fi or cellular** — scan the QR or open that URL.

> Important:
> - The URL is **random and changes every time** you restart `wakili-cloudflare`. Re‑scan
>   the new QR after a restart. (The in‑app connection switcher always shows the current one.)
> - It is a **public** URL — treat it like a password; anyone with it *and* the token could
>   reach your gateway. Token‑only auth (no admin/admin) is the guard.
> - Use **`wakili-cloudflare`**, not `wakili --tunnel cloudflare`. The plain flag
>   gives a public URL but Cloudflare buffers the live stream, so streaming breaks; the
>   bridge relays it over a WebSocket (served at `/cf.html`) that Cloudflare won't buffer.

---

## Switching connections from the app

Inside the web page, the **🔌 Connection** button (sidebar footer) lists every URL the
same gateway is reachable on — Local network, Tailscale, Cloudflare — and switches to
the one you pick. They all reach the same gateway, so your sessions come along. (A
target only connects if the phone's current network can actually reach it.)

---

## How it works & where things live

On first run a random access token is generated and saved to `~/.wakili/token.txt`; the
URL carries it (`?t=…`) so opening the link "just works." Each chat is a JSON file under
`~/.wakili/sessions/` (the project it runs in is saved as a `cwd` field inside it), and
uploads live in `~/.wakili/uploads/` (override the base with `WAKILI_HOME`).

The first launch also registers Wakili to **start with the computer** — meaning the gateway
starts automatically when the computer turns on, running in the background even at the lock
screen with no need to sign in, so after that one manual run there's never a terminal to
open. Turn it off anytime with the *Start with computer* toggle in the app; your choice
sticks and is never re-enabled behind your back.

---

## Configuration

Set these in a `.env` file (copy from `.env.example`) or as real environment variables
(shell env wins). All optional. The `.env` can live in two places, both are read:

- `~/.wakili/.env` — **recommended for `npm install -g` setups**: it survives package updates.
- next to `server.mjs` — natural for a cloned repo (wins over the home file if both exist).

| Variable | Default | Purpose |
|----------|---------|---------|
| `PORT` | `8730` | Gateway port. |
| `WAKILI_TOKEN` | auto-generated | Access token (the only credential). Unset → generated & persisted to `~/.wakili/token.txt`. |
| `WAKILI_CLAUDE_ENTRYPOINT` | `claude-vscode` | Which editor's resume list your chats appear in (`claude-vscode` / `cli` / `sdk-cli`). |
| `WAKILI_HOME` | `~/.wakili` | Where the runtime store (sessions, token, uploads) lives. |
| `CF_BRIDGE_PORT` | `8731` | Port for the Cloudflare bridge. |
| `WAKILI_KEEP_AWAKE` | `1` | Keep the machine awake while running (screen can still lock). `0` to opt out. |
| `HTTPS_PROXY` / `HTTP_PROXY` | unset | Route outbound traffic through an HTTP proxy (see below). |
| `NO_PROXY` | auto | Hosts that bypass the proxy. `localhost`/`127.0.0.1` are always added for you. |

Deeper settings live in [`src/config.mjs`](src/config.mjs).

---

## Behind a firewall or blocked region

If `api.anthropic.com` or `api.openai.com` isn't reachable from your network (corporate
firewall, national blocking), point Wakili at an HTTP proxy you trust:

```ini
# ~/.wakili/.env
HTTPS_PROXY=http://user:pass@your-proxy-host:8888
HTTP_PROXY=http://user:pass@your-proxy-host:8888
```

That's the whole setup. The gateway loads this at startup **no matter how it was
launched** — terminal or start-at-login — and hands it to every `claude`/`codex` process
it spawns, so agent traffic goes through the proxy while the phone still connects to the
gateway directly. Loopback (`localhost`, `127.0.0.1`) is excluded automatically so the
gateway's internal callbacks never detour through the proxy; add more bypass hosts with
`NO_PROXY` if you need them.

Notes:

- Credentials in the URL are fine (`http://user:pass@host:port`) — URL-encode any special
  characters in the password.
- The proxy needs to support HTTPS `CONNECT` tunneling (virtually all forward proxies do).
- Your proxy now carries the agents' API traffic; token-heavy sessions add real bandwidth.

---

## Commands

| Global install | From a clone | Does |
|----------------|--------------|------|
| `wakili` | `npm start` | Gateway, auto mode (LAN + Tailscale if present). |
| `wakili --tunnel tailscale` | `npm run tailscale` | Gateway, Tailscale only. |
| `wakili --tunnel none` | `npm run lan` | Gateway, LAN only (no tunnel). |
| `wakili-cloudflare` | `npm run cloudflare` | Gateway **+** Cloudflare bridge together. |
| — | `npm run bridge` | The Cloudflare bridge alone (gateway must already be running). |

---

## Security notes

- Access is **token-only** — opening the page requires the `?t=…` token from the QR or
  link. There's no password to guess, but treat that URL like a password, especially
  over a public Cloudflare tunnel.
- The token is 24 random bytes, auto-generated and saved to `~/.wakili/token.txt`. Delete
  that file to rotate it (a fresh one is generated on the next start).
- The store lives in `~/.wakili/` — outside the repo, so your token and session history
  can't be committed by accident. `.env` is git-ignored too.
