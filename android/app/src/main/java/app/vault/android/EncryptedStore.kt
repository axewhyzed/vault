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
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Credentials(val origin: String, val accessToken: String, val accessExpiry: String, val refreshToken: String, val refreshExpiry: String, val userId: String, val deviceId: String)

data class CachedInbox(
    val ownerKey: String,
    val cachedAtEpochMillis: Long,
    val items: List<InboxSummary>,
    val nextCursor: String?,
    val quota: InboxQuota?
)

class EncryptedStore(context: Context, private val crypto: VaultCrypto = VaultCrypto()) {
    val appCacheDir: File = context.cacheDir
    private val root = File(context.filesDir, "vault-private").apply { mkdirs() }
    private val drafts = File(root, "drafts").apply { mkdirs() }
    private val blobs = File(root, "spool").apply { mkdirs() }
    private val cacheDir = File(root, "cache").apply { mkdirs() }
    private val credentialFile = File(root, "credentials.bin")
    private val inboxCacheFile = File(cacheDir, "inbox_cache.bin")
    private val settingsFile = File(root, "settings.bin")

    @Synchronized fun saveDefaultDownloadUri(uri: String?) {
        val currentJson = try {
            if (settingsFile.exists()) JSONObject(crypto.decrypt(settingsFile.readBytes()).toString(Charsets.UTF_8)) else JSONObject()
        } catch (_: Exception) { JSONObject() }
        if (uri.isNullOrBlank()) {
            currentJson.remove("defaultDownloadUri")
        } else {
            currentJson.put("defaultDownloadUri", uri)
        }
        writeEncrypted(settingsFile, currentJson.toString().toByteArray())
    }

    @Synchronized fun defaultDownloadUri(): String? {
        if (!settingsFile.exists()) return null
        return try {
            val json = JSONObject(crypto.decrypt(settingsFile.readBytes()).toString(Charsets.UTF_8))
            if (json.has("defaultDownloadUri") && !json.isNull("defaultDownloadUri")) {
                json.getString("defaultDownloadUri").takeIf { it.isNotBlank() }
            } else null
        } catch (_: Exception) { null }
    }

    val defaultDownloadFolderUri: String? get() = defaultDownloadUri()

    @Synchronized fun saveCredentials(value: Credentials) {
        writeEncrypted(credentialFile, JSONObject().apply {
            put("origin", value.origin); put("accessToken", value.accessToken); put("accessExpiry", value.accessExpiry)
            put("refreshToken", value.refreshToken); put("refreshExpiry", value.refreshExpiry); put("userId", value.userId); put("deviceId", value.deviceId)
        }.toString().toByteArray())
        notifyAuthChanged()
    }

    @Synchronized fun credentials(): Credentials? {
        if (!credentialFile.exists()) return null
        return try { JSONObject(crypto.decrypt(credentialFile.readBytes()).toString(Charsets.UTF_8)).let {
            val c = Credentials(it.getString("origin"), it.getString("accessToken"), it.getString("accessExpiry"), it.getString("refreshToken"), it.getString("refreshExpiry"), it.getString("userId"), it.getString("deviceId"))
            if (isRefreshExpired(c.refreshExpiry)) {
                clearCredentials()
                null
            } else {
                c
            }
        } } catch (_: Exception) { null }
    }

    @Synchronized fun clearCredentials() {
        credentialFile.delete()
        clearInboxCache()
        notifyAuthChanged()
    }

    private fun isRefreshExpired(expiry: String): Boolean = runCatching {
        val expiresAt = Instant.parse(expiry)
        !Instant.now().isBefore(expiresAt)
    }.getOrDefault(false)
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

    @Synchronized fun saveInboxCache(value: CachedInbox) = writeEncrypted(inboxCacheFile, JSONObject().apply {
        put("ownerKey", value.ownerKey)
        put("cachedAtEpochMillis", value.cachedAtEpochMillis)
        put("nextCursor", value.nextCursor ?: JSONObject.NULL)
        if (value.quota != null) {
            put("quota", JSONObject().apply {
                put("activeBytes", value.quota.activeBytes)
                put("maxActiveBytes", value.quota.maxActiveBytes)
                put("activeItems", value.quota.activeItems)
                put("maxActiveItems", value.quota.maxActiveItems)
            })
        }
        val array = JSONArray()
        value.items.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("type", item.type)
                put("state", item.state)
                put("displayName", item.displayName)
                put("sizeBytes", item.sizeBytes)
                put("createdAt", item.createdAt)
                put("availableAt", item.availableAt)
                put("expiresAt", item.expiresAt)
                put("version", item.version)
                put("device", JSONObject().apply {
                    put("id", item.sourceDevice.id)
                    put("name", item.sourceDevice.name)
                    put("platform", item.sourceDevice.platform)
                })
                put("ownerOrigin", item.ownerOrigin)
                put("ownerUserId", item.ownerUserId)
            })
        }
        put("items", array)
    }.toString().toByteArray())

    @Synchronized fun cachedInbox(): CachedInbox? {
        if (!inboxCacheFile.exists()) return null
        return try {
            val json = JSONObject(crypto.decrypt(inboxCacheFile.readBytes()).toString(Charsets.UTF_8))
            val ownerKey = json.getString("ownerKey")
            val cachedAt = json.getLong("cachedAtEpochMillis")
            val nextCursor = if (json.isNull("nextCursor")) null else json.optString("nextCursor").takeIf { it.isNotBlank() }
            val quota = if (json.has("quota") && !json.isNull("quota")) {
                val q = json.getJSONObject("quota")
                InboxQuota(
                    q.getLong("activeBytes"),
                    q.getLong("maxActiveBytes"),
                    q.getLong("activeItems"),
                    q.getInt("maxActiveItems")
                )
            } else null
            val array = json.optJSONArray("items") ?: JSONArray()
            val items = (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val dev = obj.getJSONObject("device")
                InboxSummary(
                    id = obj.getString("id"),
                    type = obj.getString("type"),
                    state = obj.getString("state"),
                    displayName = obj.getString("displayName"),
                    sizeBytes = obj.getLong("sizeBytes"),
                    createdAt = obj.getString("createdAt"),
                    availableAt = obj.getString("availableAt"),
                    expiresAt = obj.getString("expiresAt"),
                    version = obj.getInt("version"),
                    sourceDevice = InboxSourceDevice(
                        id = dev.getString("id"),
                        name = dev.getString("name"),
                        platform = dev.getString("platform")
                    ),
                    ownerOrigin = obj.optString("ownerOrigin", ""),
                    ownerUserId = obj.optString("ownerUserId", "")
                )
            }
            CachedInbox(ownerKey, cachedAt, items, nextCursor, quota)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized fun clearInboxCache() {
        inboxCacheFile.delete()
    }

    @Synchronized fun spoolSize(): Long =
        blobs.listFiles()?.sumOf { it.length() } ?: 0L

    @Synchronized fun clearLocalCache() {
        blobs.listFiles()?.forEach { it.delete() }
        clearInboxCache()
    }

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

    companion object {
        private val _authRevision = MutableStateFlow(0L)
        val authRevision: StateFlow<Long> = _authRevision.asStateFlow()

        fun notifyAuthChanged() {
            _authRevision.update { it + 1 }
        }
    }
}
