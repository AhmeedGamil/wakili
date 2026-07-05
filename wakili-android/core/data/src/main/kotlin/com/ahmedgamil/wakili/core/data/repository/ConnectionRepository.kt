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

    /** Switch to another endpoint of the same gateway (connection switcher). */
    suspend fun switchEndpoint(url: String): ConnectionProfile = connect(url)

    companion object {
        /**
         * Accepts what the gateway prints/encodes in its QRs:
         *   http://192.168.1.10:8730/?t=TOKEN
         *   http://100.x.y.z:8730/?t=TOKEN
         *   https://name.trycloudflare.com/cf.html?t=TOKEN
         * Base = scheme://host[:port]; the ?t= token is required (it's the only
         * credential — without it the gateway answers 401).
         */
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
