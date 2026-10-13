# Self-host wakili on any container platform (Railway, Render, Fly.io, a VPS…).
# The agents run inside this container — mount a volume at /data so sessions,
# uploads, and Claude Code state survive restarts and redeploys.
FROM node:22-slim

# git: required by the coding agents; curl/ca-certificates: downloads and TLS.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Bake the agent CLIs into the image so `claude` and `codex` are always on PATH.
RUN npm install -g @anthropic-ai/claude-code @openai/codex

# Antigravity CLI (agy): one glibc binary from the GitHub release, picked by
# the image's architecture. Optional agent — it needs a Google sign-in before
# its turns work (see ANTIGRAVITY_OAUTH_TOKEN in docker-entrypoint.sh).
RUN arch="$(dpkg --print-architecture)" \
    && case "$arch" in amd64) agy=x64 ;; arm64) agy=arm64 ;; *) agy="" ;; esac \
    && if [ -n "$agy" ]; then \
         curl -fsSL "https://github.com/google-antigravity/antigravity-cli/releases/latest/download/agy_cli_linux_${agy}.tar.gz" \
           | tar -xz -C /usr/local/bin antigravity \
         && chmod +x /usr/local/bin/antigravity; \
       fi

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY . .

# Uploads can come from a Windows checkout, where git hands out CRLF endings.
# dash rejects the resulting `set -e\r`, so strip CR before the shim ever runs.
RUN sed -i 's/\r$//' docker-entrypoint.sh

# Containers run as root, and Claude Code refuses bypassPermissions ("Auto
# mode") as root unless told it is sandboxed — every Auto-mode turn would die.
ENV WAKILI_HOME=/data \
    CLAUDE_CONFIG_DIR=/data/claude-config \
    CODEX_HOME=/data/codex \
    IS_SANDBOX=1

EXPOSE 8730
CMD ["sh", "docker-entrypoint.sh"]
