package app.vault.android

import java.net.URI

data class SharePayload(val text: String? = null, val contentUri: String? = null) {
    companion object {
        fun from(action: String?, mime: String?, text: CharSequence?, streamUris: List<String>): SharePayload {
            require(action == "android.intent.action.SEND") { "Share one item at a time." }
            val uniqueStreams = streamUris.distinct()
            require(uniqueStreams.size <= 1) { "Share one file at a time." }
            val body = text?.toString()?.takeIf { it.isNotBlank() }
            val uri = uniqueStreams.singleOrNull()
            require(body != null || uri != null) { "Nothing to send." }
            if (uri != null) require(URI(uri).scheme == "content") { "Only readable content links are accepted." }
            require(mime == null || uri != null || mime == "text/plain") { "Only text/plain shares are supported without a file." }
            return SharePayload(body, uri)
        }
    }
}
