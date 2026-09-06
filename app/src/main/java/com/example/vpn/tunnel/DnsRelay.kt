package com.example.vpn.tunnel

import com.example.vpn.dns.DnsManager
import com.example.vpn.packet.IPv4Header
import com.example.vpn.packet.PacketBuilder
import com.example.vpn.packet.UdpHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class DnsRelay(
    private val scope: CoroutineScope,
    private val defaultDnsServer: String,
    private val fallbackDnsServer: String = "8.8.8.8",
    private val protectSocket: (Socket) -> Boolean,
    private val protectDatagram: (DatagramSocket) -> Boolean,
    private val sendToTun: (ByteArray) -> Unit,
    private val onTraffic: (sent: Long, received: Long) -> Unit
) {

    companion object {
        const val MAX_CONCURRENT_DNS_QUERIES = 64
    }

    private val dnsManager = DnsManager(protectSocket, protectDatagram)
    private val activeDnsQueries = AtomicInteger(0)

    fun handleDnsPacket(
        ipHeader: IPv4Header,
        udpHeader: UdpHeader,
        packetData: ByteArray
    ) {
        val payloadLen = udpHeader.payloadLength
        if (payloadLen <= 0 || payloadLen > packetData.size) return

        // Guard against unbounded coroutine / socket explosion
        if (activeDnsQueries.get() >= MAX_CONCURRENT_DNS_QUERIES) {
            return
        }

        val dnsQueryData = ByteArray(payloadLen)
        System.arraycopy(packetData, udpHeader.payloadOffset, dnsQueryData, 0, payloadLen)

        val clientIp = ipHeader.srcIp
        val clientPort = udpHeader.srcPort
        val serverIp = ipHeader.dstIp
        val serverPort = udpHeader.dstPort

        val targetDns = if (defaultDnsServer.isNotBlank()) defaultDnsServer else "https://8.8.8.8/dns-query"

        activeDnsQueries.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            try {
                onTraffic(dnsQueryData.size.toLong(), 0L)
                val responseData = dnsManager.resolveDnsQuery(dnsQueryData, targetDns, fallbackDnsServer)

                if (responseData != null && responseData.isNotEmpty()) {
                    onTraffic(0L, responseData.size.toLong())

                    val responseIpPacket = PacketBuilder.buildUdpPacket(
                        srcIp = serverIp,
                        dstIp = clientIp,
                        srcPort = serverPort,
                        dstPort = clientPort,
                        payload = responseData
                    )

                    sendToTun(responseIpPacket)
                }
            } finally {
                activeDnsQueries.decrementAndGet()
            }
        }
    }
}
