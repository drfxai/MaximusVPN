package com.example.vpn.tunnel

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import javax.net.ssl.SSLException
import javax.security.auth.x500.X500Principal

class RealityVerifierTest {

    private class MockX509Certificate(
        private val pubKey: java.security.PublicKey,
        private val encodedBytes: ByteArray = byteArrayOf(1, 2, 3, 4)
    ) : X509Certificate() {
        override fun checkValidity() {}
        override fun checkValidity(date: Date?) {}
        override fun getPublicKey(): java.security.PublicKey = pubKey
        override fun getEncoded(): ByteArray = encodedBytes
        override fun getVersion(): Int = 3
        override fun getSerialNumber(): BigInteger = BigInteger.ONE
        override fun getIssuerDN(): java.security.Principal = X500Principal("CN=Test")
        override fun getSubjectDN(): java.security.Principal = X500Principal("CN=Test")
        override fun getNotBefore(): Date = Date()
        override fun getNotAfter(): Date = Date(System.currentTimeMillis() + 1000000)
        override fun getSigAlgName(): String = "SHA256withRSA"
        override fun getSigAlgOID(): String = "1.2.840.113549.1.1.11"
        override fun getSigAlgParams(): ByteArray? = null
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getKeyUsage(): BooleanArray? = null
        override fun getBasicConstraints(): Int = -1
        override fun verify(key: java.security.PublicKey?) {}
        override fun verify(key: java.security.PublicKey?, sigProvider: String?) {}
        override fun toString(): String = "MockX509Certificate"
        override fun getTBSCertificate(): ByteArray = byteArrayOf()
        override fun getSignature(): ByteArray = byteArrayOf()
        override fun hasUnsupportedCriticalExtension(): Boolean = false
        override fun getCriticalExtensionOIDs(): Set<String>? = null
        override fun getNonCriticalExtensionOIDs(): Set<String>? = null
        override fun getExtensionValue(oid: String?): ByteArray? = null
    }

    @Test
    fun testRejectBlankOrEmptyPublicKey() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val mockCert = MockX509Certificate(keyPair.public)
        val chain = arrayOf(mockCert)

        // Blank key should throw SSLException
        assertThrows(SSLException::class.java) {
            RealityVerifier.verifyRealityPeer(chain, "")
        }
        assertThrows(SSLException::class.java) {
            RealityVerifier.verifyRealityPeer(chain, "   ")
        }
    }

    @Test
    fun testRejectNullOrEmptyChain() {
        assertThrows(SSLException::class.java) {
            RealityVerifier.verifyRealityPeer(null, "some_key")
        }
        assertThrows(SSLException::class.java) {
            RealityVerifier.verifyRealityPeer(emptyArray(), "some_key")
        }
    }

    @Test
    fun testRejectMismatchedKey() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val mockCert = MockX509Certificate(keyPair.public)
        val chain = arrayOf(mockCert)

        assertThrows(SSLException::class.java) {
            RealityVerifier.verifyRealityPeer(chain, "invalid_mismatched_public_key")
        }
    }

    @Test
    fun testAcceptMatchingPublicKeySpkiHash() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val spki = keyPair.public.encoded
        val hash = MessageDigest.getInstance("SHA-256").digest(spki)
        val expectedBase64 = Base64.getEncoder().encodeToString(hash)

        val mockCert = MockX509Certificate(keyPair.public)
        val chain = arrayOf(mockCert)

        // Should succeed without throwing
        RealityVerifier.verifyRealityPeer(chain, expectedBase64)
    }
}
