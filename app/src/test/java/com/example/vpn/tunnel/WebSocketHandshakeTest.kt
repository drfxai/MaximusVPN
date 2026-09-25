package com.example.vpn.tunnel

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class WebSocketHandshakeTest {
    private val key = "dGhlIHNhbXBsZSBub25jZQ=="
    private val headers = "Upgrade: websocket\r\nConnection: keep-alive, Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"

    @Test fun validUpgradeLeavesTunnelPayloadUnread() {
        val input = ByteArrayInputStream(("HTTP/1.1 101 Switching Protocols\r\n" + headers + "payload").toByteArray())
        WebSocketHandshake.validateResponse(input, key)
        assertEquals("payload", input.readBytes().toString(Charsets.UTF_8))
    }

    @Test fun rejectsStatusContaining101WithoutAnUpgrade() {
        assertThrows(IllegalStateException::class.java) {
            WebSocketHandshake.validateResponse(ByteArrayInputStream(("HTTP/1.1 404 error101\r\n" + headers).toByteArray()), key)
        }
    }

    @Test fun explains404WithoutDisclosingConfiguration() {
        val error = assertThrows(IllegalStateException::class.java) {
            WebSocketHandshake.validateResponse(ByteArrayInputStream("HTTP/1.1 404 Not Found\r\n".toByteArray()), key)
        }
        assertTrue(error.message!!.contains("Host and path"))
    }

    @Test fun rejectsWrongAcceptanceKey() {
        assertThrows(IllegalStateException::class.java) {
            WebSocketHandshake.validateResponse(ByteArrayInputStream(("HTTP/1.1 101 Switching Protocols\r\n" + headers.replace("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", "wrong")).toByteArray()), key)
        }
    }

    @Test fun rejectsMissingUpgradeHeader() {
        assertThrows(IllegalStateException::class.java) {
            WebSocketHandshake.validateResponse(ByteArrayInputStream(("HTTP/1.1 101 Switching Protocols\r\n" + headers.replace("Upgrade: websocket\r\n", "")).toByteArray()), key)
        }
    }

    @Test fun rejectsOversizedAndTruncatedResponses() {
        assertThrows(IllegalStateException::class.java) {
            WebSocketHandshake.validateResponse(ByteArrayInputStream(("HTTP/1.1 101 OK\r\nX: " + "a".repeat(17000)).toByteArray()), key)
        }
        assertThrows(java.io.EOFException::class.java) {
            WebSocketHandshake.validateResponse(ByteArrayInputStream("HTTP/1.1 101 OK\r\n".toByteArray()), key)
        }
    }
}
