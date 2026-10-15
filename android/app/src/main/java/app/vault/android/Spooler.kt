package app.vault.android

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

data class SpoolResult(val sizeBytes: Long, val sha256: String)

object Spooler {
    const val MAX_FILE_BYTES = 100_000_000L
    fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long = MAX_FILE_BYTES): SpoolResult {
        require(maxBytes >= 0)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            require(total <= maxBytes - read) { "File exceeds the 100 MB limit." }
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            total += read
        }
        output.flush()
        return SpoolResult(total, digest.digest().joinToString("") { "%02x".format(it) })
    }
}
