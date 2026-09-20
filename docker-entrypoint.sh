#!/bin/sh
# Container boot shim. Codex needs its home dir to exist, and cloud platforms
# hand out secrets as env vars, not files — so if CODEX_AUTH_JSON is set and no
# auth file exists yet, materialize it once. Never overwrite an existing file:
# codex refreshes and rewrites auth.json itself, and a stale env value must not
# clobber newer tokens (delete the file on the volume to force a re-seed).
set -e
CODEX_HOME="${CODEX_HOME:-/data/codex}"
mkdir -p "$CODEX_HOME"
if [ -n "$CODEX_AUTH_JSON" ] && [ ! -f "$CODEX_HOME/auth.json" ]; then
  printf '%s' "$CODEX_AUTH_JSON" > "$CODEX_HOME/auth.json"
  chmod 600 "$CODEX_HOME/auth.json"
fi

# Antigravity keeps everything (sign-in, conversations, settings) under
# ~/.gemini with no env var to relocate it — so point that at the volume, or
# every redeploy would sign it out and forget its conversations. Same seeding
# rule as codex: ANTIGRAVITY_OAUTH_TOKEN is the contents of a signed-in
# machine's ~/.gemini/antigravity-cli/antigravity-oauth-token, written once and
# never over an existing file.
GEMINI_DIR="${WAKILI_HOME:-/data}/gemini"
mkdir -p "$GEMINI_DIR/antigravity-cli"
if [ ! -e "$HOME/.gemini" ]; then ln -s "$GEMINI_DIR" "$HOME/.gemini"; fi
AGY_TOKEN_FILE="$GEMINI_DIR/antigravity-cli/antigravity-oauth-token"
if [ -n "$ANTIGRAVITY_OAUTH_TOKEN" ] && [ ! -f "$AGY_TOKEN_FILE" ]; then
  printf '%s' "$ANTIGRAVITY_OAUTH_TOKEN" > "$AGY_TOKEN_FILE"
  chmod 600 "$AGY_TOKEN_FILE"
fi
exec node server.mjs --tunnel none
