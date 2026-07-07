package com.ahmedgamil.wakili.core.network.api

import com.ahmedgamil.wakili.core.model.ConnectionProfile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * The single mutable holder of "which gateway are we talking to". Retrofit is
 * built once against a placeholder host; [GatewayRouteInterceptor] rewrites every
 * request to the current profile and stamps the auth header. Switching endpoints
 * (LAN ↔ Tailscale ↔ Cloudflare) is just [update] — no client rebuilds.
 */
@Singleton
class GatewayConnection @Inject constructor() {

    private val _profile = MutableStateFlow<ConnectionProfile?>(null)
    val profile: StateFlow<ConnectionProfile?> = _profile.asStateFlow()

    fun update(profile: ConnectionProfile?) {
        _profile.value = profile
    }

    fun require(): ConnectionProfile =
        _profile.value ?: error("No gateway connection configured")

    companion object {
        /** Placeholder Retrofit base; every request gets rewritten. */
        const val PLACEHOLDER_BASE_URL = "http://gateway.invalid/"
    }
}

class GatewayRouteInterceptor(
    private val connection: GatewayConnection,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val profile = connection.require()
        val base = profile.baseUrl.toHttpUrlOrNull()
            ?: error("Invalid gateway base URL: ${profile.baseUrl}")

        val request = chain.request()
        val rerouted = request.url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .build()

        return chain.proceed(
            request.newBuilder()
                .url(rerouted)
                .header("x-auth-token", profile.token)
                .build(),
        )
    }
}
