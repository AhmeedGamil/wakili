package com.ahmedgamil.wakili.core.network.di

import com.ahmedgamil.wakili.core.network.api.GatewayApi
import com.ahmedgamil.wakili.core.network.api.GatewayConnection
import com.ahmedgamil.wakili.core.network.api.GatewayRouteInterceptor
import com.ahmedgamil.wakili.core.network.stream.CfWebSocketStreamClient
import com.ahmedgamil.wakili.core.network.stream.EventStreamClientFactory
import com.ahmedgamil.wakili.core.network.stream.SseStreamClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun okHttpClient(connection: GatewayConnection): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(GatewayRouteInterceptor(connection))
            .connectTimeout(15, TimeUnit.SECONDS)
            // Matches the web client: 15s default; long-running calls (exec/term/
            // upload) override per-request via Retrofit's timeout tag in phase 4+.
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun gatewayApi(client: OkHttpClient, json: Json): GatewayApi =
        Retrofit.Builder()
            .baseUrl(GatewayConnection.PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GatewayApi::class.java)

    @Provides
    @Singleton
    fun streamClientFactory(
        sse: SseStreamClient,
        cfWebSocket: CfWebSocketStreamClient,
    ): EventStreamClientFactory = EventStreamClientFactory { profile ->
        if (profile.isCloudflare) cfWebSocket else sse
    }
}
