package dev.alore.argos.data

import android.content.Context
import androidx.core.content.edit
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Kinds of per-server secrets. */
enum class SecretKind { PRIVATE_KEY, PASSPHRASE, PASSWORD }

/**
 * Per-server secrets (private key text, passphrase, password) encrypted with AES-256-GCM using a
 * non-exportable key in the Android Keystore; ciphertexts live in a private SharedPreferences
 * file. Never stored in the server model or in plain text.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("argos_secrets", Context.MODE_PRIVATE)

    /** Stores (or replaces) a secret; null/empty deletes it. */
    fun put(serverId: String, kind: SecretKind, value: String?) {
        val prefKey = prefKey(serverId, kind)
        if (value.isNullOrEmpty()) {
            prefs.edit { remove(prefKey) }
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit { putString(prefKey, Base64.encodeToString(payload, Base64.NO_WRAP)) }
    }

    /** Null when missing or undecryptable (e.g. Keystore key lost); the latter is logged. */
    fun get(serverId: String, kind: SecretKind): String? {
        val encoded = prefs.getString(prefKey(serverId, kind), null) ?: return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = payload.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            }
            cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Could not decrypt $kind for server $serverId", e)
            null
        }
    }

    fun deleteAll(serverId: String) {
        prefs.edit { SecretKind.entries.forEach { remove(prefKey(serverId, it)) } }
    }

    private fun prefKey(serverId: String, kind: SecretKind) = "$serverId.${kind.name}"

    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val TAG = "Argos/Secrets"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "argos-secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
