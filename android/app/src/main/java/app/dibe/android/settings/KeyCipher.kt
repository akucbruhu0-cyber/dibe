package app.dibe.android.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API keys at rest: AES-GCM under a non-exportable Android Keystore key, stored as base64(iv + ciphertext). */
internal object KeyCipher {
    private const val ALIAS = "dibe-settings"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Null when the Keystore refuses; the key then lives in memory only, never in plain text on disk. */
    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
            Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()))
        } catch (e: GeneralSecurityException) {
            fail("encrypt", e)
        } catch (e: ProviderException) {
            fail("encrypt", e)
        } catch (e: IOException) {
            fail("encrypt", e)
        }
    }

    /** Empty when the ciphertext cannot be read, e.g. after a restore onto a device without the key. */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return try {
            val bytes = Base64.getDecoder().decode(stored)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            }
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
        } catch (e: GeneralSecurityException) {
            fail("decrypt", e) ?: ""
        } catch (e: ProviderException) {
            fail("decrypt", e) ?: ""
        } catch (e: IOException) {
            fail("decrypt", e) ?: ""
        } catch (e: IllegalArgumentException) {
            fail("decrypt", e) ?: ""
        }
    }

    private fun fail(operation: String, error: Exception): String? {
        Log.w("KeyCipher", "failed to $operation an API key", error)
        return null
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }
}
