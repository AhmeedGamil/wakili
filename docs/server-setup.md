# Running Wakili on a server

Wakili normally runs on your own computer, next to your code. This guide covers the other
mode: running it on a **server or cloud container platform** (Railway, Render, Fly.io, a
VPS with Docker — anything that can build a Dockerfile). The agents then run **inside the
container**: clone your repos there with the built-in terminal and point chats at those
folders. Your phone connects to the server's public HTTPS URL — no Tailscale or Cloudflare
tunnel needed.

The repo's [`Dockerfile`](../Dockerfile) is the whole build: Node 22, `git`, and the
Claude Code + Codex CLIs baked in. [`docker-entrypoint.sh`](../docker-entrypoint.sh) runs
at boot and turns secrets you pass as env vars into what the CLIs expect.

---

## What every host needs

Three things, identical on every platform:

1. **Build from the `Dockerfile`** (all container hosts auto-detect it).
2. **A persistent volume mounted at `/data`** — sessions, uploads, the access token, and
   both agents' state live there and survive restarts and redeploys.
3. **Environment variables** — the table below. Secrets always travel as env vars; nothing
   secret is ever baked into the image.

| Variable | Required | Purpose |
|----------|----------|---------|
| `WAKILI_TOKEN` | yes | The access token in your URL (`?t=…`). Set it to any long random string. If unset, one is generated and saved to `/data/token.txt` — fine, but you'll have to fish it out of the logs. |
| `PORT` | host-dependent | Port the gateway listens on (default `8730`). Set it, or use the port your host injects. |
| `CLAUDE_CODE_OAUTH_TOKEN` | for Claude (subscription) | From `claude setup-token` — see below. |
| `ANTHROPIC_API_KEY` | for Claude (API billing) | Alternative to the OAuth token. |
| `CODEX_AUTH_JSON` | for Codex (ChatGPT plan) | The full contents of an `auth.json` from a machine where you ran `codex login` — see below. |
| `OPENAI_API_KEY` | for Codex (API billing) | Alternative to `CODEX_AUTH_JSON`. |
| `ANTIGRAVITY_OAUTH_TOKEN` | for Antigravity (optional agent) | The contents of `antigravity-oauth-token` from a machine where you signed in to the Antigravity CLI — see below. |
| `WAKILI_KEEP_AWAKE` | recommended: `0` | Keep-awake is for physical machines; disable it in a container. |

Defaults already set in the image (override only if you know why): `WAKILI_HOME=/data`,
`CLAUDE_CONFIG_DIR=/data/claude-config`, `CODEX_HOME=/data/codex`.

**Your URL** is simply `https://<your-domain>/?t=<WAKILI_TOKEN>`. Open it on the phone
once and the token is saved. Treat the full URL like a password.

---

## Agent auth 1 — Claude Code

Claude has a first-class headless flow:

1. On any machine with a browser (and a Claude Pro/Max subscription):
   ```bash
   claude setup-token
   ```
2. Sign in when the browser opens; the command prints a long-lived token starting with
   `sk-ant-oat01-…`.
3. Set it as the `CLAUDE_CODE_OAUTH_TOKEN` env var on the server. Done — every `claude`
   process the gateway spawns picks it up from the environment automatically.

No subscription? Set `ANTHROPIC_API_KEY` (from console.anthropic.com) instead — same
mechanism, per-token API billing.

---

## Agent auth 2 — Codex

OpenAI has no equivalent of `setup-token`; `codex login` needs a browser redirecting back
to the same machine, which a headless container can't do. The portable credential is the
**file** that login saves — so you create it elsewhere and pass its contents in:

1. On any machine with a browser:
   ```bash
   npm install -g @openai/codex
   codex login
   ```
   Sign in with the ChatGPT account whose plan the server should use.
2. Copy the contents of the file it saved:
   - Windows: `C:\Users\<name>\.codex\auth.json`
   - macOS/Linux: `~/.codex/auth.json`
3. Set the whole JSON (from `{` to `}`) as the `CODEX_AUTH_JSON` env var on the server.

At boot, the entrypoint writes it once to `/data/codex/auth.json` (mode 600). From then on
Codex refreshes the tokens itself on the volume, so the credential stays valid without the
original machine. The entrypoint **never overwrites an existing file** — a stale env value
can't clobber refreshed tokens.

To switch accounts later: update `CODEX_AUTH_JSON`, delete `/data/codex/auth.json` (one
`rm` in the built-in terminal), and restart — the entrypoint re-seeds from the new value.

Or skip all of this with `OPENAI_API_KEY` (API billing, not a ChatGPT plan).

---

## Agent auth 3 — Antigravity (optional)

The image also carries Google's Antigravity CLI. Its sign-in is a browser flow too, so it
is seeded the same way as Codex:

1. On any machine with a browser, install the
   [Antigravity CLI](https://github.com/google-antigravity/antigravity-cli/releases),
   run it once and sign in.
2. Copy the contents of the file it saved:
   - Windows: `C:\Users\<name>\.gemini\antigravity-cli\antigravity-oauth-token`
   - macOS/Linux: `~/.gemini/antigravity-cli/antigravity-oauth-token`
3. Set it as the `ANTIGRAVITY_OAUTH_TOKEN` env var on the server.

At boot the entrypoint links `~/.gemini` to `/data/gemini` — Antigravity keeps its sign-in
and its conversations there, so both survive redeploys — and writes the token once (never
over an existing file). Without a sign-in the Antigravity tab still shows, but its turns
fail with a sign-in error. Off a container, the agent appears only on machines where the
CLI is installed (`~/.antigravity/bin`, or point `WAKILI_AGY_BIN` at the binary).

---

## Example: Railway

```bash
railway init                              # or link an existing project
railway add --service wakili
railway service wakili
railway volume add -m /data
railway variables --skip-deploys \
  --set "WAKILI_TOKEN=<random-string>" \
  --set "PORT=8730" \
  --set "WAKILI_KEEP_AWAKE=0" \
  --set "CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat01-..." \
  --set "CODEX_AUTH_JSON=<paste auth.json contents>"
railway up --detach                       # builds the Dockerfile, deploys
railway domain --port 8730                # prints your public URL
```

Open `https://<generated-domain>/?t=<WAKILI_TOKEN>` on your phone.

## Example: any VPS with docker compose

```yaml
services:
  wakili:
    build: .
    ports:
      - "8730:8730"
    volumes:
      - wakili-data:/data
    environment:
      WAKILI_TOKEN: "<random-string>"
      WAKILI_KEEP_AWAKE: "0"
      CLAUDE_CODE_OAUTH_TOKEN: "sk-ant-oat01-..."
      CODEX_AUTH_JSON: '{"auth_mode":"chatgpt", ... }'
volumes:
  wakili-data:
```

`docker compose up -d`, then put HTTPS in front of port 8730 (Caddy or nginx + certbot)
and open `https://<host>/?t=<WAKILI_TOKEN>`.

---

## Notes & gotchas

- **The container is "the computer."** Remote power controls (shutdown, lock) act on the
  container, not anything you own — shutdown just triggers the platform's restart policy.
- **Always mount `/data`.** Without it, every redeploy wipes your sessions, the generated
  token, and both agents' auth state.
- **A server runs (and bills) 24/7** on most platforms, unlike the run-when-you-need-it
  local mode.
- **Verifying a deploy:** a failed agent spawn currently surfaces only as a live
  `stderr` event on `/api/stream`, not in the saved transcript — a chat that silently
  returns nothing usually means the agent process failed to start; check the deploy logs.
- **Rotating the access token:** change `WAKILI_TOKEN` and restart; the old URL stops
  working immediately.
