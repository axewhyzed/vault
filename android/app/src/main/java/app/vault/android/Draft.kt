package app.vault.android

import org.json.JSONObject

enum class DraftState { PREPARED, QUEUED, UPLOADING, RETRYING, AVAILABLE, FAILED }
enum class DraftContentKind { AUTO, TEXT, URL, FILE }

data class Draft(
    val id: String,
    val idempotencyKey: String,
    val serverOrigin: String,
    val ownerUserId: String,
    val retentionDays: Int = 7,
    val text: String? = null,
    val spoolName: String? = null,
    val displayName: String,
    val contentType: String,
    val sizeBytes: Long,
    val bytesSent: Long = 0,
    val sha256: String? = null,
    val uploadId: String? = null,
    val state: DraftState = DraftState.QUEUED,
    val safeError: String? = null,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val contentKind: DraftContentKind = if (text == null) DraftContentKind.FILE else DraftContentKind.AUTO,
    val retentionHours: Int? = null,
    val version: Int = 1,
) {
    fun json() = JSONObject().apply {
        put("version", version)
        put("id", id); put("idempotencyKey", idempotencyKey); put("serverOrigin", serverOrigin); put("ownerUserId", ownerUserId); put("retentionDays", retentionDays)
        put("text", text); put("spoolName", spoolName); put("displayName", displayName); put("contentType", contentType)
        put("sizeBytes", sizeBytes); put("bytesSent", bytesSent); put("sha256", sha256); put("uploadId", uploadId); put("state", state.name); put("safeError", safeError)
        put("createdAtEpochMillis", createdAtEpochMillis)
        put("contentKind", contentKind.name)
        if (retentionHours != null) put("retentionHours", retentionHours)
    }
    companion object {
        fun from(json: JSONObject) = Draft(
            id = json.getString("id"),
            idempotencyKey = json.getString("idempotencyKey"),
            serverOrigin = json.getString("serverOrigin"),
            ownerUserId = json.getString("ownerUserId"),
            retentionDays = json.optInt("retentionDays", 7),
            text = json.optString("text").takeUnless { json.isNull("text") },
            spoolName = json.optString("spoolName").takeUnless { json.isNull("spoolName") },
            displayName = json.optString("displayName", "shared-file"),
            contentType = json.optString("contentType", "application/octet-stream"),
            sizeBytes = json.optLong("sizeBytes", 0L),
            bytesSent = json.optLong("bytesSent", 0L),
            sha256 = json.optString("sha256").takeUnless { json.isNull("sha256") },
            uploadId = json.optString("uploadId").takeUnless { json.isNull("uploadId") },
            state = runCatching { DraftState.valueOf(json.optString("state")) }.getOrDefault(DraftState.QUEUED),
            safeError = json.optString("safeError").takeUnless { json.isNull("safeError") },
            createdAtEpochMillis = json.optLong("createdAtEpochMillis", 0L),
            contentKind = runCatching { DraftContentKind.valueOf(json.optString("contentKind")) }.getOrDefault(if (json.isNull("text")) DraftContentKind.FILE else DraftContentKind.AUTO),
            retentionHours = if (json.has("retentionHours") && !json.isNull("retentionHours")) json.getInt("retentionHours") else null,
            version = json.optInt("version", 1),
        )
    }
}
