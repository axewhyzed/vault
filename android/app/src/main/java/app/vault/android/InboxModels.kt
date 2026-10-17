package app.vault.android

data class InboxSourceDevice(val id: String, val name: String, val platform: String)

data class DeviceItem(
    val id: String,
    val name: String,
    val platform: String,
    val isCurrent: Boolean,
    val createdAt: String,
)

data class InboxQuota(val activeBytes: Long, val maxActiveBytes: Long, val activeItems: Long, val maxActiveItems: Int)

data class InboxPage(
    val items: List<InboxSummary>,
    val nextCursor: String?,
    val quota: InboxQuota,
)

data class InboxSummary(
    val id: String,
    val type: String,
    val state: String,
    val displayName: String,
    val sizeBytes: Long,
    val createdAt: String,
    val availableAt: String,
    val expiresAt: String,
    val version: Int,
    val sourceDevice: InboxSourceDevice,
    val textContent: String? = null,
    internal val ownerOrigin: String = "",
    internal val ownerUserId: String = "",
)

data class InboxDetail(
    val id: String,
    val type: String,
    val state: String,
    val displayName: String,
    val sizeBytes: Long,
    val createdAt: String,
    val availableAt: String,
    val expiresAt: String,
    val version: Int,
    val sourceDevice: InboxSourceDevice,
    val text: String?,
    val contentType: String,
    val downloadUrl: String?,
    val sha256: String?,
    internal val ownerOrigin: String = "",
    internal val ownerUserId: String = "",
)

internal fun InboxSummary.asDetail(
    text: String?,
    contentType: String,
    downloadUrl: String?,
    sha256: String?,
) = InboxDetail(id, type, state, displayName, sizeBytes, createdAt, availableAt, expiresAt, version, sourceDevice,
    text, contentType, downloadUrl, sha256, ownerOrigin, ownerUserId)
