package app.vault.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileUploadApiTest {
    @Test fun uploadsFileAsInitiateStreamCompleteWithStableIntentKey() {
        val server = MockWebServer(); server.start()
        try {
            val store = EncryptedStore(
                ApplicationProvider.getApplicationContext<Context>(),
                VaultCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()),
            )
            val origin = server.url("/").toString().trimEnd('/')
            store.saveCredentials(Credentials(origin, "va1_access", "2099-01-01T00:00:00Z", "vr1_refresh", "2099-02-01T00:00:00Z", "owner", "device"))
            val bytes = "file-content-for-vault".toByteArray()
            val spool = store.createSpool("file-upload-test", ByteArrayInputStream(bytes))
            val draft = Draft("file-upload", "stable-file-intent-key", origin, "owner", retentionDays = 3, spoolName = "file-upload-test", displayName = "notes.bin", contentType = "application/octet-stream", sizeBytes = spool.sizeBytes, sha256 = spool.sha256, state = DraftState.UPLOADING)
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"uploadId":"upload-123"}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val progress = UploadDraftProgress(store, draft)

            val result = VaultApi(store).upload(draft, onState = progress::saveState, onProgress = progress::saveBytesSent)

            assertEquals(DraftState.AVAILABLE, result.state)
            assertEquals("upload-123", progress.current().uploadId)
            assertTrue(progress.current().bytesSent > 0)
            val initiate = server.takeRequest()
            val content = server.takeRequest()
            val complete = server.takeRequest()
            assertEquals("POST", initiate.method)
            assertEquals("/api/v1/uploads/initiate", initiate.path)
            assertEquals("stable-file-intent-key", initiate.getHeader("Idempotency-Key"))
            assertEquals(spool.sha256, org.json.JSONObject(initiate.body.readUtf8()).getString("sha256"))
            assertEquals("PUT", content.method)
            assertEquals("/api/v1/uploads/upload-123/content", content.path)
            assertEquals(bytes.toList(), content.body.clone().readByteArray().toList())
            assertEquals("stable-file-intent-key", content.getHeader("Idempotency-Key"))
            assertEquals("POST", complete.method)
            assertEquals("/api/v1/uploads/upload-123/complete", complete.path)
            assertEquals("stable-file-intent-key", complete.getHeader("Idempotency-Key"))
        } finally { server.shutdown() }
    }

    @Test fun fileFailureAfterInitiationLeavesUploadIdAndProgressForRetry() {
        val server = MockWebServer(); server.start()
        try {
            val store = EncryptedStore(
                ApplicationProvider.getApplicationContext<Context>(),
                VaultCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()),
            )
            val origin = server.url("/").toString().trimEnd('/')
            store.saveCredentials(Credentials(origin, "va1_access", "2099-01-01T00:00:00Z", "vr1_refresh", "2099-02-01T00:00:00Z", "owner", "device"))
            val bytes = "retryable-content".toByteArray()
            val spool = store.createSpool("file-upload-retry-test", ByteArrayInputStream(bytes))
            val draft = Draft("file-upload-retry", "stable-retry-key", origin, "owner", spoolName = "file-upload-retry-test", displayName = "notes.bin", contentType = "application/octet-stream", sizeBytes = spool.sizeBytes, sha256 = spool.sha256, state = DraftState.UPLOADING)
            val progress = UploadDraftProgress(store, draft)
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"uploadId":"upload-retry-123"}"""))
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))

            val failure = runCatching { VaultApi(store).upload(draft, onState = progress::saveState, onProgress = progress::saveBytesSent) }.exceptionOrNull()

            assertTrue(failure is ApiFailure)
            val persisted = store.draft(draft.id)!!
            assertEquals("upload-retry-123", persisted.uploadId)
            assertEquals("stable-retry-key", persisted.idempotencyKey)
            assertTrue(persisted.bytesSent > 0)
        } finally { server.shutdown() }
    }
}
