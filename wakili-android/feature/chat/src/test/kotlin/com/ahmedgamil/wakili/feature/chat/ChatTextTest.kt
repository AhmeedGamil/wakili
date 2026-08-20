package com.ahmedgamil.wakili.feature.chat

import com.ahmedgamil.wakili.core.model.Part
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTextTest {

    @Test
    fun `tool copy text includes name input and output`() {
        val tool = Part.Tool(
            id = "tool-1",
            name = "Read",
            input = buildJsonObject { put("file_path", "notes.txt") },
            output = "contents",
        )

        assertEquals("Read\n\nfile_path: notes.txt\n\ncontents", tool.asPlainText())
    }
}
