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

    @Test fun validatesUtf8TextSizeAndFileProviderMetadataBoundaries() {
        assertTrue(isShareTextWithinLimit("é".repeat(131_072)))
        assertFalse(isShareTextWithinLimit("é".repeat(131_073)))
        validateSharedFileSize(null)
        validateSharedFileSize(Spooler.MAX_FILE_BYTES)
        assertThrows(IllegalArgumentException::class.java) { validateSharedFileSize(Spooler.MAX_FILE_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { validateSharedFileSize(-1) }
        assertEquals("report.pdf", safeSharedFileName("C:\\private\\report.pdf"))
        assertEquals("shared-file", safeSharedFileName("/\\\u0000"))
        assertEquals("application/octet-stream", safeSharedMimeType("text/html; charset=utf-8"))
        assertEquals("image/png", safeSharedMimeType("image/png"))
    }
}
