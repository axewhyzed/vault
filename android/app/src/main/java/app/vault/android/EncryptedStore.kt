package app.vault.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class Credentials(val origin: String, val accessToken: String, val accessExpiry: String, val refreshToken: String, val refreshExpiry: String, val userId: String, val deviceId: String)

class EncryptedStore(context: Context, private val crypto: VaultCrypto = VaultCrypto()) {
    private val root = File(context.filesDir, "vault-private").apply { mkdirs() }
    private val drafts = File(root, "drafts").apply { mkdirs() }
    private val blobs = File(root, "spool").apply { mkdirs() }
    private val credentialFile = File(root, "credentials.bin")

    @Synchronized fun saveCredentials(value: Credentials) = writeEncrypted(credentialFile, JSONObject().apply {
        put("origin", value.origin); put("accessToken", value.accessToken); put("accessExpiry", value.accessExpiry)
        put("refreshToken", value.refreshToken); put("refreshExpiry", value.refreshExpiry); put("userId", value.userId); put("deviceId", value.deviceId)
    }.toString().toByteArray())
    @Synchronized fun credentials(): Credentials? {
        if (!credentialFile.exists()) return null
        return try { JSONObject(crypto.decrypt(credentialFile.readBytes()).toString(Charsets.UTF_8)).let {
            Credentials(it.getString("origin"), it.getString("accessToken"), it.getString("accessExpiry"), it.getString("refreshToken"), it.getString("refreshExpiry"), it.getString("userId"), it.getString("deviceId"))
        } } catch (_: Exception) { null }
    }
    @Synchronized fun clearCredentials() { credentialFile.delete() }
    fun saveDraft(draft: Draft) = writeEncrypted(draftFile(draft.id), draft.json().toString().toByteArray())
    fun draft(id: String): Draft? = try { Draft.from(JSONObject(crypto.decrypt(draftFile(id).readBytes()).toString(Charsets.UTF_8))) } catch (_: Exception) { null }
    @Synchronized fun allDrafts(): List<Draft> {
        val all = drafts.listFiles()?.mapNotNull { file -> draft(file.nameWithoutExtension) }
            ?.sortedWith(compareByDescending<Draft> { it.createdAtEpochMillis }.thenByDescending { it.id }) ?: emptyList()
        val retained = all.mapNotNull { it.spoolName }.toSet()
        blobs.listFiles()?.filter { it.name.endsWith(".enc") && it.name.removeSuffix(".enc") !in retained }?.forEach(File::delete)
        return all
    }
    @Synchronized fun draftsFor(origin: String?, userId: String?): List<Draft> {
        if (origin.isNullOrBlank() || userId.isNullOrBlank()) return emptyList()
        return allDrafts().filter { it.ownerUserId == userId && it.serverOrigin.equals(origin, ignoreCase = true) }
    }
    fun deleteDraft(id: String) { val draft = draft(id); draftFile(id).delete(); draft?.spoolName?.let { spoolFile(it).delete() } }
    fun completeDraft(draft: Draft) {
        draft.spoolName?.let { spoolFile(it).delete() }
        saveDraft(draft.copy(text = null, spoolName = null, bytesSent = draft.sizeBytes, state = DraftState.AVAILABLE, safeError = null))
    }
    fun createSpool(name: String, source: InputStream): SpoolResult {
        val target = spoolFile(name)
        return try {
            FileOutputStream(target).use { out -> crypto.encrypting(out).use { encrypted -> Spooler.copyBounded(source, encrypted) } }
        } catch (e: Exception) { target.delete(); throw e }
    }
    fun spool(name: String): InputStream = crypto.decrypting(FileInputStream(spoolFile(name)))
    fun hasSpool(name: String?) = name != null && spoolFile(name).isFile
    fun deleteSpool(name: String?) { name?.let { spoolFile(it).delete() } }
    private fun writeEncrypted(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(crypto.encrypt(bytes))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
    private fun draftFile(id: String) = File(drafts, "$id.draft")
    private fun spoolFile(name: String) = File(blobs, "$name.enc")
}
