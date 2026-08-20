package com.ahmedgamil.wakili.core.network.dto

import kotlinx.serialization.json.Json
import com.ahmedgamil.wakili.core.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `conditional detail stub decodes without session metadata`() {
        val dto = json.decodeFromString(
            SessionDto.serializer(),
            """{
                "unchanged": true,
                "updatedAt": 1234,
                "busy": true,
                "pending": 2,
                "parts": [{"type":"text","text":"still running"}],
                "activeClientMessageId": "client-7"
            }""",
        )

        assertEquals("", dto.id)
        assertTrue(dto.unchanged)
        assertEquals(1234L, dto.updatedAt)
        assertTrue(dto.busy)
        assertEquals(2, dto.pending)
        assertEquals("still running", dto.parts?.single()?.text)
        assertEquals("client-7", dto.activeClientMessageId)
    }

    @Test
    fun `history message identity maps to user and assistant`() {
        assertEquals(
            "client-7",
            (MessageDto(role = "user", text = "question", clientMessageId = "client-7").toModel()
                as ChatMessage.User).clientMessageId,
        )
        assertEquals(
            "client-7",
            (MessageDto(role = "assistant", text = "answer", clientMessageId = "client-7").toModel()
                as ChatMessage.Assistant).clientMessageId,
        )
    }
}
