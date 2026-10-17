package app.vault.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DraftQueueTest {
    private fun store(): EncryptedStore {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return EncryptedStore(ApplicationProvider.getApplicationContext<Context>(), VaultCrypto(key))
    }

    @Test fun prepareDoesNotEnqueueAndSendPreservesIntentKeyAndRetention() {
        val store = store()
        var workStarts = 0
        val queue = DraftQueue(store, enqueue = { id ->
            workStarts++
            val persisted = store.draft(id)!!
            assertEquals(DraftState.QUEUED, persisted.state)
        }, cancelWork = {})
        val source = Draft("prepared-text", "stable-intent-key", "https://vault.example", "owner", text = "note", displayName = "Note", contentType = "text/plain", sizeBytes = 4)

        val prepared = queue.prepare(source)
        assertEquals(DraftState.PREPARED, prepared.state)
        assertEquals(0, workStarts)

        val sent = queue.send(prepared.id, 3)
        assertEquals(1, workStarts)
        assertEquals("stable-intent-key", sent.idempotencyKey)
        assertEquals(3, sent.retentionDays)
        assertEquals("stable-intent-key", store.draft(sent.id)?.idempotencyKey)
        assertEquals(3, store.draft(sent.id)?.retentionDays)
        store.deleteDraft(sent.id)
    }

    @Test fun cancelRemovesPreparedDraftAndPrivateSpool() {
        val store = store()
        val bytes = "private bytes".toByteArray()
        val spool = store.createSpool("cancel-spool", ByteArrayInputStream(bytes))
        val source = Draft("prepared-file", "stable-file-key", "https://vault.example", "owner", spoolName = "cancel-spool", displayName = "file.bin", contentType = "application/octet-stream", sizeBytes = spool.sizeBytes, sha256 = spool.sha256)
        val prepared = DraftQueue(store, enqueue = {}, cancelWork = {}).prepare(source)
        var canceledId: String? = null
        DraftQueue(store, enqueue = {}, cancelWork = { canceledId = it }).cancel(prepared.id)
        assertEquals(prepared.id, canceledId)
        assertNull(store.draft(prepared.id))
        assertFalse(store.hasSpool("cancel-spool"))
    }

    @Test fun recentDraftsAreNewestFirstAndScopedToTheCurrentOwner() {
        val store = store()
        val drafts = listOf(
            Draft("owner-a-old", "key-a-old", "https://vault.example", "owner-a", text = "old", displayName = "old.txt", contentType = "text/plain", sizeBytes = 3, createdAtEpochMillis = 100),
            Draft("owner-b-new", "key-b-new", "https://vault.example", "owner-b", text = "private", displayName = "private.txt", contentType = "text/plain", sizeBytes = 7, createdAtEpochMillis = 300),
            Draft("owner-a-new", "key-a-new", "https://VAULT.example", "owner-a", text = "new", displayName = "new.txt", contentType = "text/plain", sizeBytes = 3, createdAtEpochMillis = 200),
        )
        drafts.forEach(store::saveDraft)

        assertEquals(listOf("owner-a-new", "owner-a-old"), store.draftsFor("https://vault.example", "owner-a").map { it.id })
        assertTrue(store.draftsFor(null, "owner-a").isEmpty())
        drafts.forEach { store.deleteDraft(it.id) }
    }

    @Test fun uploadProgressDoesNotDiscardTheServerUploadId() {
        val store = store()
        val initial = Draft("progress-file", "stable-intent-key", "https://vault.example", "owner", spoolName = "progress-spool", displayName = "file.bin", contentType = "application/octet-stream", sizeBytes = 100, state = DraftState.UPLOADING)
        val progress = UploadDraftProgress(store, initial)
        progress.saveState(initial.copy(uploadId = "upload-123"))
        progress.saveBytesSent(64)

        val persisted = store.draft(initial.id)!!
        assertEquals("upload-123", persisted.uploadId)
        assertEquals("stable-intent-key", persisted.idempotencyKey)
        assertEquals(64L, persisted.bytesSent)
        assertEquals("upload-123", progress.current().uploadId)
        store.deleteDraft(initial.id)
    }

    @Test fun sendWithRetentionHoursValidatesAndPersists() {
        val store = store()
        val queue = DraftQueue(store, enqueue = {}, cancelWork = {})
        val source = Draft("hourly-draft", "key-hours", "https://vault.example", "owner", text = "hourly note", displayName = "Note", contentType = "text/plain", sizeBytes = 11)

        val prepared = queue.prepare(source)
        val sent = queue.send(prepared.id, retentionDays = 1, retentionHours = 4)
        assertEquals(4, sent.retentionHours)
        assertEquals(4, store.draft(sent.id)?.retentionHours)

        assertThrows(IllegalArgumentException::class.java) {
            queue.send(prepared.id, retentionDays = 1, retentionHours = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            queue.send(prepared.id, retentionDays = 1, retentionHours = 169)
        }
        store.deleteDraft(sent.id)
    }
}
