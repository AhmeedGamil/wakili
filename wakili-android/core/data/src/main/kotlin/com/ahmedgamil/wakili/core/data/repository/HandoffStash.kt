package com.ahmedgamil.wakili.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands the transcript file from the chat that started an agent handoff to the
 * fresh chat it opens (a new ViewModel), which prefills its composer with the
 * file attached. One-shot: take() clears it, so reopening the chat later starts
 * clean. In-memory only — a handoff interrupted by process death just leaves an
 * ordinary empty chat, which is fine.
 */
@Singleton
class HandoffStash @Inject constructor() {

    private var sessionId: String? = null
    private var file: SendAttachment? = null

    @Synchronized
    fun put(sessionId: String, file: SendAttachment) {
        this.sessionId = sessionId
        this.file = file
    }

    @Synchronized
    fun take(sessionId: String): SendAttachment? {
        if (sessionId != this.sessionId) return null
        val taken = file
        this.sessionId = null
        this.file = null
        return taken
    }
}
