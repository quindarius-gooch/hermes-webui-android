package com.example.hermeswebui.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlUtilsTest {

    @Test
    fun normalizeUrl_withHttp_remainsHttp() {
        val input = "http://192.168.1.100:8787"
        val expected = "http://192.168.1.100:8787"
        assertEquals(expected, UrlUtils.normalizeUrl(input))
    }

    @Test
    fun normalizeUrl_withHttps_remainsHttps() {
        val input = "https://hermes.tailscale.net"
        val expected = "https://hermes.tailscale.net"
        assertEquals(expected, UrlUtils.normalizeUrl(input))
    }

    @Test
    fun normalizeUrl_withoutProtocol_prependsHttp() {
        val input = "192.168.1.100:8787"
        val expected = "http://192.168.1.100:8787"
        assertEquals(expected, UrlUtils.normalizeUrl(input))
    }

    @Test
    fun normalizeUrl_withTrailingSlash_removesTrailingSlash() {
        val input = "http://192.168.1.100:8787/"
        val expected = "http://192.168.1.100:8787"
        assertEquals(expected, UrlUtils.normalizeUrl(input))
    }

    @Test
    fun normalizeUrl_withWhitespace_trimsWhitespace() {
        val input = "   https://hermes.tailscale.net/   "
        val expected = "https://hermes.tailscale.net"
        assertEquals(expected, UrlUtils.normalizeUrl(input))
    }
}
