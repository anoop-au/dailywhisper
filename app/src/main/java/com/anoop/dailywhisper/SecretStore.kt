package com.anoop.dailywhisper

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secrets (the speech-slot API keys) kept on this phone only, encrypted with an AES-256-GCM key
 * that lives in the Android Keystore and never leaves it. Stored in its own prefs file ("secrets"),
 * which is excluded from backup (allowBackup=false). Values are never logged.
 */
object SecretStore {
    private const val KEY_ALIAS = "dailywhisper_secrets_v1"
    private const val FILE = "secrets"
    const val STT_API_KEY = "stt_api_key"       // speech slot 1 (Deepgram); name kept from v0.1.5
    const val SLOT2_API_KEY = "stt_api_key_2"   // speech slot 2 (Sarvam AI)

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun put(c: Context, name: String, value: String) {
        if (value.isEmpty()) { prefs(c).edit().remove(name).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + ct // 12-byte IV, then ciphertext + tag
        prefs(c).edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    /** The stored value, or "" if none (or if it can no longer be decrypted, e.g. after a key reset). */
    fun get(c: Context, name: String): String {
        val stored = prefs(c).getString(name, null) ?: return ""
        return runCatching {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
            String(cipher.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun has(c: Context, name: String): Boolean = prefs(c).contains(name)
}
