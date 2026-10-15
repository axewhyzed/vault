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
}
