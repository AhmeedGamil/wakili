package com.ahmedgamil.wakili.core.network.api

import com.ahmedgamil.wakili.core.model.ConnectionProfile
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    // Pinged when any request answers 401 mid-session (token revoked/rotated):
    // the app bounces back to the connect gate instead of failing quietly.
    private val _unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unauthorized: SharedFlow<Unit> = _unauthorized.asSharedFlow()

    fun update(profile: ConnectionProfile?) {
        _profile.value = profile
    }

    fun signalUnauthorized() {
        _unauthorized.tryEmit(Unit)
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
        // Fail the CALL, never the process: an interceptor may only throw
        // IOException — anything else escapes OkHttp's dispatcher thread on
        // async calls and crashes the app. Requests can legitimately fire with
        // no profile yet (fresh install: the sidebar's ViewModel refreshes
        // behind the connect gate); they must land in the caller's catch.
        val profile = connection.profile.value
            ?: throw IOException("No gateway connection configured")
        val base = profile.baseUrl.toHttpUrlOrNull()
            ?: throw IOException("Invalid gateway base URL: ${profile.baseUrl}")

        val request = chain.request()
        val rerouted = request.url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .build()

        val response = chain.proceed(
            request.newBuilder()
                .url(rerouted)
                .header("x-auth-token", profile.token)
                .build(),
        )
        if (response.code == 401) connection.signalUnauthorized()
        return response
    }
}
