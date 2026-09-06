package com.example.vpn.dns

import com.example.xray.XrayLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class DnsManager(
    private val protectSocket: (Socket) -> Boolean,
    private val protectDatagram: (DatagramSocket) -> Boolean
) {
    private val dohClient = DoHClient(protectSocket)
    private val queryCount = AtomicInteger(0)

    suspend fun resolveDnsQuery(
        queryData: ByteArray,
        primaryDns: String,
        fallbackDns: String = "8.8.8.8"
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (queryData.size < 12) return@withContext null

        val isPrimaryDoH = primaryDns.startsWith("https://", ignoreCase = true)

        // 1. Try DoH if specified
        if (isPrimaryDoH) {
            val dohResult = dohClient.query(primaryDns, queryData)
            if (dohResult != null) {
                logSuccess(primaryDns)
                return@withContext dohResult
            }
            // Fallback DoH: https://1.1.1.1/dns-query
            val dohFallback = dohClient.query("https://1.1.1.1/dns-query", queryData)
            if (dohFallback != null) {
                logSuccess("https://1.1.1.1/dns-query (DoH Fallback)")
                return@withContext dohFallback
            }

            // Do not silently downgrade to plaintext if user explicitly chose DoH, unless fallback is specified
            if (fallbackDns.isNotBlank() && !fallbackDns.startsWith("https://")) {
                val udpFallback = queryDo53(queryData, fallbackDns)
                if (udpFallback != null) {
                    logSuccess("$fallbackDns (Plaintext Fallback)")
                    return@withContext udpFallback
                }
            }
            return@withContext null
        }

        // 2. Standard UDP Do53 query
        val targetIp = primaryDns
        val udpResult = queryDo53(queryData, targetIp)
        if (udpResult != null) {
            logSuccess(targetIp)
            return@withContext udpResult
        }

        // 3. Fallback UDP Do53 (8.8.8.8 / 1.1.1.1)
        val fallbacks = listOf(fallbackDns, "8.8.8.8", "1.1.1.1").distinct()
        for (fb in fallbacks) {
            if (fb != targetIp && fb.isNotBlank() && !fb.startsWith("https://")) {
                val fbResult = queryDo53(queryData, fb)
                if (fbResult != null) {
                    logSuccess("$fb (Emergency Fallback)")
                    return@withContext fbResult
                }
            }
        }

        null
    }

    private fun queryDo53(queryData: ByteArray, serverIp: String): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            try { protectDatagram(socket) } catch (_: Exception) {}
            socket.soTimeout = 3000

            val inetAddress = InetAddress.getByName(serverIp)
            val packet = DatagramPacket(queryData, queryData.size, inetAddress, 53)
            socket.send(packet)

            val buffer = ByteArray(2048)
            val inPacket = DatagramPacket(buffer, buffer.size)
            socket.receive(inPacket)

            if (inPacket.length > 0) {
                val result = ByteArray(inPacket.length)
                System.arraycopy(buffer, 0, result, 0, inPacket.length)
                result
            } else null
        } catch (_: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun logSuccess(server: String) {
        val count = queryCount.incrementAndGet()
        if (count % 25 == 1) {
            XrayLogManager.appendLog("DNS query #$count resolved via $server", "DNS")
        }
    }
}
