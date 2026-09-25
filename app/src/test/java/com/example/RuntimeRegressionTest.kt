package com.example

import com.example.data.model.*
import com.example.vless.VlessValidator
import com.example.vpn.engine.RuntimeCapabilities
import com.example.vpn.packet.*
import com.example.vpn.tunnel.*
import com.example.vpn.dns.DnsResponse
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RuntimeRegressionTest {
    private val node = VlessProfile(name="test", address="example.invalid", port=443, uuid="00000000-0000-0000-0000-000000000001")

    @Test fun unsupportedProfilesCannotMasqueradeAsWorkingTunnels() {
        assertNull(RuntimeCapabilities.unsupportedReason(node))
        assertNotNull(RuntimeCapabilities.unsupportedReason(node.copy(security="reality")))
        assertNotNull(RuntimeCapabilities.unsupportedReason(node.copy(security="tls", fingerprint="unsafe")))
        assertNotNull(RuntimeCapabilities.unsupportedReason(node.copy(transport="grpc")))
        assertNotNull(RuntimeCapabilities.unsupportedReason(node.copy(protocolType=ProtocolType.VMESS)))
        assertNotNull(RuntimeCapabilities.unsupportedReason(node.copy(flow="xtls-rprx-vision")))
        assertNotNull(RuntimeCapabilities.unsupportedReason(node.copy(profileType=ProfileType.MIHOMO_YAML, rawConfig="proxies: []")))
        assertNotNull(RuntimeCapabilities.unsupportedReason(node.copy(protocolType=ProtocolType.TROJAN, uuid="password", security="none")))
        assertNull(RuntimeCapabilities.unsupportedReason(node.copy(protocolType=ProtocolType.TROJAN, uuid="password", security="tls")))
        val socks = node.copy(protocolType=ProtocolType.SOCKS5, uuid="")
        VlessValidator.validate(socks)
        assertNull(RuntimeCapabilities.unsupportedReason(socks))
    }

    @Test fun packetBoundsUseActualReadLengthRatherThanReusableBufferSize() {
        val udp = PacketBuilder.buildUdpPacket(byteArrayOf(1,2,3,4), byteArrayOf(8,8,8,8), 42, 53, ByteArray(12))
        assertNull(IPv4Header.parse(udp.copyOf(2000), 0, 25))
        assertNull(UdpHeader.parse(udp, 20, 30))
        assertNull(UdpHeader.parse(udp, -1, udp.size))
        udp[24] = 0; udp[25] = 7
        assertNull(UdpHeader.parse(udp, 20, udp.size))
        val tcp = PacketBuilder.buildTcpPacket(byteArrayOf(1,2,3,4), byteArrayOf(8,8,8,8), 42, 443, 1, 1, 16)
        tcp[32] = 0x40
        assertNull(TcpHeader.parse(tcp, 0, 20, tcp.size))
        tcp[32] = 0xf0.toByte()
        assertNull(TcpHeader.parse(tcp, 0, 20, tcp.size))
    }

    @Test fun retransmissionsGapsAndSequenceWrapAreHandled() {
        assertEquals(0, TcpSequence.acceptedOffset(100, 100, 10))
        assertNull(TcpSequence.acceptedOffset(110, 100, 10))
        assertEquals(5, TcpSequence.acceptedOffset(105, 100, 10))
        assertNull(TcpSequence.acceptedOffset(100, 105, 10))
        assertEquals(2L, TcpSequence.add(0xfffffffe, 4))
        assertEquals(4, TcpSequence.acceptedOffset(2, 0xfffffffe, 8))
    }

    @Test fun websocketPingIsConsumedAndAnsweredWithoutCorruptingData() {
        val stream = ByteArrayInputStream(byteArrayOf(0x89.toByte(),2,1,2, 0x82.toByte(),3,4,5,6))
        var ping: ByteArray? = null
        assertEquals(0, WebSocketCodec.readFrame(stream) { ping=it }!!.size)
        assertArrayEquals(byteArrayOf(1,2), ping)
        assertArrayEquals(byteArrayOf(4,5,6), WebSocketCodec.readFrame(stream))
        assertEquals(0x8a, WebSocketCodec.encodeFrame(ping!!, 10)[0].toInt() and 255)
    }

    @Test fun websocketStreamPreservesUdpDataAcrossFrameBoundaries() {
        val stream = WebSocketInputStream(ByteArrayInputStream(byteArrayOf(0x82.toByte(),1,0, 0x82.toByte(),2,3,7, 0x82.toByte(),2,8,9))) {}
        assertArrayEquals(byteArrayOf(0,3,7,8,9), stream.readBytes())
    }

    @Test fun maliciousAndTruncatedWebsocketLengthsFailWithoutAllocation() {
        assertThrows(IllegalArgumentException::class.java) {
            WebSocketCodec.readFrame(ByteArrayInputStream(byteArrayOf(0x82.toByte(),127,-128,0,0,0,0,0,0,0)))
        }
        assertThrows(java.io.EOFException::class.java) {
            WebSocketCodec.readFrame(ByteArrayInputStream(byteArrayOf(0x82.toByte(),3,1)))
        }
    }

    @Test fun dnsRequiresMatchingTransactionAndResponseFlag() {
        val query = ByteArray(12).apply { this[0]=1; this[1]=2 }
        val response = query.copyOf().apply { this[2]=0x80.toByte() }
        assertTrue(DnsResponse.isResponseTo(query,response))
        assertFalse(DnsResponse.isResponseTo(query,query))
        response[1]=3
        assertFalse(DnsResponse.isResponseTo(query,response))
        assertFalse(DnsResponse.isResponseTo(query,ByteArray(5)))
        assertFalse(DnsResponse.isResponseTo(query,ByteArray(65508).apply {
            this[0]=1; this[1]=2; this[2]=0x80.toByte()
        }))
    }

    @Test fun packetBuildersRejectOversizedPayloads() {
        assertThrows(IllegalArgumentException::class.java) {
            PacketBuilder.buildUdpPacket(byteArrayOf(1,2,3,4), byteArrayOf(8,8,8,8), 53, 53, ByteArray(65508))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PacketBuilder.buildTcpPacket(byteArrayOf(1,2,3,4), byteArrayOf(8,8,8,8), 443, 443, 1, 1, 16, payload=ByteArray(65496))
        }
    }
}
