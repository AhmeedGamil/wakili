package com.ahmedgamil.wakili.core.data.repository

import com.ahmedgamil.wakili.core.model.AutostartState
import com.ahmedgamil.wakili.core.model.PowerState
import com.ahmedgamil.wakili.core.network.api.GatewayApi
import com.ahmedgamil.wakili.core.network.dto.AutostartBody
import com.ahmedgamil.wakili.core.network.dto.KeepAwakeBody
import com.ahmedgamil.wakili.core.network.dto.toModel
import javax.inject.Inject
import javax.inject.Singleton

/** Laptop device controls — the web DeviceMenu's actions. */
@Singleton
class DeviceRepository @Inject constructor(
    private val api: GatewayApi,
) {
    suspend fun power(): PowerState = api.power().toModel()
    suspend fun lockScreen() { api.lockScreen() }
    suspend fun screenOff() { api.screenOff() }
    suspend fun lockAndOff() { api.lockOff() }

    /** Reports the gateway's own verdict: it answers {ok:false} when no
     *  shutdown command on that OS worked, which is not an HTTP failure. */
    suspend fun shutdown(): Boolean = api.shutdown().ok

    suspend fun keepAwake(on: Boolean): PowerState = api.keepAwake(KeepAwakeBody(on)).toModel()
    suspend fun autostart(): AutostartState = api.autostart().toModel()
    suspend fun setAutostart(on: Boolean): AutostartState = api.setAutostart(AutostartBody(on)).toModel()
}
