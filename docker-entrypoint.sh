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
exec node server.mjs --tunnel none
