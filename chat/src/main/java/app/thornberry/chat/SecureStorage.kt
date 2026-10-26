package app.thornberry.chat

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts app files with an AES-256-GCM key that lives in the Android Keystore (non-exportable).
 * The file name is bound in as associated data, so a file cannot be swapped for another one.
 * If decryption fails we never silently start from empty state (see [Node]).
 */
object SecureStorage {
    private const val ALIAS = "thornberry-state-key-v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return kg.generateKey()
    }

    fun encrypt(plain: ByteArray, name: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(name.toByteArray())
        return c.iv + c.doFinal(plain)
    }

    fun decrypt(data: ByteArray, name: String): ByteArray {
        require(data.size > 12 + 16) { "file too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        c.updateAAD(name.toByteArray())
        return c.doFinal(data, 12, data.size - 12)
    }

    /** Write atomically: temp file first, then rename, so a crash never leaves a half-written file. */
    fun write(dir: File, name: String, plain: ByteArray) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(encrypt(plain, name))
        if (!tmp.renameTo(File(dir, name))) {
            File(dir, name).delete()
            check(tmp.renameTo(File(dir, name))) { "could not save $name" }
        }
    }

    /** Null if the file does not exist; throws if it exists but cannot be decrypted. */
    fun read(dir: File, name: String): ByteArray? {
        val f = File(dir, name)
        if (!f.exists()) return null
        return decrypt(f.readBytes(), name)
    }
}
