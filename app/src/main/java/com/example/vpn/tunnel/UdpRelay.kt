package com.example.vpn.tunnel

import com.example.data.model.AppSettings
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vless.VlessHeader
import com.example.vpn.packet.IPv4Header
import com.example.vpn.packet.IpProtocol
import com.example.vpn.packet.PacketBuilder
import com.example.vpn.packet.UdpHeader
import com.example.vpn.routing.RoutingDecision
import com.example.vpn.routing.RoutingEngine
import com.example.xray.XrayLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class UdpRelay(
    private val scope: CoroutineScope,
    private val profile: VlessProfile?,
    private val settings: AppSettings,
    private val protectSocket: (Socket) -> Boolean,
    private val protectDatagram: (DatagramSocket) -> Boolean,
    private val sendToTun: (ByteArray) -> Unit,
    private val onTraffic: (sent: Long, received: Long) -> Unit,
    private val onTunnelError: ((String) -> Unit)? = null
) {

    companion object {
        const val MAX_CONCURRENT_UDP_SESSIONS = 512
        const val UDP_IDLE_TIMEOUT_MS = 60000L
    }

    private sealed class UdpSession(
        val key: String,
        val clientIp: ByteArray,
        val serverIp: ByteArray,
        val clientPort: Int,
        val serverPort: Int,
        @Volatile var lastActiveTime: Long = System.currentTimeMillis()
    ) {
        abstract fun send(payload: ByteArray)
        abstract fun close()
    }

    private class DirectUdpSession(
        key: String,
        clientIp: ByteArray,
        serverIp: ByteArray,
        clientPort: Int,
        serverPort: Int,
        val socket: DatagramSocket,
        var listenJob: Job? = null
    ) : UdpSession(key, clientIp, serverIp, clientPort, serverPort) {
        override fun send(payload: ByteArray) {
            try {
                val targetAddr = InetAddress.getByAddress(serverIp)
                val packet = DatagramPacket(payload, payload.size, targetAddr, serverPort)
                socket.send(packet)
            } catch (_: Exception) {}
        }

        override fun close() {
            listenJob?.cancel()
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private class ProxiedUdpSession(
        key: String,
        clientIp: ByteArray,
        serverIp: ByteArray,
        clientPort: Int,
        serverPort: Int,
        var socket: Socket? = null,
        var outStream: OutputStream? = null,
        var inStream: InputStream? = null,
        val sendChannel: Channel<ByteArray> = Channel(64),
        var writerJob: Job? = null,
        var readerJob: Job? = null,
        @Volatile var isClosed: Boolean = false,
        @Volatile var isConnected: Boolean = false
    ) : UdpSession(key, clientIp, serverIp, clientPort, serverPort) {
        override fun send(payload: ByteArray) {
            sendChannel.trySend(payload)
        }

        override fun close() {
            isClosed = true
            isConnected = false
            sendChannel.close()
            writerJob?.cancel()
            readerJob?.cancel()
            try { outStream?.close() } catch (_: Exception) {}
            try { inStream?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private val sessions = ConcurrentHashMap<String, UdpSession>()
    private var cleanupJob: Job? = null

    init {
        cleanupJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(15000)
                val now = System.currentTimeMillis()
                val iterator = sessions.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (now - entry.value.lastActiveTime > UDP_IDLE_TIMEOUT_MS) {
                        entry.value.close()
                        iterator.remove()
                    }
                }
            }
        }
    }

    fun handleUdpPacket(
        ipHeader: IPv4Header,
        udpHeader: UdpHeader,
        packetData: ByteArray
    ) {
        val payloadLen = udpHeader.payloadLength
        if (payloadLen <= 0 || udpHeader.payloadOffset < 0 || udpHeader.payloadOffset > packetData.size - payloadLen) return

        val payload = ByteArray(payloadLen)
        System.arraycopy(packetData, udpHeader.payloadOffset, payload, 0, payloadLen)

        val decision = RoutingEngine.evaluate(
            dstIp = ipHeader.dstIp,
            dstPort = udpHeader.dstPort,
            protocol = IpProtocol.UDP,
            settings = settings,
            isTunnelConnected = (profile != null)
        )

        when (decision) {
            RoutingDecision.BLOCK -> {
                // Drop packet to prevent leaks
                return
            }
            RoutingDecision.DIRECT -> {
                handleDirectUdp(ipHeader, udpHeader, payload)
            }
            RoutingDecision.PROXY -> {
                handleProxiedUdp(ipHeader, udpHeader, payload)
            }
        }
    }

    private fun handleDirectUdp(
        ipHeader: IPv4Header,
        udpHeader: UdpHeader,
        payload: ByteArray
    ) {
        val key = "direct:${ipHeader.srcIpStr}:${udpHeader.srcPort}->${ipHeader.dstIpStr}:${udpHeader.dstPort}"
        var session = sessions[key] as? DirectUdpSession

        if (session == null) {
            if (sessions.size >= MAX_CONCURRENT_UDP_SESSIONS) {
                pruneOldestIdleSession()
            }
            try {
                val socket = DatagramSocket()
                if (!protectDatagram(socket)) { socket.close(); error("UDP socket protection failed") }
                socket.connect(InetAddress.getByAddress(ipHeader.dstIp), udpHeader.dstPort)
                socket.soTimeout = 10000

                val newSession = DirectUdpSession(
                    key = key,
                    clientIp = ipHeader.srcIp,
                    serverIp = ipHeader.dstIp,
                    clientPort = udpHeader.srcPort,
                    serverPort = udpHeader.dstPort,
                    socket = socket
                )
                sessions[key] = newSession
                session = newSession
                startDirectListening(newSession)
            } catch (_: Exception) {
                return
            }
        }

        session.lastActiveTime = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            session.send(payload)
            onTraffic(payload.size.toLong(), 0L)
        }
    }

    private fun startDirectListening(session: DirectUdpSession) {
        session.listenJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(65507)
            val packet = DatagramPacket(buffer, buffer.size)

            try {
                while (isActive) {
                    packet.length = buffer.size
                    try { session.socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { continue }
                    val len = packet.length
                    if (len > 0) {
                        session.lastActiveTime = System.currentTimeMillis()
                        onTraffic(0L, len.toLong())

                        val responseData = ByteArray(len)
                        System.arraycopy(buffer, 0, responseData, 0, len)

                        val respIpPacket = PacketBuilder.buildUdpPacket(
                            srcIp = session.serverIp,
                            dstIp = session.clientIp,
                            srcPort = session.serverPort,
                            dstPort = session.clientPort,
                            payload = responseData
                        )
                        sendToTun(respIpPacket)
                    }
                }
            } catch (_: Exception) {
                // Socket closed.
            } finally {
                sessions.remove(session.key, session)
                session.close()
            }
        }
    }

    private fun handleProxiedUdp(
        ipHeader: IPv4Header,
        udpHeader: UdpHeader,
        payload: ByteArray
    ) {
        if (profile == null || profile.protocolType != ProtocolType.VLESS) return

        val key = "proxy:${ipHeader.srcIpStr}:${udpHeader.srcPort}->${ipHeader.dstIpStr}:${udpHeader.dstPort}"
        var session = sessions[key] as? ProxiedUdpSession

        if (session == null) {
            if (sessions.size >= MAX_CONCURRENT_UDP_SESSIONS) {
                pruneOldestIdleSession()
            }
            val newSession = ProxiedUdpSession(
                key = key,
                clientIp = ipHeader.srcIp,
                serverIp = ipHeader.dstIp,
                clientPort = udpHeader.srcPort,
                serverPort = udpHeader.dstPort
            )
            sessions[key] = newSession
            session = newSession
            scope.launch(Dispatchers.IO) {
                establishProxiedUdp(newSession, ipHeader.dstIpStr, udpHeader.dstPort)
            }
        }

        session.lastActiveTime = System.currentTimeMillis()
        session.send(payload)
    }

    private suspend fun establishProxiedUdp(
        session: ProxiedUdpSession,
        destIpStr: String,
        destPort: Int
    ) {
        val targetProfile = profile ?: return
        var rawSocket: Socket? = null

        try {
            if (session.isClosed) return
            rawSocket = Socket()
            session.socket = rawSocket
            check(protectSocket(rawSocket)) { "VPN socket protection failed" }
            rawSocket.tcpNoDelay = true
            rawSocket.keepAlive = true
            try { rawSocket.receiveBufferSize = 524288 } catch (_: Exception) {}
            try { rawSocket.sendBufferSize = 524288 } catch (_: Exception) {}
            try { rawSocket.trafficClass = 0x10 } catch (_: Exception) {}
            rawSocket.soTimeout = 0
            rawSocket.connect(InetSocketAddress(targetProfile.address, targetProfile.port), 10000)

            var activeSocket = rawSocket
            val isReality = targetProfile.security.equals("reality", ignoreCase = true)
            val isTls = targetProfile.security.equals("tls", ignoreCase = true) || isReality

            if (isTls) {
                activeSocket = configureTlsSocket(rawSocket, targetProfile)
            }

            val outStream = activeSocket.getOutputStream()
            val inStream = activeSocket.getInputStream()

            val isWs = targetProfile.transport.equals("ws", ignoreCase = true)
            if (isWs) {
                WebSocketHandshake.perform(activeSocket, targetProfile)
            }

            // Send VLESS UDP Request Header
            val uuidBytes = VlessHeader.uuidToBytes(targetProfile.uuid)
            val vlessReq = VlessHeader.encodeRequest(
                uuidBytes = uuidBytes,
                command = VlessHeader.COMMAND_UDP,
                destPort = destPort,
                destAddress = destIpStr
            )

            if (isWs) {
                outStream.write(WebSocketCodec.encodeFrame(vlessReq))
            } else {
                outStream.write(vlessReq)
            }
            outStream.flush()

            if (session.isClosed) { activeSocket.close(); return }
            session.socket = activeSocket
            session.outStream = outStream
            session.inStream = inStream
            session.isConnected = true

            startProxiedUdpPumping(session, isWs)
        } catch (e: Exception) {
            try { rawSocket?.close() } catch (_: Exception) {}
            if (session.isClosed) return
            val errMsg = e.message ?: "UDP tunnel error"
            XrayLogManager.appendLog("Proxied UDP tunnel setup failed for $destIpStr:$destPort: $errMsg", "UDP")
            onTunnelError?.invoke(errMsg)
            sessions.remove(session.key)
            session.close()
        }
    }

    private fun configureTlsSocket(rawSocket: Socket, profile: VlessProfile): SSLSocket {
        val isReality = profile.security.equals("reality", ignoreCase = true)
        if (isReality && profile.publicKey.isBlank()) {
            throw javax.net.ssl.SSLException("REALITY Security Failure: Public key (pbk) is missing for REALITY profile '${profile.name}'")
        }

        val sniHost = if (profile.sni.isNotBlank()) {
            profile.sni
        } else if (profile.host.isNotBlank()) {
            profile.host
        } else {
            profile.address
        }

        val isUnsafe = profile.fingerprint.equals("unsafe", ignoreCase = true)
        if (isUnsafe && !com.example.BuildConfig.DEBUG) {
            throw javax.net.ssl.SSLException("Unsafe TLS is disabled in release builds")
        }
        val sslContext: SSLContext
        if (isReality) {
            val realityTrustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    RealityVerifier.verifyRealityPeer(chain, profile.publicKey)
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            sslContext = try {
                SSLContext.getInstance("TLSv1.3").apply {
                    init(null, arrayOf<TrustManager>(realityTrustManager), java.security.SecureRandom())
                }
            } catch (_: Exception) {
                SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf<TrustManager>(realityTrustManager), java.security.SecureRandom())
                }
            }
        } else if (isUnsafe) {
            val trustAllManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            sslContext = try {
                SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf<TrustManager>(trustAllManager), java.security.SecureRandom())
                }
            } catch (_: Exception) {
                SSLContext.getDefault()
            }
        } else {
            sslContext = try {
                SSLContext.getInstance("TLSv1.3").apply { init(null, null, java.security.SecureRandom()) }
            } catch (_: Exception) {
                SSLContext.getDefault()
            }
        }

        val sslSocket = sslContext.socketFactory.createSocket(
            rawSocket,
            sniHost,
            profile.port,
            true
        ) as SSLSocket

        val params = SSLParameters()
        if (!isReality && !isUnsafe) params.endpointIdentificationAlgorithm = "HTTPS"
        if (sniHost.isNotBlank() && !sniHost.contains(':') && !sniHost.matches(Regex("[0-9.]+"))) {
            params.serverNames = listOf(javax.net.ssl.SNIHostName(sniHost))
        }
        val supportedProtocols = sslSocket.supportedProtocols.toList()
        val enabledProtocols = mutableListOf<String>()
        if (supportedProtocols.contains("TLSv1.3")) enabledProtocols.add("TLSv1.3")
        if (supportedProtocols.contains("TLSv1.2")) enabledProtocols.add("TLSv1.2")
        if (enabledProtocols.isNotEmpty()) {
            params.protocols = enabledProtocols.toTypedArray()
        }

        sslSocket.sslParameters = params
        sslSocket.soTimeout = 10000
        sslSocket.startHandshake()
        sslSocket.soTimeout = 0
        return sslSocket
    }

    private fun startProxiedUdpPumping(session: ProxiedUdpSession, isWs: Boolean) {
        val outStream = session.outStream ?: return
        val inStream = session.inStream ?: return
        val bufferedOut = java.io.BufferedOutputStream(outStream, 65536)
        val bufferedIn = java.io.BufferedInputStream(inStream, 65536)

        val writeLock = Any()

        // Upstream UDP sender
        session.writerJob = scope.launch(Dispatchers.IO) {
            try {
                for (payload in session.sendChannel) {
                    if (!session.isConnected) break
                    // VLESS UDP format: [2-byte Length] + [Payload]
                    val len = payload.size
                    val packetBuffer = ByteArray(2 + len)
                    packetBuffer[0] = ((len shr 8) and 0xFF).toByte()
                    packetBuffer[1] = (len and 0xFF).toByte()
                    System.arraycopy(payload, 0, packetBuffer, 2, len)

                    synchronized(writeLock) {
                        if (isWs) bufferedOut.write(WebSocketCodec.encodeFrame(packetBuffer))
                        else bufferedOut.write(packetBuffer)
                        bufferedOut.flush()
                    }
                    session.lastActiveTime = System.currentTimeMillis()
                    onTraffic(payload.size.toLong(), 0L)
                }
                bufferedOut.flush()
            } catch (_: Exception) {
                sessions.remove(session.key)
                session.close()
            }
        }

        // Decode the VLESS stream across arbitrary WebSocket frame boundaries.
        session.readerJob = scope.launch(Dispatchers.IO) {
            try {
                val input = if (isWs) WebSocketInputStream(bufferedIn) { ping ->
                    synchronized(writeLock) {
                        bufferedOut.write(WebSocketCodec.encodeFrame(ping, 0xA))
                        bufferedOut.flush()
                    }
                } else bufferedIn
                val header = ByteArray(2)
                readExact(input, header, 2)
                check(header[0] == 0.toByte()) { "Invalid VLESS response version" }
                val addons = header[1].toInt() and 255
                readExact(input, ByteArray(addons), addons)
                while (isActive && session.isConnected) {
                    readExact(input, header, 2)
                    val length = ((header[0].toInt() and 255) shl 8) or (header[1].toInt() and 255)
                    check(length <= 65507) { "UDP datagram exceeds IPv4 payload limit" }
                    val payload = ByteArray(length)
                    readExact(input, payload, length)
                    session.lastActiveTime = System.currentTimeMillis()
                    onTraffic(0L, payload.size.toLong())
                    sendToTun(PacketBuilder.buildUdpPacket(session.serverIp, session.clientIp,
                        session.serverPort, session.clientPort, payload))
                }
            } catch (_: Exception) {
                // Stream closed or invalid framing.
            } finally {
                sessions.remove(session.key, session)
                session.close()
            }
        }
    }

    private fun pruneOldestIdleSession() {
        var oldestKey: String? = null
        var oldestTime = Long.MAX_VALUE
        for ((k, s) in sessions) {
            if (s.lastActiveTime < oldestTime) {
                oldestTime = s.lastActiveTime
                oldestKey = k
            }
        }
        oldestKey?.let { sessions.remove(it)?.close() }
    }

    private fun readExact(stream: InputStream, buffer: ByteArray, length: Int) {
        var total = 0
        while (total < length) {
            val read = stream.read(buffer, total, length - total)
            if (read == -1) throw java.io.EOFException("Unexpected EOF reading proxied UDP packet")
            total += read
        }
    }

    private fun readHttpLine(inputStream: InputStream): String {
        val sb = StringBuilder()
        var c: Int
        while (inputStream.read().also { c = it } != -1) {
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 2048) break
        }
        return sb.toString()
    }

    fun closeAll() {
        cleanupJob?.cancel()
        val keys = sessions.keys().toList()
        for (k in keys) {
            sessions.remove(k)?.close()
        }
    }
}
