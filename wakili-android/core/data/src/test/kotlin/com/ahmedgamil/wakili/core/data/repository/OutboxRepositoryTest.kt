package com.ahmedgamil.wakili.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxRepositoryTest {

    @Test
    fun `same client message id creates one outbox entry`() {
        val repository = OutboxRepository()

        val first = repository.add("s", "hello", emptyMap(), null, emptyList(), "message-1")
        val duplicate = repository.add("s", "hello", emptyMap(), null, emptyList(), "message-1")

        assertSame(first, duplicate)
        assertEquals(1, repository.outbox.value.getValue("s").size)
    }

    @Test
    fun `retry is single flight until marked failed again`() {
        val repository = OutboxRepository()
        val entry = repository.add("s", "hello", emptyMap(), null, emptyList(), "message-1")

        assertTrue(repository.markRetrying("s", entry.id))
        assertFalse(repository.markRetrying("s", entry.id))
        assertEquals(OutboxStatus.Retrying, repository.outbox.value.getValue("s").single().status)

        repository.markFailed("s", entry.id)
        assertEquals(OutboxStatus.Failed, repository.outbox.value.getValue("s").single().status)
        assertTrue(repository.markRetrying("s", entry.id))
    }
}
