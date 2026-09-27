package org.olcbox.app.data.importer

import org.olcbox.app.data.model.ProxyCore
import org.olcbox.app.data.model.SnolcConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnolcUriParserTest {

    @Test
    fun parseStandardUri() {
        val uri = "snolc://mytoken@198.51.100.1:443?carrier=ssh&protection=noise&dns=8.8.8.8%3A53&proxy_link=vless%3A%2F%2Ftest#MyServer"
        val parsed = SnolcUriParser.parse(uri)

        assertNotNull(parsed)
        assertEquals("MyServer", parsed.name)
        assertEquals("198.51.100.1:443", parsed.config.serverEndpoint)
        assertEquals("mytoken", parsed.config.authToken)
        assertEquals(SnolcConfig.CARRIER_SSH, parsed.config.carrier)
        assertEquals(SnolcConfig.PROTECTION_NOISE, parsed.config.protection)
        assertEquals("8.8.8.8:53", parsed.config.dnsServer)
        assertEquals("vless://test", parsed.config.proxyLink)
    }

    @Test
    fun composeAndRoundTripStandard() {
        val config = SnolcConfig(
            serverEndpoint = "203.0.113.15:8443",
            carrier = SnolcConfig.CARRIER_TCP,
            protection = SnolcConfig.PROTECTION_TLS,
            authToken = "secret-token-123",
            dnsServer = "9.9.9.9:53",
            proxyLink = "ss://YWVzLTEyOC1nY206cGFzc0AxLjIuMy40OjQ0Mw#exit",
            proxyCore = ProxyCore.Xray,
            debug = true,
        )
        val composed = SnolcUriParser.compose("Tokyo Edge", config)
        assertTrue(composed.startsWith(SnolcUriParser.SCHEME))

        val reparsed = SnolcUriParser.parse(composed)
        assertNotNull(reparsed)
        assertEquals("Tokyo Edge", reparsed.name)
        assertEquals(config.serverEndpoint, reparsed.config.serverEndpoint)
        assertEquals(config.carrier, reparsed.config.carrier)
        assertEquals(config.protection, reparsed.config.protection)
        assertEquals(config.authToken, reparsed.config.authToken)
        assertEquals(config.dnsServer, reparsed.config.dnsServer)
        assertEquals(config.proxyLink, reparsed.config.proxyLink)
        assertEquals(config.proxyCore, reparsed.config.proxyCore)
        assertTrue(reparsed.config.debug)
    }

    @Test
    fun composeAndRoundTripCustomToml() {
        val toml = """
            wire_version = 1
            [engine]
            max_sessions = 8
            [outbound]
            endpoint = "example.com:443"
        """.trimIndent()

        val config = SnolcConfig(customToml = toml)
        val composed = SnolcUriParser.compose("Custom SNOLC", config)

        val reparsed = SnolcUriParser.parse(composed)
        assertNotNull(reparsed)
        assertEquals("Custom SNOLC", reparsed.name)
        assertEquals(toml, reparsed.config.customToml)
    }

    @Test
    fun looksLikeSnolcToml() {
        assertTrue(SnolcUriParser.looksLikeSnolcToml("""
            [engine]
            max_sessions = 4
        """))
        assertTrue(SnolcUriParser.looksLikeSnolcToml("""
            carrier = "tcp"
            protection = "noise"
        """))
        assertFalse(SnolcUriParser.looksLikeSnolcToml("vless://user@host:443"))
        assertFalse(SnolcUriParser.looksLikeSnolcToml("Hello world"))
    }

    @Test
    fun invalidUriReturnsNull() {
        assertNull(SnolcUriParser.parse("vless://user@host:443"))
        assertNull(SnolcUriParser.parse("snolc://"))
    }
}
