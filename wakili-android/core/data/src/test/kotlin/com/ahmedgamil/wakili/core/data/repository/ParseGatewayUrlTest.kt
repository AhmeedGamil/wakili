package com.ahmedgamil.wakili.core.data.repository

import com.ahmedgamil.wakili.core.data.repository.ConnectionRepository.Companion.parseGatewayUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseGatewayUrlTest {

    @Test
    fun `lan url with token`() {
        val p = parseGatewayUrl("http://192.168.1.10:8730/?t=abc123")!!
        assertEquals("http://192.168.1.10:8730", p.baseUrl)
        assertEquals("abc123", p.token)
        assertEquals(false, p.isCloudflare)
    }

    @Test
    fun `tailscale url`() {
        val p = parseGatewayUrl("http://100.104.128.42:8730/?t=tok_en-URL")!!
        assertEquals("http://100.104.128.42:8730", p.baseUrl)
        assertEquals("tok_en-URL", p.token)
    }

    @Test
    fun `cloudflare cf html url`() {
        val p = parseGatewayUrl("https://random-name.trycloudflare.com/cf.html?t=zzz")!!
        assertEquals("https://random-name.trycloudflare.com", p.baseUrl)
        assertEquals("zzz", p.token)
        assertEquals(true, p.isCloudflare)
    }

    @Test
    fun `token among other params`() {
        val p = parseGatewayUrl("http://host:1/?x=1&t=real&y=2")!!
        assertEquals("real", p.token)
    }

    @Test
    fun `rejects urls without token`() {
        assertNull(parseGatewayUrl("http://192.168.1.10:8730/"))
        assertNull(parseGatewayUrl("http://192.168.1.10:8730/?t="))
    }

    @Test
    fun `rejects non-http input`() {
        assertNull(parseGatewayUrl("ftp://x/?t=a"))
        assertNull(parseGatewayUrl("hello world"))
        assertNull(parseGatewayUrl(""))
    }

    @Test
    fun `whitespace is trimmed`() {
        val p = parseGatewayUrl("  http://a:1/?t=b  ")!!
        assertEquals("http://a:1", p.baseUrl)
    }
}
