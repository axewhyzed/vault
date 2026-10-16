package app.vault.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InboxDownloadTest {
    private val id = "123e4567-e89b-12d3-a456-426614174000"

    private fun store(server: MockWebServer) = EncryptedStore(
        ApplicationProvider.getApplicationContext<Context>(),
        VaultCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()),
    ).apply {
        saveCredentials(Credentials(server.url("/").toString().trimEnd('/'), "va1_access", "2099-01-01T00:00:00Z", "vr1_refresh", "2099-02-01T00:00:00Z", "owner", "device"))
    }

    private fun detail(store: EncryptedStore, bytes: ByteArray, size: Long = bytes.size.toLong(), hash: String = sha256(bytes)) = InboxDetail(
        id, "file", "available", "thing.bin", size, "2026-10-16T10:00:00Z", "2026-10-16T10:00:00Z", "2026-10-23T10:00:00Z", 1,
        InboxSourceDevice("device", "Phone", "android"), null, "application/octet-stream", "/api/v1/items/$id/download", hash,
        store.credentials()!!.origin, "owner",
    )

    private fun fileResponse(body: String) = MockResponse().setResponseCode(200).setBody(body)
        .addHeader("Content-Disposition", "attachment; filename=thing.bin")
        .addHeader("X-Content-Type-Options", "nosniff")
        .addHeader("Cache-Control", "no-store")

    @Test fun streamsVerifiedFileWithBearerAndNoCookies() {
        val server = MockWebServer(); server.start()
        var file: File? = null
        try {
            val store = store(server)
            server.enqueue(fileResponse("data"))
            val progress = mutableListOf<Long>()
            file = VaultApi(store).downloadToTemp(detail(store, "data".toByteArray())) { progress.add(it) }
            assertEquals("data", file.readText())
            assertTrue(progress.contains(4L))
            val request = server.takeRequest()
            assertEquals("/api/v1/items/$id/download", request.path)
            assertEquals("Bearer va1_access", request.getHeader("Authorization"))
            assertEquals("no-store", request.getHeader("Cache-Control"))
            assertNull(request.getHeader("Cookie"))
            assertEquals(1, server.requestCount)
        } finally { file?.delete(); server.shutdown() }
    }

    @Test fun rejectsRedirectWithoutFollowingIt() {
        val server = MockWebServer(); server.start()
        try {
            val store = store(server)
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://elsewhere.example/file"))
            val failure = assertThrows(ApiFailure::class.java) { VaultApi(store).downloadToTemp(detail(store, byteArrayOf(1))) }
            assertEquals("inbox_request_failed", failure.code)
            assertEquals(1, server.requestCount)
            assertEquals("Bearer va1_access", server.takeRequest().getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun rejectsShortOversizedAndHashMismatchAndDeletesTemp() {
        val server = MockWebServer(); server.start()
        val tempDirectory = File(System.getProperty("java.io.tmpdir") ?: ".")
        val before = tempDirectory.listFiles()?.filter { it.name.startsWith("vault-inbox-") }?.toSet().orEmpty()
        try {
            val store = store(server)
            server.enqueue(MockResponse().setResponseCode(200).setChunkedBody("abc", 2)
                .addHeader("Content-Disposition", "attachment; filename=thing.bin").addHeader("X-Content-Type-Options", "nosniff").addHeader("Cache-Control", "no-store"))
            val oversize = assertThrows(ApiFailure::class.java) { VaultApi(store).downloadToTemp(detail(store, "ab".toByteArray(), size = 2)) }
            assertEquals("invalid_download_size", oversize.code)

            server.enqueue(MockResponse().setResponseCode(200).setChunkedBody("ab", 2)
                .addHeader("Content-Disposition", "attachment; filename=thing.bin").addHeader("X-Content-Type-Options", "nosniff").addHeader("Cache-Control", "no-store"))
            val short = assertThrows(ApiFailure::class.java) { VaultApi(store).downloadToTemp(detail(store, "abc".toByteArray(), size = 3)) }
            assertEquals("invalid_download_size", short.code)

            server.enqueue(fileResponse("data"))
            val badHash = assertThrows(ApiFailure::class.java) { VaultApi(store).downloadToTemp(detail(store, "data".toByteArray(), hash = "0".repeat(64))) }
            assertEquals("invalid_download_hash", badHash.code)
            val after = tempDirectory.listFiles()?.filter { it.name.startsWith("vault-inbox-") }?.toSet().orEmpty()
            assertEquals(before, after)
        } finally { server.shutdown() }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
