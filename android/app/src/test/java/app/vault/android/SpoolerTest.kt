package app.vault.android

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class SpoolerTest {
    @Test fun boundsCopyAndReturnsPlaintextCountAndSha256() {
        val source = "sample file bytes".toByteArray()
        val output = ByteArrayOutputStream()
        val result = Spooler.copyBounded(ByteArrayInputStream(source), output, 100)
        assertEquals(source.size.toLong(), result.sizeBytes)
        assertArrayEquals(source, output.toByteArray())
        assertEquals(MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }, result.sha256)
    }

    @Test fun rejectsOverLimitWhileStreaming() {
        assertThrows(IllegalArgumentException::class.java) { Spooler.copyBounded(ByteArrayInputStream(ByteArray(101)), ByteArrayOutputStream(), 100) }
    }
}
