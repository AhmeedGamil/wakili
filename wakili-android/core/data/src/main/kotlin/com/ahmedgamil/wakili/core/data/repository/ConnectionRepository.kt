package com.ahmedgamil.wakili.core.data.repository

import com.ahmedgamil.wakili.core.datastore.ConnectionStore
import com.ahmedgamil.wakili.core.model.ConnectionProfile
import com.ahmedgamil.wakili.core.model.GatewayAuthException
import com.ahmedgamil.wakili.core.model.GatewayUnreachableException
import com.ahmedgamil.wakili.core.network.api.GatewayApi
import com.ahmedgamil.wakili.core.network.api.GatewayConnection
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import retrofit2.HttpException

/**
 * Owns the gateway connection lifecycle: parse the QR/typed URL (capturing the
 * ?t= token exactly like the web client's loadToken), verify it against the
 * gateway, persist it, and restore it on app start.
 */
@Singleton
class ConnectionRepository @Inject constructor(
    private val store: ConnectionStore,
    private val connection: GatewayConnection,
    private val api: GatewayApi,
) {

    val profile: Flow<ConnectionProfile?> = store.profile

    /** Every gateway ever connected to — the saved-hosts switcher list. */
    val savedHosts: Flow<List<ConnectionProfile>> = store.hosts

    /** User-chosen host display names, keyed by baseUrl (empty = use derived). */
    val hostNames: Flow<Map<String, String>> = store.hostNames

    /** Rename a saved host (blank clears it back to the derived name). */
    suspend fun renameHost(baseUrl: String, name: String) = store.setHostName(baseUrl, name)

    /** Load the saved profile into the active connection. Null = first run. */
    suspend fun restore(): ConnectionProfile? =
        store.profile.first()?.also { connection.update(it) }

    /**
     * Connect to the URL from a QR scan or manual entry. Throws
     * [InvalidGatewayUrlException] for unparseable input, [GatewayAuthException]
     * on 401, [GatewayUnreachableException] otherwise — the transport layer's
     * exception types never leak past here. A failed attempt restores the
     * previous profile so it doesn't break an existing connection.
     */
    suspend fun connect(rawUrl: String): ConnectionProfile {
        val profile = parseGatewayUrl(rawUrl) ?: throw InvalidGatewayUrlException()
        val previous = connection.profile.value
        connection.update(profile)
        try {
            api.agents() // 401/timeouts surface here
        } catch (e: Exception) {
            connection.update(previous)
            throw when {
                e is HttpException && e.code() == 401 -> GatewayAuthException()
                else -> GatewayUnreachableException(e)
            }
        }
        store.save(profile)
        return profile
    }

    suspend fun disconnect() {
        store.clear()
        connection.update(null)
    }

    /** Connect to a SAVED host (token already known) — verify, then persist. */
    suspend fun connectTo(profile: ConnectionProfile): ConnectionProfile {
        val previous = connection.profile.value
        connection.update(profile)
        try {
            api.agents()
        } catch (e: Exception) {
            connection.update(previous)
            throw when {
                e is HttpException && e.code() == 401 -> GatewayAuthException()
                else -> GatewayUnreachableException(e)
            }
        }
        store.save(profile)
        return profile
    }

    suspend fun removeHost(baseUrl: String) = store.removeHost(baseUrl)

    /**
     * Connect to a URL the user TYPED (as opposed to scanned). Manual entry is
     * held to a looser standard than [parseGatewayUrl]: the scheme may be
     * omitted, and so may the ?t= token — typing a bare host is the normal way
     * to hop between endpoints of a gateway already been connected to (LAN IP →
     * Tailscale IP), and that token is already saved. Resolution order:
     *   1. token typed in the URL  → use it
     *   2. same host saved before  → reuse that token
     *   3. neither                 → [MissingGatewayTokenException], so the UI
     *      can say what's missing instead of leaving Go inert.
     */
    suspend fun connectManual(rawUrl: String): ConnectionProfile {
        val url = normalizeUrl(rawUrl) ?: throw InvalidGatewayUrlException()
        if (parseGatewayUrl(url) != null) return connect(url)
        val base = baseUrlOf(url) ?: throw InvalidGatewayUrlException()
        val saved = store.hosts.first().firstOrNull { it.baseUrl.equals(base, ignoreCase = true) }
            ?: throw MissingGatewayTokenException()
        return connect(url + (if ('?' in url) "&" else "?") + "t=" + saved.token)
    }

    /** The connected computer's own name (os.hostname) — the friendly device
     *  name for the sidebar, in place of an IP/network label. Null if unknown. */
    suspend fun deviceHostname(): String? =
        runCatching { api.host().hostname }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Switch to another endpoint of the same gateway (connection switcher).
     * /api/endpoints URLs may come without ?t= — it's the same gateway, so the
     * current profile's token is carried over (the browser gets this for free
     * via localStorage; here it must be explicit).
     */
    suspend fun switchEndpoint(url: String): ConnectionProfile {
        val token = connection.profile.value?.token
        val withToken = if (parseGatewayUrl(url) != null || token.isNullOrEmpty()) {
            url
        } else {
            url + (if ('?' in url) "&" else "?") + "t=" + token
        }
        return connect(withToken)
    }

    companion object {
        /**
         * Accepts what the gateway prints/encodes in its QRs:
         *   http://192.168.1.10:8730/?t=TOKEN
         *   http://100.x.y.z:8730/?t=TOKEN
         *   https://name.trycloudflare.com/cf.html?t=TOKEN
         * Base = scheme://host[:port]; the ?t= token is required (it's the only
         * credential — without it the gateway answers 401).
         */
        /**
         * Typed input → a URL worth trying: trims, and supplies the scheme the
         * user almost never types ("192.168.1.10:8730" → "http://…"). Null when
         * there's no host to speak of. Deliberately does NOT require a token —
         * that's [connectManual]'s job to resolve.
         */
        fun normalizeUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val withScheme =
                if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed
                else "http://$trimmed"
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            if (uri.host.isNullOrBlank()) return null
            return withScheme
        }

        /** scheme://host[:port] for a typed URL, token or not. Null if unparseable. */
        fun baseUrlOf(raw: String): String? {
            val uri = runCatching { URI(normalizeUrl(raw) ?: return null) }.getOrNull() ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port != -1) ":${uri.port}" else ""
            return "${uri.scheme.lowercase()}://$host$port"
        }

        fun parseGatewayUrl(raw: String): ConnectionProfile? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) return null
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            val host = uri.host ?: return null
            val token = uri.query
                ?.split('&')
                ?.firstNotNullOfOrNull { p ->
                    val eq = p.indexOf('=')
                    if (eq > 0 && p.substring(0, eq) == "t") p.substring(eq + 1) else null
                }
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            val port = if (uri.port != -1) ":${uri.port}" else ""
            return ConnectionProfile(
                baseUrl = "${uri.scheme.lowercase()}://$host$port",
                token = token,
            )
        }
    }
}

class InvalidGatewayUrlException : IllegalArgumentException("not a gateway URL with a ?t= token")

/** A reachable-looking host was typed, but nothing supplies its token yet. */
class MissingGatewayTokenException : IllegalArgumentException("gateway URL has no ?t= token and none is saved for this host")
