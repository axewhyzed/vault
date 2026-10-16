package app.vault.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class InboxPresentationTest {
    @Test fun allowsOnlyAbsoluteHttpLinksWithHostAndNoUserInfoOrWhitespace() {
        assertTrue(isSafeExternalHttpUrl("https://example.com/path?q=one"))
        assertTrue(isSafeExternalHttpUrl("HTTP://example.com"))
        assertFalse(isSafeExternalHttpUrl("javascript:alert(1)"))
        assertFalse(isSafeExternalHttpUrl("https:///missing-host"))
        assertFalse(isSafeExternalHttpUrl("https://user@example.com/path"))
        assertFalse(isSafeExternalHttpUrl("https://example.com/a b"))
        assertFalse(isSafeExternalHttpUrl("https://example.com/\n"))
    }

    @Test fun checksExpiryAndTreatsInvalidExpiryAsExpired() {
        val now = 1_800_000_000_000L
        assertTrue(isInboxItemExpired(Instant.ofEpochMilli(now - 1).toString(), now))
        assertFalse(isInboxItemExpired(Instant.ofEpochMilli(now + 1).toString(), now))
        assertTrue(isInboxItemExpired("invalid", now))
    }

    @Test fun documentSuggestionDropsPathsAndControlCharacters() {
        assertEquals("quarterly report.pdf", safeDocumentSuggestion("C:\\private\\quarterly report.pdf"))
        assertEquals("vault-attachment", safeDocumentSuggestion("/\\\u0000"))
        assertEquals(120, safeDocumentSuggestion("x".repeat(200)).length)
    }
}
