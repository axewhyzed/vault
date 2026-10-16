package app.vault.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairingApiTest {
    @Test fun sendsSingleUsePairingPayloadWithoutCookieOrBearerAndStoresRotatingTokens() {
        val server = MockWebServer(); server.start()
        try {
            val store = EncryptedStore(
                ApplicationProvider.getApplicationContext<Context>(),
                VaultCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()),
            )
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"tokenType":"Bearer","accessToken":"va1_access","accessTokenExpiresAt":"2099-01-01T00:00:00Z","refreshToken":"vr1_refresh","refreshTokenExpiresAt":"2099-02-01T00:00:00Z","userId":"owner","deviceId":"device"}"""))
            val payload = PairingPayload(server.url("/").toString().trimEnd('/'), "123e4567-e89b-12d3-a456-426614174000", "A".repeat(43))

            val credentials = VaultApi(store).pair(payload, payload.pairingSecret!!, "Mihir's phone")

            assertEquals("owner", credentials.userId)
            assertEquals("device", credentials.deviceId)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/devices/pair", request.path)
            assertNotNull(request.getHeader("Idempotency-Key"))
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals(payload.pairingSessionId, body.getString("pairingSessionId"))
            assertEquals(payload.pairingSecret, body.getString("pairingSecret"))
            assertEquals("Mihir's phone", body.getString("deviceName"))
        } finally { server.shutdown() }
    }

    @Test fun rejectsUnexpectedTokenType() {
        val server = MockWebServer(); server.start()
        try {
            val store = EncryptedStore(
                ApplicationProvider.getApplicationContext<Context>(),
                VaultCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()),
            )
            server.enqueue(MockResponse().setBody("""{"tokenType":"Basic"}"""))
            val failure = runCatching {
                VaultApi(store).pair(PairingPayload(server.url("/").toString().trimEnd('/'), "123e4567-e89b-12d3-a456-426614174000"), "secret", "phone")
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertNull(store.credentials())
        } finally { server.shutdown() }
    }
}
