package com.example.core

import java.io.PrintWriter
import java.io.StringWriter
import java.util.regex.Pattern

object SecretRedactor {

    private val UUID_PATTERN = Pattern.compile(
        "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"
    )

    private val PROXY_URL_PATTERN = Pattern.compile(
        "(vless|vmess|trojan|ss|ssr|hysteria2|hy2|tuic)://([^@]+)@([^:/?#]+):(\\d+)([^\\s\"'<>]*)"
    )

    private val REALITY_PBK_PATTERN = Pattern.compile(
        "(pbk|publicKey|public_key|secretKey|private_key|privateKey)=([^&\\s,}{\"]+)"
    )

    private val REALITY_SID_PATTERN = Pattern.compile(
        "(sid|shortId|short_id)=([^&\\s,}{\"]+)"
    )

    private val PASSWORD_KEY_PATTERN = Pattern.compile(
        "(password|pass|secret|token|auth|key|auth_token)\\s*[:=]\\s*\"?([^\\s,}{\"]+)\"?",
        Pattern.CASE_INSENSITIVE
    )

    private val JSON_ID_PATTERN = Pattern.compile(
        "\"id\"\\s*:\\s*\"[^\"]+\""
    )

    private val JSON_PBK_PATTERN = Pattern.compile(
        "\"(publicKey|privateKey|password|secret|key|token)\"\\s*:\\s*\"[^\"]+\""
    )

    private val JSON_SID_PATTERN = Pattern.compile(
        "\"shortId\"\\s*:\\s*\"[^\"]+\""
    )

    private val BEARER_TOKEN_PATTERN = Pattern.compile(
        "Bearer\\s+([A-Za-z0-9_.-]+)",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Redacts all sensitive fields from logs, error stack traces, and diagnostics text.
     */
    fun redact(text: String): String {
        if (text.isBlank()) return text
        var result = text

        // Redact full proxy URLs (vless://, vmess://, trojan://, ss://, etc.)
        val proxyMatcher = PROXY_URL_PATTERN.matcher(result)
        if (proxyMatcher.find()) {
            result = proxyMatcher.replaceAll("$1://[REDACTED_CREDENTIALS]@$3:$4[REDACTED_PARAMS]")
        }

        // Redact standalone UUIDs
        result = UUID_PATTERN.matcher(result).replaceAll("[REDACTED_UUID]")

        // Redact REALITY parameters & keys
        result = REALITY_PBK_PATTERN.matcher(result).replaceAll("$1=[REDACTED_KEY]")
        result = REALITY_SID_PATTERN.matcher(result).replaceAll("$1=[REDACTED_SID]")

        // Redact passwords, tokens, auth keys
        result = PASSWORD_KEY_PATTERN.matcher(result).replaceAll("$1=[REDACTED]")
        result = BEARER_TOKEN_PATTERN.matcher(result).replaceAll("Bearer [REDACTED_TOKEN]")

        // Redact JSON fields
        result = JSON_ID_PATTERN.matcher(result).replaceAll("\"id\": \"[REDACTED_UUID]\"")
        result = JSON_PBK_PATTERN.matcher(result).replaceAll("\"$1\": \"[REDACTED_KEY]\"")
        result = JSON_SID_PATTERN.matcher(result).replaceAll("\"shortId\": \"[REDACTED_SID]\"")

        return result
    }

    /**
     * Formats and redacts an exception stack trace into a clean, safe diagnostic string.
     */
    fun formatThrowable(throwable: Throwable, maxFrames: Int = 10): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val fullTrace = sw.toString()

        val lines = fullTrace.lines()
        val condensed = if (lines.size > maxFrames + 1) {
            lines.take(maxFrames).joinToString("\n") + "\n\t... (${lines.size - maxFrames} more frames omitted)"
        } else {
            fullTrace
        }

        return redact(condensed)
    }

    /**
     * Masks a UUID for UI display (e.g. "a1b2...c3d4").
     */
    fun maskUuid(uuid: String): String {
        if (uuid.length <= 8) return "****"
        return "${uuid.take(4)}...${uuid.takeLast(4)}"
    }
}
