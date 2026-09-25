package com.example

import com.example.data.model.ProxyNode
import com.example.data.model.SubscriptionInfo
import com.example.data.model.VlessProfile
import com.example.data.database.ServerProfileEntity
import com.example.data.database.SubscriptionEntity
import com.example.vless.VlessHeader
import com.example.vpn.engine.MihomoEngine
import com.example.vpn.tunnel.UpstreamProtocol
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamProtocolRegressionTest {
    @Test
    fun vlessResponseRequiresVersionZeroAndCompleteAddons() {
        assertTrue(VlessHeader.decodeResponse(ByteArrayInputStream(byteArrayOf(0, 2, 10, 11))))
        assertFalse(VlessHeader.decodeResponse(ByteArrayInputStream(byteArrayOf(1, 0))))
        assertFalse(VlessHeader.decodeResponse(ByteArrayInputStream(byteArrayOf(0, 2, 10))))
    }

    @Test
    fun httpConnectAcceptsOnlyACompleteSuccessfulResponse() {
        UpstreamProtocol.validateHttpConnectResponse(
            ByteArrayInputStream("HTTP/1.1 200 Connection established\r\nProxy-Agent: test\r\n\r\n".toByteArray())
        )
        assertThrows(IllegalStateException::class.java) {
            UpstreamProtocol.validateHttpConnectResponse(
                ByteArrayInputStream("HTTP/1.1 500 failure containing 200\r\n\r\n".toByteArray())
            )
        }
        assertThrows(java.io.EOFException::class.java) {
            UpstreamProtocol.validateHttpConnectResponse(
                ByteArrayInputStream("HTTP/1.1 200 Connection established\r\n".toByteArray())
            )
        }
    }

    @Test
    fun socks5HandshakeValidatesEveryReplyField() {
        val successReply = byteArrayOf(
            0x05, 0x00,
            0x05, 0x00, 0x00, 0x01,
            127, 0, 0, 1, 0x1f, 0x90.toByte()
        )
        val request = ByteArrayOutputStream()
        UpstreamProtocol.performSocks5Connect(
            ByteArrayInputStream(successReply), request, "8.8.8.8", 443
        )
        assertArrayEquals(byteArrayOf(0x05, 0x01, 0x00), request.toByteArray().copyOfRange(0, 3))

        val invalidAddressType = byteArrayOf(
            0x05, 0x00,
            0x05, 0x00, 0x00, 0x7f
        )
        assertThrows(IllegalStateException::class.java) {
            UpstreamProtocol.performSocks5Connect(
                ByteArrayInputStream(invalidAddressType), ByteArrayOutputStream(), "8.8.8.8", 443
            )
        }
    }

    @Test
    fun mihomoProbeStopsWhenSocketProtectionIsRejected() {
        val node = ProxyNode(name = "test", server = "127.0.0.1", port = 9)
        assertNull(MihomoEngine.instance.testNodeLatency(node) { false })
    }

    @Test
    fun subscriptionCredentialsAreNotPersistedInPlaintext() {
        val url = "https://provider.example/subscription?token=top-secret"
        val subscriptionEntity = SubscriptionEntity.fromDomain(SubscriptionInfo(name = "test", url = url))
        assertNotEquals(url, subscriptionEntity.url)
        assertEquals(url, subscriptionEntity.toDomain().url)

        val profileEntity = ServerProfileEntity.fromDomain(
            VlessProfile(
                name = "node",
                address = "203.0.113.1",
                port = 443,
                uuid = "00000000-0000-0000-0000-000000000001",
                sourceSubscription = url
            )
        )
        assertNotEquals(url, profileEntity.subscriptionUrl)
        assertNotEquals(url, profileEntity.sourceSubscription)
        assertTrue(profileEntity.sourceSubscription?.startsWith("sha256:") == true)
        assertEquals(url, profileEntity.toDomain().sourceSubscription)
    }
}
