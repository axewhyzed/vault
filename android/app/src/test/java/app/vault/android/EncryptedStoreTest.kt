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

    @Test fun defaultDownloadUriIsEncryptedAndPersisted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = EncryptedStore(context, VaultCrypto(testKey))

        assertNull(store.defaultDownloadUri())
        assertNull(store.defaultDownloadFolderUri)

        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FVault"
        store.saveDefaultDownloadUri(uri)

        // Verify retrieval
        assertEquals(uri, store.defaultDownloadUri())
        assertEquals(uri, store.defaultDownloadFolderUri)

        // Verify disk file is encrypted and does not leak plaintext URI
        val settingsFile = java.io.File(context.filesDir, "vault-private/settings.bin")
        assertTrue(settingsFile.exists())
        val rawBytes = settingsFile.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(rawBytes.contains("primary%3ADownload"))
        assertFalse(rawBytes.contains("content://"))

        // Update to a new URI
        val updatedUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
        store.saveDefaultDownloadUri(updatedUri)
        assertEquals(updatedUri, store.defaultDownloadUri())

        // Clear URI by saving null
        store.saveDefaultDownloadUri(null)
        assertNull(store.defaultDownloadUri())
        assertNull(store.defaultDownloadFolderUri)
    }

    @Test fun cacheOperationsClearSpoolsAndCachedInbox() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = EncryptedStore(context, VaultCrypto(testKey))

        // Create temporary spools
        val data1 = "first spooled temporary chunk".toByteArray()
        val data2 = "second spooled temporary chunk".toByteArray()
        store.createSpool("spool-1", ByteArrayInputStream(data1))
        store.createSpool("spool-2", ByteArrayInputStream(data2))

        val currentSpoolBytes = store.spoolSize()
        assertTrue(currentSpoolBytes >= (data1.size + data2.size))
        assertTrue(store.hasSpool("spool-1"))
        assertTrue(store.hasSpool("spool-2"))

        // Save inbox cache
        val summary = InboxSummary(
            id = "item-temp",
            type = "text",
            state = "available",
            displayName = "temp-note",
            sizeBytes = 100,
            createdAt = "2026-10-17T12:00:00Z",
            availableAt = "2026-10-17T12:00:01Z",
            expiresAt = "2026-10-24T12:00:00Z",
            version = 1,
            sourceDevice = InboxSourceDevice("dev-temp", "Laptop", "web"),
        )
        store.saveInboxCache(CachedInbox("owner-key", 1729166400000L, listOf(summary), null, null))
        assertNotNull(store.cachedInbox())

        // Clear local cache
        store.clearLocalCache()

        // Verify all spools and inbox cache are removed
        assertEquals(0L, store.spoolSize())
        assertFalse(store.hasSpool("spool-1"))
        assertFalse(store.hasSpool("spool-2"))
        assertNull(store.cachedInbox())
    }

    @Test fun expiredRefreshTokenReturnsNullAndClearsCredentials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = EncryptedStore(context, VaultCrypto(testKey))
        // Set refresh expiry in the past
        store.saveCredentials(Credentials("https://vault.example", "va1_access", "2020-01-01T00:00:00Z", "vr1_token", "2020-01-14T00:00:00Z", "user", "device"))
        assertNull(store.credentials())
        assertFalse(java.io.File(context.filesDir, "vault-private/credentials.bin").exists())
    }

    @Test fun authRevisionIncrementsOnSaveAndClearCredentials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = EncryptedStore(context, VaultCrypto(testKey))
        val initialEpoch = EncryptedStore.authRevision.value
        store.saveCredentials(Credentials("https://vault.example", "va1_access", "2099-01-01T00:00:00Z", "vr1_token", "2099-02-01T00:00:00Z", "user", "device"))
        assertTrue(EncryptedStore.authRevision.value > initialEpoch)
        val afterSave = EncryptedStore.authRevision.value
        store.clearCredentials()
        assertTrue(EncryptedStore.authRevision.value > afterSave)
    }
}
