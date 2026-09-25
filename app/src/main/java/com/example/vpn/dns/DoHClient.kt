package com.example.vpn.dns

import com.example.xray.XrayLogManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class DoHClient(
    private val protectSocket: ((Socket) -> Boolean)? = null
) {
    private val dnsMessageMediaType = "application/dns-message".toMediaType()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .socketFactory(object : SocketFactory() {
                private val defaultFactory = SocketFactory.getDefault()

                private fun protectOrThrow(socket: Socket) {
                    try {
                        if (protectSocket?.invoke(socket) == false) throw IOException("DNS socket protection failed")
                    } catch (e: Exception) {
                        socket.close()
                        throw IOException("DNS socket protection failed", e)
                    }
                }

                override fun createSocket(): Socket {
                    val socket = defaultFactory.createSocket()
                    protectOrThrow(socket)
                    return socket
                }
                override fun createSocket(host: String?, port: Int): Socket {
                    val socket = defaultFactory.createSocket()
                    protectOrThrow(socket)
                    if (host != null) {
                        socket.connect(InetSocketAddress(host, port))
                    }
                    return socket
                }
                override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): Socket {
                    val socket = defaultFactory.createSocket()
                    if (localHost != null) {
                        socket.bind(InetSocketAddress(localHost, localPort))
                    }
                    protectOrThrow(socket)
                    if (host != null) {
                        socket.connect(InetSocketAddress(host, port))
                    }
                    return socket
                }
                override fun createSocket(host: java.net.InetAddress?, port: Int): Socket {
                    val socket = defaultFactory.createSocket()
                    protectOrThrow(socket)
                    if (host != null) {
                        socket.connect(InetSocketAddress(host, port))
                    }
                    return socket
                }
                override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): Socket {
                    val socket = defaultFactory.createSocket()
                    if (localAddress != null) {
                        socket.bind(InetSocketAddress(localAddress, localPort))
                    }
                    protectOrThrow(socket)
                    if (address != null) {
                        socket.connect(InetSocketAddress(address, port))
                    }
                    return socket
                }
            })
            .build()
    }

    /**
     * Resolves a binary DNS wire query via DNS-over-HTTPS (DoH RFC 8484).
     */
    fun query(dohUrl: String, queryData: ByteArray): ByteArray? {
        if (queryData.size < 12 || !dohUrl.startsWith("https://", ignoreCase = true)) return null

        return try {
            val requestBody = queryData.toRequestBody(dnsMessageMediaType)
            val request = Request.Builder()
                .url(dohUrl)
                .post(requestBody)
                .header("Accept", "application/dns-message")
                .header("User-Agent", "Maximus-VPN/1.0 (DoH RFC8484)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBytes = response.body?.source()?.let { source ->
                        val buffer = okio.Buffer()
                        while (buffer.size < 65536L && source.read(buffer, 65536L - buffer.size) != -1L) { }
                        buffer.readByteArray()
                    }
                    if (responseBytes != null && DnsResponse.isResponseTo(queryData, responseBytes)) {
                        responseBytes
                    } else null
                } else {
                    XrayLogManager.appendLog("DoH upstream $dohUrl returned HTTP ${response.code}", "DNS")
                    null
                }
            }
        } catch (e: Exception) {
            XrayLogManager.appendLog("DoH query failed to $dohUrl: ${e.message}", "DNS")
            null
        }
    }
}
