<p align="center">
  <img src="public/icons/logo-mask.png" alt="Wakili logo" width="120" />
</p>

<h1 align="center">Wakili</h1>

<p align="center">
  <img src="https://img.shields.io/badge/node-%3E%3D20-339933?logo=node.js&logoColor=white" alt="Node.js >=20" />
  <img src="https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-8250df?logo=windows&logoColor=white" alt="Platform: Windows | macOS | Linux" />
  <img src="https://img.shields.io/badge/agents-Claude%20Code%20%C2%B7%20Codex-d97757?logo=anthropic&logoColor=white" alt="Agents: Claude Code · Codex" />
  <img src="https://img.shields.io/badge/access-LAN%20%C2%B7%20Tailscale%20%C2%B7%20Cloudflare-f38020?logo=cloudflare&logoColor=white" alt="Access: LAN · Tailscale · Cloudflare" />
</p>

Control and chat with Claude Code and Codex from your phone using Wakili app. Run it on your computer and
drive your agent sessions remotely — over your LAN, securely via Tailscale, or publicly
via a Cloudflare tunnel.

- Streaming output and tool/permission cards
- File upload/download
- A built-in **terminal**
- Remote **power controls** — lock the screen, blank the display, keep the machine awake
- An in-app connection switcher

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
</p>

---

## Prerequisites

- **Node.js 20+**
- **Claude Code or Codex installed** — on your `PATH` and authenticated (per that tool's own docs).
- **Outside the LAN:** Tailscale or cloudflared.

There are **no npm dependencies** — nothing to `npm install`. The project runs on Node
built-ins plus those external CLIs.

---

## Quick start

1. **Install Tailscale** on both the computer and the phone, and **sign in with the same
   account** on each (this puts both devices on your private tailnet).
2. **Start the gateway** on the computer:
   ```bash
   npm start
   ```
3. **Open Wakili on your phone**, any of:
   - **Android app** — [download the latest APK](https://github.com/ahmeedgamil/wakili/releases/latest/download/wakili.apk).
   - **Build it yourself** — from [`wakili-react-native/`](wakili-react-native/).
   - **Web** — point the phone camera at the QR code printed in the terminal to open the
     app in the browser.
