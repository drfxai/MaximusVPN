package com.example.vpn.tunnel

import com.example.data.model.VlessProfile
import java.io.EOFException
import java.io.InputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

/** Shared RFC 6455 upgrade validation for probes and TCP/UDP tunnels. */
object WebSocketHandshake {
    fun perform(socket: Socket, profile: VlessProfile, timeoutMs: Int = 10000) {
        val host = profile.host.ifBlank { profile.sni.ifBlank { profile.address } }
        val path = profile.path.ifBlank { "/" }.let { if (it.startsWith('/')) it else "/$it" }
        require(host.none { it <= ' ' || it == '\u007f' }) { "Invalid WebSocket Host header" }
        require(path.none { it <= ' ' || it == '\u007f' }) { "Invalid WebSocket path; encode spaces and control characters" }
        val key = Base64.getEncoder().encodeToString(ByteArray(16).apply { SecureRandom().nextBytes(this) })
        val previousTimeout = socket.soTimeout
        try {
            socket.soTimeout = timeoutMs
            socket.getOutputStream().apply {
                write(("GET $path HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\n" +
                    "Connection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(Charsets.UTF_8))
                flush()
            }
            validateResponse(socket.getInputStream(), key)
        } finally {
            socket.soTimeout = previousTimeout
        }
    }

    internal fun validateResponse(input: InputStream, key: String) {
        var remaining = 16384
        fun line(): String {
            val text = StringBuilder()
            while (true) {
                check(remaining-- > 0) { "WebSocket response headers exceed 16 KiB" }
                val byte = input.read()
                if (byte < 0) throw EOFException("Incomplete WebSocket upgrade response")
                if (byte == 10) return text.toString().removeSuffix("\r")
                text.append(byte.toChar())
            }
        }
        val status = line()
        val code = Regex("^HTTP/1\\.[01] ([0-9]{3})(?: .*)?$").matchEntire(status)?.groupValues?.get(1)
        check(code == "101") {
            if (code == "404") "WebSocket HTTP 404: verify the configured Host and path with the server provider"
            else "WebSocket upgrade rejected (HTTP ${code ?: "invalid response"}); verify Host, path and TLS settings"
        }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            val separator = header.indexOf(':')
            check(separator > 0) { "Malformed WebSocket response header" }
            val name = header.substring(0, separator).lowercase(Locale.ROOT)
            val value = header.substring(separator + 1).trim()
            headers[name] = headers[name]?.let { "$it,$value" } ?: value
        }
        fun hasToken(name: String, token: String) = headers[name]?.split(',')?.any { it.trim().equals(token, true) } == true
        check(hasToken("upgrade", "websocket") && hasToken("connection", "upgrade")) { "Invalid WebSocket Upgrade/Connection headers" }
        val expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))
        check(headers["sec-websocket-accept"] == expected) { "Invalid WebSocket acceptance key" }
        check(headers["sec-websocket-extensions"].isNullOrEmpty() && headers["sec-websocket-protocol"].isNullOrEmpty()) {
            "Server selected an unrequested WebSocket extension or subprotocol"
        }
    }
}
