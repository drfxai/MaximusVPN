package com.example.data.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecureStorage(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("maximus_secure_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ALIAS = "MaximusMasterKey_v3"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12

        @Volatile
        private var memoryFallbackKey: SecretKey? = null

        @Volatile
        private var activeSecretKey: SecretKey? = null

        @Synchronized
        fun getMasterKey(context: Context? = null): SecretKey {
            activeSecretKey?.let { return it }

            // Clean up any legacy insecure file if present
            context?.let { ctx ->
                try {
                    val legacyKeyFile = File(ctx.filesDir, ".maximus_enc_key")
                    if (legacyKeyFile.exists()) {
                        legacyKeyFile.delete()
                    }
                } catch (_: Exception) {}
            }

            // 1. Try AndroidKeyStore first (Hardware-backed / TEE / StrongBox where supported)
            try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (!keyStore.containsAlias(KEY_ALIAS)) {
                    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                    val spec = KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                    keyGenerator.init(spec)
                    val key = keyGenerator.generateKey()
                    activeSecretKey = key
                    return key
                }
                val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                if (entry != null) {
                    val key = entry.secretKey
                    activeSecretKey = key
                    return key
                }
            } catch (_: Exception) {
                // KeyStore unavailable (e.g. standard JVM test environment)
            }

            // 2. Safe JVM / Test Fallback: Ephemeral random 256-bit key in volatile RAM
            memoryFallbackKey?.let { return it }
            val randomBytes = ByteArray(32).apply { SecureRandom().nextBytes(this) }
            val fallbackKey = SecretKeySpec(randomBytes, "AES")
            memoryFallbackKey = fallbackKey
            activeSecretKey = fallbackKey
            return fallbackKey
        }

        /**
         * Encrypts a string using AES-256-GCM with a fresh random 12-byte IV for every invocation.
         */
        fun encrypt(plaintext: String, context: Context? = null): String {
            if (plaintext.isEmpty()) return ""
            return try {
                val secretKey = getMasterKey(context)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val iv = cipher.iv
                val cipherText = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

                val combined = ByteArray(iv.size + cipherText.size)
                System.arraycopy(iv, 0, combined, 0, iv.size)
                System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

                Base64.getEncoder().encodeToString(combined)
            } catch (_: Exception) {
                // Fail closed: return empty if encryption fails
                ""
            }
        }

        /**
         * Decrypts an AES-256-GCM ciphertext string.
         * Fails closed: returns empty string on any decryption error or corrupted ciphertext.
         */
        fun decrypt(ciphertext: String, context: Context? = null): String {
            if (ciphertext.isEmpty()) return ""
            return try {
                val combined = Base64.getDecoder().decode(ciphertext)
                if (combined.size < GCM_IV_LENGTH + 16) {
                    // Invalid ciphertext length: fail closed
                    return ""
                }

                val iv = ByteArray(GCM_IV_LENGTH)
                System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
                val cipherBytes = ByteArray(combined.size - GCM_IV_LENGTH)
                System.arraycopy(combined, GCM_IV_LENGTH, cipherBytes, 0, cipherBytes.size)

                val secretKey = getMasterKey(context)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
                val decryptedBytes = cipher.doFinal(cipherBytes)
                String(decryptedBytes, Charsets.UTF_8)
            } catch (_: Exception) {
                // Fail-closed: Never return ciphertext or partial data on error
                ""
            }
        }
    }

    fun encryptAndSave(key: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(key).apply()
            return
        }
        val encrypted = encrypt(value, context)
        if (encrypted.isNotEmpty()) {
            prefs.edit().putString(key, encrypted).apply()
        }
    }

    fun getAndDecrypt(key: String, defaultValue: String = ""): String {
        val stored = prefs.getString(key, null) ?: return defaultValue
        if (stored.isBlank()) return defaultValue
        val decrypted = decrypt(stored, context)
        return if (decrypted.isNotEmpty()) decrypted else defaultValue
    }
}

