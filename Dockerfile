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

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY . .

# Uploads can come from a Windows checkout, where git hands out CRLF endings.
# dash rejects the resulting `set -e\r`, so strip CR before the shim ever runs.
RUN sed -i 's/\r$//' docker-entrypoint.sh

ENV WAKILI_HOME=/data \
    CLAUDE_CONFIG_DIR=/data/claude-config \
    CODEX_HOME=/data/codex

EXPOSE 8730
CMD ["sh", "docker-entrypoint.sh"]
