package com.example

import com.example.core.SecretRedactor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {

    @Test
    fun redact_replacesUuidWithSanitizedPlaceholder() {
        val rawLog = "Handshake with user UUID e7b99c42-88f1-4b19-9182-3d84a7e93f12 established"
        val redacted = SecretRedactor.redact(rawLog)

        assertFalse(redacted.contains("e7b99c42-88f1-4b19-9182-3d84a7e93f12"))
        assertTrue(redacted.contains("[REDACTED_UUID]"))
    }

    @Test
    fun redact_replacesJsonSecrets() {
        val rawJson = """{"id": "e7b99c42-88f1-4b19-9182-3d84a7e93f12", "publicKey": "D4g8xP_98uI1O4L6v3Yq0eN7w2m1k0j9i8h7g6f5e4d"}"""
        val redacted = SecretRedactor.redact(rawJson)

        assertFalse(redacted.contains("D4g8xP_98uI1O4L6v3Yq0eN7w2m1k0j9i8h7g6f5e4d"))
        assertTrue(redacted.contains("[REDACTED_KEY]"))
    }

    @Test
    fun redact_replacesProxyUrlCredentials() {
        val rawLog = "Connecting to vless://e7b99c42-88f1-4b19-9182-3d84a7e93f12@example.com:443?security=reality&pbk=D4g8xP#Server1"
        val redacted = SecretRedactor.redact(rawLog)

        assertFalse(redacted.contains("e7b99c42-88f1-4b19-9182-3d84a7e93f12"))
        assertTrue(redacted.contains("REDACTED_CREDENTIALS") || redacted.contains("REDACTED_UUID"))
    }

    @Test
    fun redact_replacesHttpsUserInfoCredentials() {
        val redacted = SecretRedactor.redact("Syncing https://alice:secret@example.com/sub")
        assertFalse(redacted.contains("alice:secret"))
        assertTrue(redacted.contains("https://[REDACTED_CREDENTIALS]@example.com/sub"))
    }

    @Test
    fun formatThrowable_sanitizesSensitiveStackTrace() {
        val exception = RuntimeException("Connection failed for vless://e7b99c42-88f1-4b19-9182-3d84a7e93f12@192.168.1.1:443")
        val formatted = SecretRedactor.formatThrowable(exception)

        assertFalse(formatted.contains("e7b99c42-88f1-4b19-9182-3d84a7e93f12"))
        assertTrue(formatted.contains("RuntimeException"))
    }
}
