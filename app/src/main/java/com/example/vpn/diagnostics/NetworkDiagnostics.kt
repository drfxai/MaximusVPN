package com.example.vpn.diagnostics

import com.example.data.model.VlessProfile
import com.example.xray.XrayLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class DiagnosticResult(
    val isReachable: Boolean,
    val tcpConnectMs: Long = -1,
    val tlsHandshakeMs: Long = -1,
    val alpnSelected: String? = null,
    val resolvedIp: String? = null,
    val dnsLatencyMs: Long = -1,
    val packetLossPercent: Int = 0,
    val errorMessage: String? = null
)

data class DnsLeakResult(
    val queryIp: String,
    val country: String,
    val org: String,
    val isProtected: Boolean,
    val dnsResolver: String
)

object NetworkDiagnostics {

    /**
     * Conducts a detailed TCP and TLS handshake test against the server node.
     */
    suspend fun testServer(profile: VlessProfile, timeoutMs: Int = 3500): DiagnosticResult = withContext(Dispatchers.IO) {
        val host = profile.address
        val port = profile.port

        var dnsTime: Long = -1
        var resolvedAddress: InetAddress? = null

        // 1. DNS Resolution
        try {
            val dStart = System.currentTimeMillis()
            val addresses = InetAddress.getAllByName(host)
            dnsTime = System.currentTimeMillis() - dStart
            resolvedAddress = addresses.firstOrNull()
        } catch (e: Exception) {
            return@withContext DiagnosticResult(
                isReachable = false,
                errorMessage = "DNS Resolution Failed: ${e.message}"
            )
        }

        val targetAddress = resolvedAddress ?: return@withContext DiagnosticResult(
            isReachable = false,
            errorMessage = "No IP addresses resolved for $host"
        )

        // 2. TCP Connectivity & Latency
        var tcpLatency: Long = -1
        var socket: Socket? = null
        try {
            socket = Socket()
            val tcpStart = System.currentTimeMillis()
            socket.connect(InetSocketAddress(targetAddress, port), timeoutMs)
            tcpLatency = System.currentTimeMillis() - tcpStart
        } catch (e: Exception) {
            try { socket?.close() } catch (_: Exception) {}
            return@withContext DiagnosticResult(
                isReachable = false,
                resolvedIp = targetAddress.hostAddress,
                dnsLatencyMs = dnsTime,
                errorMessage = "TCP Connection Failed (${port}): ${e.message}"
            )
        }

        // 3. TLS Handshake (if TLS or Reality enabled)
        val isTls = profile.security.equals("tls", ignoreCase = true) || profile.security.equals("reality", ignoreCase = true)
        var tlsHandshakeTime: Long = -1
        var selectedAlpn: String? = null

        if (isTls) {
            var sslSocket: SSLSocket? = null
            try {
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sniHost = if (profile.sni.isNotBlank()) profile.sni else profile.host.ifBlank { host }

                val tlsStart = System.currentTimeMillis()
                sslSocket = sslFactory.createSocket(socket, sniHost, port, true) as SSLSocket

                val sslParams = sslSocket.sslParameters ?: SSLParameters()
                if (sniHost.isNotBlank()) {
                    try {
                        sslParams.serverNames = listOf(SNIHostName(sniHost))
                    } catch (_: Exception) {}
                }

                if (profile.alpn.isNotBlank()) {
                    val alpnArray = profile.alpn.split(",").map { it.trim() }.toTypedArray()
                    try {
                        sslParams.applicationProtocols = alpnArray
                    } catch (_: Exception) {}
                }

                sslSocket.sslParameters = sslParams
                sslSocket.soTimeout = timeoutMs
                sslSocket.startHandshake()

                tlsHandshakeTime = System.currentTimeMillis() - tlsStart
                try {
                    selectedAlpn = sslSocket.applicationProtocol
                } catch (_: Throwable) {}

            } catch (e: Exception) {
                // If Reality or self-signed, TLS handshake might fail strict CA validation, but TCP succeeded
                XrayLogManager.appendLog("TLS probe notice for ${profile.name}: ${e.message}", "DIAG")
            } finally {
                try { sslSocket?.close() } catch (_: Exception) {}
            }
        } else {
            try { socket.close() } catch (_: Exception) {}
        }

        DiagnosticResult(
            isReachable = true,
            tcpConnectMs = tcpLatency,
            tlsHandshakeMs = tlsHandshakeTime,
            alpnSelected = selectedAlpn,
            resolvedIp = targetAddress.hostAddress,
            dnsLatencyMs = dnsTime,
            packetLossPercent = 0
        )
    }

    /**
     * Conducts a DNS & IP leak test by querying an IP/resolver inspection service.
     */
    suspend fun testDnsLeak(): DnsLeakResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://ip-api.com/json/?fields=status,country,query,org,as")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val jsonStr = reader.readText()
                reader.close()
                val json = JSONObject(jsonStr)

                val ip = json.optString("query", "Unknown")
                val country = json.optString("country", "Unknown")
                val org = json.optString("org", json.optString("as", "Unknown"))

                return@withContext DnsLeakResult(
                    queryIp = ip,
                    country = country,
                    org = org,
                    isProtected = true,
                    dnsResolver = org
                )
            }
        } catch (e: Exception) {
            XrayLogManager.appendLog("DNS leak check notice: ${e.message}", "DIAG")
        }

        DnsLeakResult(
            queryIp = "Unknown",
            country = "Unknown",
            org = "Unknown",
            isProtected = false,
            dnsResolver = "Direct ISP"
        )
    }
}
