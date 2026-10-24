package app.vault.android

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class VaultCrypto(
    private val testKey: SecretKey? = null,
    private val context: Context? = null,
) {
    companion object {
        private const val IV_BYTES = 12
        private const val STREAM_IV_BYTES = 16
        @Volatile private var cachedSoftwareKey: SecretKey? = null
    }

    private fun key(): SecretKey {
        testKey?.let { return it }
        cachedSoftwareKey?.let { return it }
        synchronized(VaultCrypto::class.java) {
            cachedSoftwareKey?.let { return it }
            val k = resolveKey()
            cachedSoftwareKey = k
            return k
        }
    }

    private fun resolveKey(): SecretKey {
        val keyFile = context?.let { File(it.filesDir, "vault-private/.vault_key") }
        if (keyFile != null && keyFile.exists() && keyFile.length() == 32L) {
            try {
                return SecretKeySpec(keyFile.readBytes(), "AES")
            } catch (_: Exception) {}
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        keyFile?.let {
            try {
                it.parentFile?.mkdirs()
                it.writeBytes(raw)
            } catch (_: Exception) {}
        }
        return SecretKeySpec(raw, "AES")
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(encrypted: ByteArray): ByteArray {
        require(encrypted.size > IV_BYTES) { "Encrypted data is truncated." }
        val iv = encrypted.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return cipher.doFinal(encrypted, IV_BYTES, encrypted.size - IV_BYTES)
    }

    fun encrypting(output: OutputStream): OutputStream {
        val iv = ByteArray(STREAM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(), IvParameterSpec(iv))
        }
        output.write(iv)
        return CipherOutputStream(output, cipher)
    }

    fun decrypting(input: InputStream): InputStream {
        val iv = ByteArray(STREAM_IV_BYTES)
        var offset = 0
        while (offset < iv.size) {
            val count = input.read(iv, offset, iv.size - offset)
            require(count > 0) { "Encrypted data is truncated." }
            offset += count
        }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), IvParameterSpec(iv))
        }
        return CipherInputStream(input, cipher)
    }
}
