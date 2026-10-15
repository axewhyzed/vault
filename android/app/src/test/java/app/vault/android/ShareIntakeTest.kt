package app.vault.android

import org.junit.Assert.*
import org.junit.Test

class ShareIntakeTest {
    @Test fun acceptsPlainTextAndOneContentUri() {
        assertEquals("hello", SharePayload.from("android.intent.action.SEND", "text/plain", "hello", emptyList()).text)
        assertEquals("content://provider/document/1", SharePayload.from("android.intent.action.SEND", "application/pdf", null, listOf("content://provider/document/1")).contentUri)
        assertEquals("content://provider/document/1", SharePayload.from("android.intent.action.SEND", "application/pdf", null, listOf("content://provider/document/1", "content://provider/document/1")).contentUri)
    }

    @Test fun rejectsMultipleFilesFilePathAndUnsupportedTextMime() {
        assertThrows(IllegalArgumentException::class.java) { SharePayload.from("android.intent.action.SEND", "text/plain", null, listOf("content://provider/1", "content://provider/2")) }
        assertThrows(IllegalArgumentException::class.java) { SharePayload.from("android.intent.action.SEND", "text/plain", null, listOf("file:///tmp/a.txt")) }
        assertThrows(IllegalArgumentException::class.java) { SharePayload.from("android.intent.action.SEND", "text/html", "hello", emptyList()) }
    }
}
