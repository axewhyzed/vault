package app.vault.android

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EncryptedStoreTest {
    @Test fun credentialsAndDraftAreEncryptedAndReadable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = EncryptedStore(context, VaultCrypto(testKey))
        val token = "vr1_sensitive_refresh_token"
        store.saveCredentials(Credentials("https://vault.example", "va1_access", "2099-01-01T00:00:00Z", token, "2099-02-01T00:00:00Z", "user", "device"))
        assertFalse(java.io.File(context.filesDir, "vault-private/credentials.bin").readBytes().toString(Charsets.ISO_8859_1).contains(token))
        assertEquals(token, store.credentials()?.refreshToken)
        val draft = Draft("draft-test", "intent-test", "https://vault.example", "user", text = "secret draft", displayName = "Snippet", contentType = "text/plain", sizeBytes = 12)
        store.saveDraft(draft)
        assertEquals(draft, store.draft(draft.id))
        store.completeDraft(draft)
        val available = store.draft(draft.id)!!
        assertEquals(DraftState.AVAILABLE, available.state)
        assertNull(available.text)
        assertNull(available.spoolName)
        val bytes = "spooled content".toByteArray()
        val spool = store.createSpool("blob-test", ByteArrayInputStream(bytes))
        val encryptedSpool = java.io.File(context.filesDir, "vault-private/spool/blob-test.enc").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(encryptedSpool.contains("spooled content"))
        val fileDraft = Draft("draft-file", "intent-file", "https://vault.example", "user", spoolName = "blob-test", displayName = "file.txt", contentType = "text/plain", sizeBytes = spool.sizeBytes, sha256 = spool.sha256)
        store.saveDraft(fileDraft)
        assertTrue(store.hasSpool(fileDraft.spoolName))
        store.completeDraft(fileDraft)
        assertFalse(store.hasSpool(fileDraft.spoolName))
        assertNull(store.draft(fileDraft.id)!!.spoolName)
        val failedDraft = fileDraft.copy(id = "failed-file", spoolName = "blob-test", state = DraftState.FAILED, text = "")
        store.createSpool("blob-test", ByteArrayInputStream(bytes))
        store.saveDraft(failedDraft)
        assertTrue(store.hasSpool(failedDraft.spoolName))
        store.deleteDraft(failedDraft.id)
        assertNull(store.draft(failedDraft.id))
        assertFalse(store.hasSpool(failedDraft.spoolName))
        store.clearCredentials(); store.deleteDraft(draft.id)
        store.deleteDraft(fileDraft.id)
    }

    @Test fun cachedInboxIsEncryptedAndPersisted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = EncryptedStore(context, VaultCrypto(testKey))

        val summary = InboxSummary(
            id = "item-123",
            type = "file",
            state = "available",
            displayName = "confidential-report.pdf",
            sizeBytes = 2048,
            createdAt = "2026-10-17T12:00:00Z",
            availableAt = "2026-10-17T12:00:01Z",
            expiresAt = "2026-10-24T12:00:00Z",
            version = 1,
            sourceDevice = InboxSourceDevice("dev-1", "Laptop", "web"),
            ownerOrigin = "https://vault.example",
            ownerUserId = "user-1",
        )
        val quota = InboxQuota(activeBytes = 2048, maxActiveBytes = 10000, activeItems = 1, maxActiveItems = 100)
        val cache = CachedInbox(
            ownerKey = "https://vault.example|user-1",
            cachedAtEpochMillis = 1729166400000L,
            items = listOf(summary),
            nextCursor = "cursor-abc",
            quota = quota,
        )

        store.saveInboxCache(cache)

        // Verify disk file is encrypted
        val rawBytes = java.io.File(context.filesDir, "vault-private/cache/inbox_cache.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(rawBytes.contains("confidential-report.pdf"))
        assertFalse(rawBytes.contains("item-123"))

        // Verify decrypted retrieval
        val loaded = store.cachedInbox()
        assertNotNull(loaded)
        assertEquals(cache.ownerKey, loaded!!.ownerKey)
        assertEquals(cache.cachedAtEpochMillis, loaded.cachedAtEpochMillis)
        assertEquals(cache.nextCursor, loaded.nextCursor)
        assertEquals(cache.quota?.activeBytes, loaded.quota?.activeBytes)
        assertEquals(1, loaded.items.size)
        assertEquals("confidential-report.pdf", loaded.items[0].displayName)

        // Clear cache
        store.clearInboxCache()
        assertNull(store.cachedInbox())
    }
}
