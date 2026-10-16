package app.vault.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.Dispatcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InboxApiTest {
    private fun store(server: MockWebServer, user: String = "owner") = EncryptedStore(
        ApplicationProvider.getApplicationContext<Context>(),
        VaultCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()),
    ).apply {
        saveCredentials(Credentials(server.url("/").toString().trimEnd('/'), "va1_access", "2099-01-01T00:00:00Z", "vr1_refresh", "2099-02-01T00:00:00Z", user, "device"))
    }

    private fun summary(id: String = "123e4567-e89b-12d3-a456-426614174000") = """{"id":"$id","type":"text","state":"available","displayName":"Note","sizeBytes":4,"createdAt":"2026-10-16T10:00:00Z","availableAt":"2026-10-16T10:00:00Z","expiresAt":"2026-10-23T10:00:00Z","version":1,"sourceDevice":{"id":"device","name":"Phone","platform":"android"}}"""

    @Test fun listEncodesOpaqueCursorAndUsesNoStoreBearer() {
        val server = MockWebServer(); server.start()
        try {
            val store = store(server)
            server.enqueue(MockResponse().setBody("""{"items":[${summary()}],"nextCursor":"abc","quota":{"activeBytes":4,"maxActiveBytes":100,"activeItems":1,"maxActiveItems":10}}"""))
            val cursor = "opaque +/= & cursor"
            val page = VaultApi(store).listItems(cursor, 7)
            assertEquals(1, page.items.size)
            assertEquals("abc", page.nextCursor)
            val request = server.takeRequest()
            assertEquals("Bearer va1_access", request.getHeader("Authorization"))
            assertEquals("no-store", request.getHeader("Cache-Control"))
            assertEquals(7, request.requestUrl!!.queryParameter("limit")!!.toInt())
            assertEquals(cursor, request.requestUrl!!.queryParameter("cursor"))
        } finally { server.shutdown() }
    }

    @Test fun unauthorizedIsRefreshedOnceThenRetried() {
        val server = MockWebServer(); server.start()
        try {
            val store = store(server)
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"tokenType":"Bearer","accessToken":"va1_rotated","accessTokenExpiresAt":"2099-01-01T00:00:00Z","refreshToken":"vr1_rotated","refreshTokenExpiresAt":"2099-02-01T00:00:00Z","userId":"owner","deviceId":"device"}"""))
            server.enqueue(MockResponse().setBody("""{"items":[],"nextCursor":null,"quota":{"activeBytes":0,"maxActiveBytes":100,"activeItems":0,"maxActiveItems":10}}"""))
            assertTrue(VaultApi(store).listItems().items.isEmpty())
            val requests = (1..3).map { server.takeRequest() }
            assertEquals("/api/v1/auth/refresh", requests[1].path)
            assertEquals("Bearer va1_rotated", requests[2].getHeader("Authorization"))
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun malformedJsonGetsSafeFailureAndOwnerMismatchSendsNoRequest() {
        val server = MockWebServer(); server.start()
        try {
            val store = store(server)
            server.enqueue(MockResponse().setBody("not-json"))
            val failure = assertThrows(ApiFailure::class.java) { VaultApi(store).listItems() }
            assertEquals("invalid_inbox_response", failure.code)
            assertFalse(failure.message!!.contains("not-json"))
            server.takeRequest()
            val detail = InboxDetail("123e4567-e89b-12d3-a456-426614174000", "file", "available", "x", 1, "", "", "", 1,
                InboxSourceDevice("d", "phone", "android"), null, "application/octet-stream", "/api/v1/items/123e4567-e89b-12d3-a456-426614174000/download", "0".repeat(64), "https://other.example", "owner")
            val mismatch = assertThrows(ApiFailure::class.java) { VaultApi(store).downloadToTemp(detail) }
            assertEquals("draft_owner_mismatch", mismatch.code)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
