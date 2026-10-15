package app.vault.android

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PairingPayloadTest {
    @Test fun parsesQrAndKeepsSecretOutOfDeepLink() {
        val qr = """{"version":1,"serverOrigin":"https://vault.axewhyzedlabs.co.in","pairingSessionId":"123e4567-e89b-12d3-a456-426614174000","pairingSecret":"abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG","expiresAt":"${Instant.now().plusSeconds(300)}"}"""
        val parsed = PairingPayload.parse(qr, "https://vault.axewhyzedlabs.co.in")
        assertEquals("https://vault.axewhyzedlabs.co.in", parsed.serverOrigin)
        assertTrue(parsed.toDeepLink().contains("pairingSessionId"))
        assertFalse(parsed.toDeepLink().contains("pairingSecret"))
    }

    @Test fun acceptsPublicOnlyDeepLinkAndRejectsUnconfiguredOrigin() {
        val link = "vault://pair?serverOrigin=https%3A%2F%2Fvault.axewhyzedlabs.co.in&pairingSessionId=123e4567-e89b-12d3-a456-426614174000"
        assertNull(PairingPayload.parse(link, "https://vault.axewhyzedlabs.co.in").pairingSecret)
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload.parse("vault://pair?serverOrigin=https%3A%2F%2Fevil.example&pairingSessionId=123e4567-e89b-12d3-a456-426614174000", "https://vault.axewhyzedlabs.co.in")
        }
    }

    @Test fun debugBuildCanBeConfiguredToUseLocalHttpsOnly() {
        val link = "vault://pair?serverOrigin=https%3A%2F%2Flocalhost%3A8443&pairingSessionId=123e4567-e89b-12d3-a456-426614174000"
        assertEquals("https://localhost:8443", PairingPayload.parse(link, "https://localhost:8443").serverOrigin)
    }

    @Test fun rejectsRepeatedQueryParameters() {
        val link = "vault://pair?serverOrigin=https%3A%2F%2Fvault.axewhyzedlabs.co.in&serverOrigin=https%3A%2F%2Fevil.example&pairingSessionId=123e4567-e89b-12d3-a456-426614174000"
        assertThrows(IllegalArgumentException::class.java) { PairingPayload.parse(link, "https://vault.axewhyzedlabs.co.in") }
    }
}
