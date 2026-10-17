package app.vault.android

internal fun shouldHandleLaunchIntent(hasSavedInstanceState: Boolean): Boolean = !hasSavedInstanceState

internal fun shouldApplyDemoMode(isDebugBuild: Boolean, demoRequested: Boolean): Boolean = isDebugBuild && demoRequested

internal const val MAX_SHARE_TEXT_BYTES = 262_144L

internal fun isShareTextWithinLimit(value: String): Boolean = value.toByteArray(Charsets.UTF_8).size.toLong() <= MAX_SHARE_TEXT_BYTES

internal fun validateSharedFileSize(sizeBytes: Long?) {
    require(sizeBytes == null || sizeBytes in 0..Spooler.MAX_FILE_BYTES) { "Files may not exceed the 100 MB limit." }
}

internal fun safeSharedFileName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
    .filterNot { Character.isISOControl(it) || it == '/' || it == '\\' }.take(200).ifBlank { "shared-file" }

internal fun safeSharedMimeType(value: String?): String = value?.takeIf {
    it.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))
} ?: "application/octet-stream"

internal var isNetworkConnected: (android.content.Context) -> Boolean = { context ->
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
    val active = cm?.activeNetwork
    val caps = if (active != null) cm.getNetworkCapabilities(active) else null
    caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

