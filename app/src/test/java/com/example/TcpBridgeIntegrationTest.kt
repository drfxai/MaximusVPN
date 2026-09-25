package com.example

import com.example.data.model.AppSettings
import com.example.vpn.packet.*
import com.example.vpn.tunnel.TcpVlessTunnel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TcpBridgeIntegrationTest {
    @Test fun duplicateUploadAndFinPayloadReachLocalPeerExactlyOnce() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val executor = Executors.newSingleThreadExecutor()
        val replies = LinkedBlockingQueue<ByteArray>()
        val tunnel = TcpVlessTunnel(scope, null, AppSettings(), {true}, {replies.offer(it); Unit}, {_,_ ->})
        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                listener.soTimeout = 5000
                val received = executor.submit<String> {
                    listener.accept().use { peer ->
                        peer.soTimeout = 5000
                        val bytes = peer.getInputStream().readBytes()
                        peer.getOutputStream().write("ok".toByteArray())
                        bytes.toString(Charsets.UTF_8)
                    }
                }
                fun send(sequence: Long, flags: Int, text: String = "") {
                    val packet = PacketBuilder.buildTcpPacket(byteArrayOf(10,0,0,2), byteArrayOf(127,0,0,1),
                        45678, listener.localPort, sequence, 120001, flags, payload=text.toByteArray())
                    tunnel.handleTcpPacket(IPv4Header.parse(packet)!!, TcpHeader.parse(packet,0,20,packet.size)!!, packet)
                }
                send(100, 2)
                val synAck = replies.poll(5, TimeUnit.SECONDS)!!
                assertTrue(TcpHeader.parse(synAck,0,20,synAck.size)!!.isSyn)
                send(101, 24, "hello")
                send(101, 24, "hello") // retransmission must not be forwarded again
                send(109, 24, "gap") // gap must not advance the ACK
                send(106, 25, "!") // payload on FIN must be drained before half-close
                assertEquals("hello!", received.get(6, TimeUnit.SECONDS))
                var downstream = ""
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (downstream.isEmpty() && System.nanoTime() < deadline) {
                    val packet = replies.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    val tcp = TcpHeader.parse(packet,0,20,packet.size)!!
                    if (tcp.payloadLength > 0) downstream += packet.copyOfRange(tcp.payloadOffset, packet.size).toString(Charsets.UTF_8)
                }
                assertEquals("ok", downstream)
            }
        } finally {
            tunnel.closeAll(); scope.cancel(); executor.shutdownNow()
        }
    }
}
