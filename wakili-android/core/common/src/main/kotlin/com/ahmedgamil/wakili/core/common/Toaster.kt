package com.ahmedgamil.wakili.core.common

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * App-wide transient notices — the web client's `.toast` (main.js emitter).
 * Any layer can inject this and `show()`; the app shell renders the messages
 * in one auto-hiding host above everything.
 */
@Singleton
class Toaster @Inject constructor() {

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    fun show(text: String) {
        _messages.tryEmit(text)
    }
}
