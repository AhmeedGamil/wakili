package com.ahmedgamil.wakili.core.common

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether any activity is currently visible — flipped by MainActivity's
 * onStart/onStop. Background notifications only fire when this is false.
 */
@Singleton
class AppForeground @Inject constructor() {

    @Volatile
    var foreground: Boolean = false
}
