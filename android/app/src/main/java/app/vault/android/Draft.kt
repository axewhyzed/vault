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
) {
    fun json() = JSONObject().apply {
        put("id", id); put("idempotencyKey", idempotencyKey); put("serverOrigin", serverOrigin); put("ownerUserId", ownerUserId); put("retentionDays", retentionDays)
        put("text", text); put("spoolName", spoolName); put("displayName", displayName); put("contentType", contentType)
        put("sizeBytes", sizeBytes); put("bytesSent", bytesSent); put("sha256", sha256); put("uploadId", uploadId); put("state", state.name); put("safeError", safeError)
        put("createdAtEpochMillis", createdAtEpochMillis)
        put("contentKind", contentKind.name)
    }
    companion object {
        fun from(json: JSONObject) = Draft(json.getString("id"), json.getString("idempotencyKey"), json.getString("serverOrigin"), json.getString("ownerUserId"), json.optInt("retentionDays", 7),
            json.optString("text").takeUnless { json.isNull("text") }, json.optString("spoolName").takeUnless { json.isNull("spoolName") },
            json.getString("displayName"), json.getString("contentType"), json.getLong("sizeBytes"), json.optLong("bytesSent", 0),
            json.optString("sha256").takeUnless { json.isNull("sha256") }, json.optString("uploadId").takeUnless { json.isNull("uploadId") },
            DraftState.valueOf(json.getString("state")), json.optString("safeError").takeUnless { json.isNull("safeError") },
            json.optLong("createdAtEpochMillis", 0L),
            runCatching { DraftContentKind.valueOf(json.optString("contentKind")) }.getOrDefault(if (json.isNull("text")) DraftContentKind.FILE else DraftContentKind.AUTO))
    }
}
