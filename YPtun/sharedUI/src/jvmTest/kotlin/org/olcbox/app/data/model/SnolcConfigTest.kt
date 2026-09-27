package org.olcbox.app.data.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SnolcConfigTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun engineTypeParsing() {
        assertEquals(EngineType.Snolc, EngineType.fromValue("snolc"))
        assertEquals(EngineType.Snolc, EngineType.fromValue("snolc-vpn"))
        assertEquals(EngineType.Snolc, EngineType.fromValue("snolcng"))
        assertEquals(EngineType.Snolc, EngineType.fromValue("SNOLC"))
    }

    @Test
    fun completenessValidation() {
        assertFalse(SnolcConfig().isComplete())
        assertTrue(SnolcConfig(serverEndpoint = "198.51.100.1:443").isComplete())
        assertTrue(SnolcConfig(customToml = "wire_version = 1").isComplete())
        assertTrue(SnolcConfig(serverEndpoint = "domain.com:8443", carrier = SnolcConfig.CARRIER_SSH).isComplete())
    }

    @Test
    fun locationConfigIntegration() {
        val snolc = SnolcConfig(
            serverEndpoint = " 203.0.113.10:443 ",
            carrier = "tcp",
            protection = "noise",
            authToken = "secret-token",
        )
        val location = LocationConfig(
            name = "Test SNOLC",
            engine = EngineType.Snolc,
            snolc = snolc,
        )

        assertTrue(location.isComplete())
        val normalized = location.normalized()
        assertEquals("203.0.113.10:443", normalized.snolc?.serverEndpoint)
        assertEquals("secret-token", normalized.snolc?.authToken)
    }

    @Test
    fun serializationRoundTrip() {
        val original = LocationConfig(
            name = "SNOLC Server",
            engine = EngineType.Snolc,
            snolc = SnolcConfig(
                serverEndpoint = "192.0.2.55:9000",
                carrier = SnolcConfig.CARRIER_TCP,
                protection = SnolcConfig.PROTECTION_NOISE,
                authToken = "auth-123",
                dnsServer = "1.1.1.1:53",
            ),
        )

        val text = json.encodeToString(LocationConfig.serializer(), original)
        val decoded = json.decodeFromString(LocationConfig.serializer(), text)

        assertEquals(EngineType.Snolc, decoded.engine)
        assertNotNull(decoded.snolc)
        assertEquals("192.0.2.55:9000", decoded.snolc?.serverEndpoint)
        assertEquals(SnolcConfig.CARRIER_TCP, decoded.snolc?.carrier)
        assertEquals(SnolcConfig.PROTECTION_NOISE, decoded.snolc?.protection)
        assertEquals("auth-123", decoded.snolc?.authToken)
    }
}
