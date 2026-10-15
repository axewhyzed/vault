package app.vault.android

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetryRequestTest {
    @Test fun repeatedWorkerAttemptUsesSameIntentKeyAndBody() {
        val server = MockWebServer(); server.start()
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val testKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val store = EncryptedStore(context, VaultCrypto(testKey))
            store.saveCredentials(Credentials(server.url("/").toString().trimEnd('/'), "va1_access", "2099-01-01T00:00:00Z", "vr1_refresh", "2099-02-01T00:00:00Z", "u", "d"))
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            val draft = Draft("d-1", "123e4567-e89b-12d3-a456-426614174000", store.credentials()!!.origin, "u", retentionDays = 3, text = "same intent", displayName = "Text snippet", contentType = "text/plain", sizeBytes = 11)
            assertThrows(ApiFailure::class.java) { VaultApi(store).upload(draft) {} }
            VaultApi(store).upload(draft) {}
            val first = server.takeRequest(); val second = server.takeRequest()
            assertEquals("123e4567-e89b-12d3-a456-426614174000", first.getHeader("Idempotency-Key"))
            assertEquals(first.getHeader("Idempotency-Key"), second.getHeader("Idempotency-Key"))
            val firstBody = first.body.clone().readUtf8()
            val secondBody = second.body.clone().readUtf8()
            assertEquals(firstBody, secondBody)
            assertEquals(3, org.json.JSONObject(secondBody).getInt("retentionDays"))
        } finally { server.shutdown() }
    }

    @Test fun mismatchedDraftOwnerIsFailedBeforeSendingBearerToken() {
        val server = MockWebServer(); server.start()
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val testKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val store = EncryptedStore(context, VaultCrypto(testKey))
            val origin = server.url("/").toString().trimEnd('/')
            store.saveCredentials(Credentials(origin, "va1_access", "2099-01-01T00:00:00Z", "vr1_refresh", "2099-02-01T00:00:00Z", "current-user", "device"))
            val draft = Draft("old-draft", "123e4567-e89b-12d3-a456-426614174001", origin, "prior-user", text = "private", displayName = "Text", contentType = "text/plain", sizeBytes = 7)
            val failure = assertThrows(ApiFailure::class.java) { VaultApi(store).upload(draft) }
            assertEquals("draft_owner_mismatch", failure.code)
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun malformedHttpPrefixIsStoredAsTextInsteadOfUrl() {
        val server = MockWebServer(); server.start()
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val testKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val store = EncryptedStore(context, VaultCrypto(testKey))
            val origin = server.url("/").toString().trimEnd('/')
            store.saveCredentials(Credentials(origin, "va1_access", "2099-01-01T00:00:00Z", "vr1_refresh", "2099-02-01T00:00:00Z", "u", "device"))
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            val malformed = "https://[broken"
            val draft = Draft("url-check", "123e4567-e89b-12d3-a456-426614174002", origin, "u", text = malformed, displayName = "Text", contentType = "text/plain", sizeBytes = malformed.length.toLong())
            VaultApi(store).upload(draft)
            val request = server.takeRequest()
            assertEquals("/api/v1/items/text", request.path)
            assertEquals(malformed, org.json.JSONObject(request.body.readUtf8()).getString("text"))
        } finally { server.shutdown() }
    }

    @Test fun concurrentExpiredUploadsShareOneRefreshAndKeepSession() {
        val server = MockWebServer(); server.start()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val testKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val store = EncryptedStore(context, VaultCrypto(testKey))
            val origin = server.url("/").toString().trimEnd('/')
            store.saveCredentials(Credentials(origin, "va1_old", "2099-01-01T00:00:00Z", "vr1_original", "2099-02-01T00:00:00Z", "owner", "device"))
            val originalRequests = CountDownLatch(2)
            val refreshRequests = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/api/v1/auth/refresh" -> {
                        refreshRequests.incrementAndGet()
                        MockResponse().setResponseCode(200).setBody("""{"tokenType":"Bearer","accessToken":"va1_rotated","accessTokenExpiresAt":"2099-01-01T00:00:00Z","refreshToken":"vr1_rotated","refreshTokenExpiresAt":"2099-02-01T00:00:00Z","userId":"owner","deviceId":"device"}""")
                    }
                    request.path == "/api/v1/items/text" && request.getHeader("Authorization") == "Bearer va1_old" -> {
                        originalRequests.countDown()
                        check(originalRequests.await(5, TimeUnit.SECONDS)) { "Both original uploads did not arrive." }
                        MockResponse().setResponseCode(401).setBody("{}")
                    }
                    request.path == "/api/v1/items/text" && request.getHeader("Authorization") == "Bearer va1_rotated" -> MockResponse().setResponseCode(201).setBody("{}")
                    else -> MockResponse().setResponseCode(500).setBody("{}")
                }
            }
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val futures = (1..2).map { index -> executor.submit {
                ready.countDown(); check(start.await(5, TimeUnit.SECONDS))
                val text = "parallel upload $index"
                val draft = Draft("parallel-$index", "123e4567-e89b-12d3-a456-42661417400$index", origin, "owner", text = text, displayName = "Text", contentType = "text/plain", sizeBytes = text.length.toLong())
                VaultApi(store).upload(draft)
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }

            val requests = (1..5).map { server.takeRequest(5, TimeUnit.SECONDS)!! }
            assertEquals(1, refreshRequests.get())
            assertEquals(1, requests.count { it.path == "/api/v1/auth/refresh" })
            val uploads = requests.filter { it.path == "/api/v1/items/text" }
            assertEquals(4, uploads.size)
            assertEquals(2, uploads.count { it.getHeader("Authorization") == "Bearer va1_old" })
            assertEquals(2, uploads.count { it.getHeader("Authorization") == "Bearer va1_rotated" })
            assertEquals("vr1_rotated", store.credentials()?.refreshToken)
            assertEquals("va1_rotated", store.credentials()?.accessToken)
        } finally {
            executor.shutdownNow()
            server.shutdown()
        }
    }
}
