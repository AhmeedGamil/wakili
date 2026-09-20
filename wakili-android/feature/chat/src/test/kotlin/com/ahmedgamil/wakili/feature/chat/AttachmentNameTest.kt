package com.ahmedgamil.wakili.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentNameTest {

    @Test
    fun `free name is kept`() {
        assertEquals("photo.jpg", uniqueAttachmentName("photo.jpg", listOf("other.jpg")))
    }

    @Test
    fun `taken name gets a counter before the extension`() {
        assertEquals("photo (2).jpg", uniqueAttachmentName("photo.jpg", listOf("photo.jpg")))
        assertEquals("photo (3).jpg", uniqueAttachmentName("photo.jpg", listOf("photo.jpg", "photo (2).jpg")))
    }

    @Test
    fun `names without an extension and dotfiles keep their shape`() {
        assertEquals("Makefile (2)", uniqueAttachmentName("Makefile", listOf("Makefile")))
        assertEquals(".env (2)", uniqueAttachmentName(".env", listOf(".env")))
    }
}
