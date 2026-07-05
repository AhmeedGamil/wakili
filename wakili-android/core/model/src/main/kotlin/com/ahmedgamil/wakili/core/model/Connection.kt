package com.ahmedgamil.wakili.core.model

/**
 * Where the gateway is reachable. One gateway can expose several endpoints
 * (LAN / Tailscale / Cloudflare) — they all serve the same sessions.
 */
data class GatewayEndpoint(
    val label: String,
    val host: String,
    val url: String,
)

/**
 * The saved connection: base URL + the access token captured from the QR link.
 * Cloudflare endpoints need the WebSocket stream transport instead of SSE
 * (Cloudflare buffers SSE responses).
 */
data class ConnectionProfile(
    val baseUrl: String,
    val token: String,
) {
    val isCloudflare: Boolean get() = baseUrl.contains("trycloudflare.com")
}
