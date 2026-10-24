package app.vault.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UploadWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After fun resetWorkerFactories() {
        UploadWorkerDependencies.storeFactory = { EncryptedStore(it) }
        UploadWorkerDependencies.apiFactory = ::vaultApi
        runCatching { WorkManager.getInstance(context).cancelAllWork().result.get() }
    }

    @Test fun retryAfterFileProgressKeepsUploadIdAndCompletesWithoutReinitializing() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            WorkManagerTestInitHelper.initializeTestWorkManager(context)
            val store = EncryptedStore(context, VaultCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
            val origin = server.url("/").toString().trimEnd('/')
            store.saveCredentials(Credentials(origin, "va1_access", "2099-01-01T00:00:00Z", "vr1_refresh", "2099-02-01T00:00:00Z", "owner", "device"))
            val bytes = "persist upload progress".toByteArray()
            val spool = store.createSpool("worker-file-spool", ByteArrayInputStream(bytes))
            val draft = Draft("worker-file", "stable-worker-key", origin, "owner", spoolName = "worker-file-spool", displayName = "file.bin", contentType = "application/octet-stream", sizeBytes = spool.sizeBytes, sha256 = spool.sha256, state = DraftState.QUEUED)
            store.saveDraft(draft)
            UploadWorkerDependencies.storeFactory = { store }
            UploadWorkerDependencies.apiFactory = { VaultApi(store) }
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"uploadId":"upload-worker-123"}"""))
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))

            val input = workDataOf(UploadWorker.KEY_DRAFT_ID to draft.id)
            val firstWorker = TestListenableWorkerBuilder<UploadWorker>(context).setInputData(input).build()
            val firstResult = firstWorker.doWork()

            assertTrue(firstResult is ListenableWorker.Result.Retry)
            val afterRetryableFailure = store.draft(draft.id)!!
            assertEquals(DraftState.RETRYING, afterRetryableFailure.state)
            assertEquals("upload-worker-123", afterRetryableFailure.uploadId)
            assertEquals("stable-worker-key", afterRetryableFailure.idempotencyKey)
            assertEquals(bytes.size.toLong(), afterRetryableFailure.bytesSent)

            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val secondWorker = TestListenableWorkerBuilder<UploadWorker>(context)
                .setInputData(input)
                .setRunAttemptCount(1)
                .build()
            val secondResult = secondWorker.doWork()

            assertTrue(secondResult is ListenableWorker.Result.Success)
            val completed = store.draft(draft.id)!!
            assertEquals(DraftState.AVAILABLE, completed.state)
            assertEquals("upload-worker-123", completed.uploadId)
            assertEquals(bytes.size.toLong(), completed.bytesSent)
            assertTrue(!store.hasSpool(completed.spoolName))
            val requests = (1..4).map { server.takeRequest() }
            assertEquals(1, requests.count { it.path == "/api/v1/uploads/initiate" })
            assertEquals(2, requests.count { it.path == "/api/v1/uploads/upload-worker-123/content" })
            assertEquals(1, requests.count { it.path == "/api/v1/uploads/upload-worker-123/complete" })
            requests.forEach { assertEquals("stable-worker-key", it.getHeader("Idempotency-Key")) }
        } finally { server.shutdown() }
    }

    @Test fun networkDropDuringUploadSetsStateToRetryingAndReturnsRetry() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            WorkManagerTestInitHelper.initializeTestWorkManager(context)
            val store = EncryptedStore(context, VaultCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
            val origin = server.url("/").toString().trimEnd('/')
            store.saveCredentials(Credentials(origin, "va1_access", "2099-01-01T00:00:00Z", "vr1_refresh", "2099-02-01T00:00:00Z", "owner", "device"))
            val bytes = "network drop test bytes".toByteArray()
            val spool = store.createSpool("worker-io-spool", ByteArrayInputStream(bytes))
            val draft = Draft("worker-io-file", "stable-io-key", origin, "owner", spoolName = "worker-io-spool", displayName = "io-file.bin", contentType = "application/octet-stream", sizeBytes = spool.sizeBytes, sha256 = spool.sha256, state = DraftState.QUEUED)
            store.saveDraft(draft)
            UploadWorkerDependencies.storeFactory = { store }
            UploadWorkerDependencies.apiFactory = { VaultApi(store) }

            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"uploadId":"upload-io-123"}"""))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

            val input = workDataOf(UploadWorker.KEY_DRAFT_ID to draft.id)
            val worker = TestListenableWorkerBuilder<UploadWorker>(context).setInputData(input).build()
            val result = worker.doWork()

            assertTrue(result is ListenableWorker.Result.Retry)
            val persisted = store.draft(draft.id)!!
            assertEquals(DraftState.RETRYING, persisted.state)
            assertEquals("Waiting for a network connection.", persisted.safeError)
            assertEquals("upload-io-123", persisted.uploadId)
        } finally { server.shutdown() }
    }
}
