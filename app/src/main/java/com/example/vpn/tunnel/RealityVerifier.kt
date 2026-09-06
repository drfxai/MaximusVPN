package com.example.vpn.tunnel

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLException

object RealityVerifier {
    /**
     * Strictly verifies that the presented peer certificate matches the expected REALITY public key (pbk)
     * or certificate fingerprint/SPKI. Prevents MITM attacks and rogue CAs from spoofing REALITY targets.
     */
    fun verifyRealityPeer(chain: Array<out X509Certificate>?, expectedPublicKey: String) {
        if (chain.isNullOrEmpty()) {
            throw SSLException("REALITY: No server certificate presented by peer")
        }

        if (expectedPublicKey.isBlank()) {
            throw SSLException("REALITY Security Error: Configured REALITY profile must have a non-blank public key (pbk)")
        }

        val cert = chain[0]
        cert.checkValidity()

        val expected = expectedPublicKey.trim()
        val spkiBytes = cert.publicKey.encoded ?: throw SSLException("REALITY: Unable to extract SubjectPublicKeyInfo")
        val spkiBase64 = Base64.getEncoder().encodeToString(spkiBytes)
        val spkiBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(spkiBytes)

        val sha256 = MessageDigest.getInstance("SHA-256")
        val spkiHash = sha256.digest(spkiBytes)
        val spkiHashBase64 = Base64.getEncoder().encodeToString(spkiHash)
        val spkiHashBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(spkiHash)

        val certEncoded = cert.encoded
        val certHash = MessageDigest.getInstance("SHA-256").digest(certEncoded)
        val certHashBase64 = Base64.getEncoder().encodeToString(certHash)
        val certHashBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(certHash)

        val matches = expected.equals(spkiBase64, ignoreCase = true) ||
                expected.equals(spkiBase64Url, ignoreCase = true) ||
                expected.equals(spkiHashBase64, ignoreCase = true) ||
                expected.equals(spkiHashBase64Url, ignoreCase = true) ||
                expected.equals(certHashBase64, ignoreCase = true) ||
                expected.equals(certHashBase64Url, ignoreCase = true) ||
                matchesHex(expected, spkiBytes, spkiHash, certHash) ||
                matchesRawKey(expected, spkiBytes)

        if (!matches) {
            throw SSLException("REALITY MITM Protection: Presented certificate public key does not match configured pbk ($expected)")
        }
    }

    private fun matchesHex(expected: String, vararg byteArrays: ByteArray): Boolean {
        val cleanExpected = expected.replace(":", "").replace("-", "").lowercase()
        for (bytes in byteArrays) {
            val hex = bytes.joinToString("") { "%02x".format(it) }
            if (hex.equals(cleanExpected, ignoreCase = true)) return true
        }
        return false
    }

    private fun matchesRawKey(expected: String, spkiBytes: ByteArray): Boolean {
        if (spkiBytes.size >= 32) {
            val rawKey = spkiBytes.copyOfRange(spkiBytes.size - 32, spkiBytes.size)
            val rawB64 = Base64.getEncoder().encodeToString(rawKey)
            val rawB64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(rawKey)
            if (expected.equals(rawB64, ignoreCase = true) || expected.equals(rawB64Url, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
