package app.vault.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
