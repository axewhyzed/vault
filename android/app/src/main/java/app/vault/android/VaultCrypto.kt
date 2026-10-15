package app.vault.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class VaultCrypto(private val testKey: SecretKey? = null) {
    private fun key() = testKey ?: androidKey()
    private fun androidKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
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
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        output.write(cipher.iv); return CipherOutputStream(output, cipher)
    }
    fun decrypting(input: InputStream): InputStream {
        val iv = ByteArray(IV_BYTES); var offset = 0
        while (offset < iv.size) { val count = input.read(iv, offset, iv.size - offset); require(count > 0) { "Encrypted data is truncated." }; offset += count }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return CipherInputStream(input, cipher)
    }
    companion object { private const val ALIAS = "vault.android.local.v1"; private const val IV_BYTES = 12 }
}
