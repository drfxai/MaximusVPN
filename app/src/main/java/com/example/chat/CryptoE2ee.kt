package com.example.chat

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Experimental local cryptographic helpers. These do not constitute E2EE: no authenticated
 * key exchange or peer transport is implemented, and the deterministic message-key derivation
 * does not provide forward secrecy.
 */
object CryptoE2ee {

    private val secureRandom = SecureRandom()
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12

    /**
     * Generates a unique, cryptographically strong 20-digit Atomic ID formatted as 5 blocks:
     * e.g., "5829-1048-3920-5620-1849"
     */
    fun generateAtomicId(): String {
        val digits = StringBuilder()
        for (i in 0 until 18) {
            digits.append(secureRandom.nextInt(10))
        }

        // Calculate 2-digit checksum using weighted modulo algorithm
        val raw18 = digits.toString()
        val checksum = computeChecksum2Digits(raw18)
        digits.append(checksum)

        val full20 = digits.toString()
        return formatAtomicId(full20)
    }

    /**
     * Validates whether a given string is a valid 20-digit Atomic ID with matching checksum.
     */
    fun isValidAtomicId(input: String): Boolean {
        val normalized = input.replace("-", "").trim()
        if (normalized.length != 20 || !normalized.all { it.isDigit() }) {
            return false
        }
        val prefix = normalized.substring(0, 18)
        val expectedCheck = computeChecksum2Digits(prefix)
        val actualCheck = normalized.substring(18, 20)
        return expectedCheck == actualCheck
    }

    /**
     * Formats 20 continuous digits into standard 5-block display format: XXXX-XXXX-XXXX-XXXX-XXXX
     */
    fun formatAtomicId(raw: String): String {
        val digits = raw.replace("-", "").filter { it.isDigit() }.take(20)
        return digits.chunked(4).joinToString("-")
    }

    private fun computeChecksum2Digits(digits18: String): String {
        var sum1 = 0
        var sum2 = 0
        for ((idx, ch) in digits18.withIndex()) {
            val d = ch.digitToInt()
            if (idx % 2 == 0) {
                sum1 += (d * 3)
            } else {
                sum2 += (d * 7)
            }
        }
        val c1 = (sum1 % 10).toString()
        val c2 = (sum2 % 10).toString()
        return "$c1$c2"
    }

    /** Derives a deterministic test key from two identifiers; unsuitable as a shared secret. */
    fun deriveSharedKey(myAtomicId: String, peerAtomicId: String): SecretKey {
        val sortedPair = listOf(myAtomicId.replace("-", ""), peerAtomicId.replace("-", "")).sorted()
        val seed = "MAXIMUS_E2EE_PFS_V3_ROOT:${sortedPair[0]}:${sortedPair[1]}".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(seed)
        val key = SecretKeySpec(keyBytes, "AES")
        seed.fill(0)
        return key
    }

    /** Derives an indexed test key; this is not a stateful forward-secret ratchet. */
    fun deriveRatchetMessageKey(rootKey: SecretKey, messageIndex: Long): SecretKey {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(rootKey.encoded, "HmacSHA256"))
        val info = "MAXIMUS_RATCHET_MSG_KEY:$messageIndex".toByteArray(Charsets.UTF_8)
        val derivedBytes = mac.doFinal(info)
        val msgKey = SecretKeySpec(derivedBytes, "AES")
        info.fill(0)
        return msgKey
    }

    /**
     * Encrypts plaintext string using AES-256-GCM.
     * Returns Base64 payload containing IV (12 bytes) + Ciphertext + GCM Tag (16 bytes).
     */
    fun encrypt(plaintext: String, secretKey: SecretKey): String {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).apply { secureRandom.nextBytes(this) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintextBytes)

        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

        // Wipe sensitive plaintext bytes from memory
        plaintextBytes.fill(0)

        return Base64.getEncoder().encodeToString(combined)
    }

    /**
     * Decrypts Base64 payload using AES-256-GCM.
     */
    fun decrypt(encryptedBase64: String, secretKey: SecretKey): String {
        val combined = Base64.getDecoder().decode(encryptedBase64)
        if (combined.size < GCM_IV_LENGTH_BYTES + 16) {
            throw IllegalArgumentException("Invalid encrypted payload size")
        }

        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH_BYTES)

        val ciphertextLen = combined.size - GCM_IV_LENGTH_BYTES
        val ciphertext = ByteArray(ciphertextLen)
        System.arraycopy(combined, GCM_IV_LENGTH_BYTES, ciphertext, 0, ciphertextLen)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val decryptedBytes = cipher.doFinal(ciphertext)
        val result = String(decryptedBytes, Charsets.UTF_8)

        // Secure wipe
        decryptedBytes.fill(0)
        ciphertext.fill(0)
        return result
    }

    /**
     * Securely shreds memory byte buffers.
     */
    fun shred(bytes: ByteArray) {
        secureRandom.nextBytes(bytes)
        bytes.fill(0)
    }
}
