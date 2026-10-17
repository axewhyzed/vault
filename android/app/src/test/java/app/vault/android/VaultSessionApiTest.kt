package app.vault.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class VaultSessionApiTest {
    @Test fun revokeAllUsesBearerAndClearsCredentialsOnlyAfterSuccess() {
        val server = MockWebServer(); server.start()
        try {
            val store = EncryptedStore(
                ApplicationProvider.getApplicationContext<Context>(),
                VaultCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()),
            )
            store.saveCredentials(Credentials(server.url("/").toString().trimEnd('/'), "va1_access", "2099-01-01T00:00:00Z", "vr1_refresh", "2099-02-01T00:00:00Z", "owner", "device"))
            server.enqueue(MockResponse().setResponseCode(204))

            VaultApi(store).revokeAll()

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/auth/sessions/revoke-all", request.path)
            assertEquals("Bearer va1_access", request.getHeader("Authorization"))
            assertEquals("{}", request.body.readUtf8())
            assertNull(store.credentials())
        } finally { server.shutdown() }
    }

    @Test fun revokeFailureRetainsLocalCredentialsForTruthfulRetry() {
        val server = MockWebServer(); server.start()
        try {
            val store = EncryptedStore(
                ApplicationProvider.getApplicationContext<Context>(),
                VaultCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()),
            )
            val credentials = Credentials(server.url("/").toString().trimEnd('/'), "va1_access", "2099-01-01T00:00:00Z", "vr1_refresh", "2099-02-01T00:00:00Z", "owner", "device")
            store.saveCredentials(credentials)
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))

            runCatching { VaultApi(store).revokeAll() }

            assertEquals(credentials, store.credentials())
        } finally { server.shutdown() }
    }

    @Test fun deviceLoginSendsCredentialsAndSavesTokens() {
        val server = MockWebServer(); server.start()
        try {
            val store = EncryptedStore(
                ApplicationProvider.getApplicationContext<Context>(),
                VaultCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()),
            )
            val serverOrigin = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setResponseCode(201).setBody("""
                {
                    "tokenType": "Bearer",
                    "accessToken": "va1_mock_access_token_12345678901234567890",
                    "accessTokenExpiresAt": "2026-10-17T12:15:00Z",
                    "refreshToken": "vr1_mock_refresh_token_12345678901234567890",
                    "refreshTokenExpiresAt": "2026-10-31T12:00:00Z",
                    "userId": "usr_test_123",
                    "deviceId": "dev_test_456"
                }
            """.trimIndent()))

            val result = VaultApi(store).deviceLogin(serverOrigin, "owner@vault.local", "Password_2026!", "Pixel 8 Pro")
            assertTrue(result.isSuccess)
            val creds = result.getOrThrow()
            assertEquals("va1_mock_access_token_12345678901234567890", creds.accessToken)
            assertEquals("usr_test_123", creds.userId)
            assertEquals("dev_test_456", creds.deviceId)

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/api/v1/devices/login", recorded.path)
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("owner@vault.local"))
            assertTrue(body.contains("Password_2026!"))
            assertTrue(body.contains("Pixel 8 Pro"))
            assertTrue(body.contains("android"))

            // Verify credentials persisted in store
            val stored = store.credentials()
            assertNotNull(stored)
            assertEquals(creds.accessToken, stored!!.accessToken)
        } finally { server.shutdown() }
    }

    @Test fun revokeCurrentDeviceCallsEndpointAndClearsLocalCredentials() {
        val server = MockWebServer(); server.start()
        try {
            val store = EncryptedStore(
                ApplicationProvider.getApplicationContext<Context>(),
                VaultCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()),
            )
            val serverOrigin = server.url("/").toString().trimEnd('/')
            store.saveCredentials(Credentials(serverOrigin, "va1_access", "2099-01-01T00:00:00Z", "vr1_refresh", "2099-02-01T00:00:00Z", "owner", "device"))
            server.enqueue(MockResponse().setResponseCode(204))

            val result = VaultApi(store).revokeCurrentDevice()
            assertTrue(result.isSuccess)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/devices/revoke-current", request.path)
            assertEquals("Bearer va1_access", request.getHeader("Authorization"))
            assertNull(store.credentials())
        } finally { server.shutdown() }
    }

    @Test fun pingReturnsLatencyWhenServerResponds() {
        val server = MockWebServer(); server.start()
        try {
            val store = EncryptedStore(
                ApplicationProvider.getApplicationContext<Context>(),
                VaultCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()),
            )
            val serverOrigin = server.url("/").toString().trimEnd('/')
            store.saveCredentials(Credentials(serverOrigin, "va1_access", "2099-01-01T00:00:00Z", "vr1_refresh", "2099-02-01T00:00:00Z", "owner", "device"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("healthy"))

            val latency = VaultApi(store).ping()
            assertNotNull(latency)
            assertTrue(latency!! >= 0)

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/health/live", request.path)
        } finally { server.shutdown() }
    }

    @Test fun deviceLoginFailureOnInvalidCredentialsDoesNotSaveTokens() {
        val server = MockWebServer(); server.start()
        try {
            val store = EncryptedStore(
                ApplicationProvider.getApplicationContext<Context>(),
                VaultCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()),
            )
            val serverOrigin = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_credentials","message":"Incorrect email or password."}"""))

            val result = VaultApi(store).deviceLogin(serverOrigin, "wrong@vault.local", "wrong-password", "Pixel 8")
            assertTrue(result.isFailure)
            assertNull(store.credentials())
        } finally { server.shutdown() }
    }

    @Test fun revokeCurrentDeviceFailureRetainsCredentialsForRetry() {
        val server = MockWebServer(); server.start()
        try {
            val store = EncryptedStore(
                ApplicationProvider.getApplicationContext<Context>(),
                VaultCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()),
            )
            val serverOrigin = server.url("/").toString().trimEnd('/')
            val creds = Credentials(serverOrigin, "va1_access", "2099-01-01T00:00:00Z", "vr1_refresh", "2099-02-01T00:00:00Z", "owner", "device")
            store.saveCredentials(creds)
            server.enqueue(MockResponse().setResponseCode(500).setBody("Server error"))

            val result = VaultApi(store).revokeCurrentDevice()
            assertTrue(result.isFailure)
            assertEquals(creds, store.credentials())
        } finally { server.shutdown() }
    }

    @Test fun getActiveDevicesReturnsConnectedDevicesList() = kotlinx.coroutines.runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val store = EncryptedStore(
                ApplicationProvider.getApplicationContext<Context>(),
                VaultCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()),
            )
            val serverOrigin = server.url("/").toString().trimEnd('/')
            val creds = Credentials(serverOrigin, "va1_access", "2099-01-01T00:00:00Z", "vr1_refresh", "2099-02-01T00:00:00Z", "owner", "device")
            store.saveCredentials(creds)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"devices":[{"id":"dev-1","name":"Pixel 8","platform":"android","isCurrent":true,"createdAt":"2026-10-17T12:00:00Z"},{"id":"dev-2","name":"Chrome on Windows","platform":"web","isCurrent":false,"createdAt":"2026-10-17T10:00:00Z"}]}""")
            )

            val result = VaultApi(store).getActiveDevices()
            assertTrue(result.isSuccess)
            val devices = result.getOrThrow()
            assertEquals(2, devices.size)
            assertEquals("Pixel 8", devices[0].name)
            assertTrue(devices[0].isCurrent)
            assertEquals("Chrome on Windows", devices[1].name)
            assertFalse(devices[1].isCurrent)

            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertEquals("/api/v1/devices", req.path)
            assertEquals("Bearer va1_access", req.getHeader("Authorization"))
        } finally { server.shutdown() }
    }
}
