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
}
