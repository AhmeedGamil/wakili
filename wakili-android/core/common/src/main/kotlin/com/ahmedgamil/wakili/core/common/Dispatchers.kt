package com.ahmedgamil.wakili.core.common

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val wakiliDispatcher: WakiliDispatchers)

enum class WakiliDispatchers {
    Default,
    IO,
}
