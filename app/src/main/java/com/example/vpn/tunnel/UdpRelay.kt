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
        val sendChannel: Channel<ByteArray> = Channel(Channel.UNLIMITED),
        var writerJob: Job? = null,
        var readerJob: Job? = null,
        @Volatile var isConnected: Boolean = false
    ) : UdpSession(key, clientIp, serverIp, clientPort, serverPort) {
        override fun send(payload: ByteArray) {
            sendChannel.trySend(payload)
        }

        override fun close() {
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
        if (payloadLen <= 0 || payloadLen > packetData.size) return

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
                try { protectDatagram(socket) } catch (_: Exception) {}
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
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)

            try {
                while (isActive) {
                    session.socket.receive(packet)
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
                // Socket closed or timeout
            }
        }
    }

    private fun handleProxiedUdp(
        ipHeader: IPv4Header,
        udpHeader: UdpHeader,
        payload: ByteArray
    ) {
        if (profile == null) return

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
            rawSocket = Socket()
            try { protectSocket(rawSocket) } catch (_: Exception) {}
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
                val wsHost = if (targetProfile.host.isNotBlank()) targetProfile.host else targetProfile.sni.ifBlank { targetProfile.address }
                val wsPath = if (targetProfile.path.isNotBlank()) {
                    if (targetProfile.path.startsWith("/")) targetProfile.path else "/${targetProfile.path}"
                } else "/"
                val randomBytes = ByteArray(16).apply { java.security.SecureRandom().nextBytes(this) }
                val wsKey = java.util.Base64.getEncoder().encodeToString(randomBytes)

                val wsHandshake = buildString {
                    append("GET $wsPath HTTP/1.1\r\n")
                    append("Host: $wsHost\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $wsKey\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("User-Agent: Mozilla/5.0 (Android; Maximus)\r\n")
                    append("\r\n")
                }
                outStream.write(wsHandshake.toByteArray(Charsets.UTF_8))
                outStream.flush()

                // Read HTTP 101
                val headerLine = readHttpLine(inStream)
                if (!headerLine.contains("101")) {
                    throw IllegalStateException("WebSocket handshake failed for UDP tunnel: $headerLine")
                }
                while (true) {
                    val line = readHttpLine(inStream)
                    if (line.isEmpty() || line == "\r") break
                }
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

            session.socket = activeSocket
            session.outStream = outStream
            session.inStream = inStream
            session.isConnected = true

            startProxiedUdpPumping(session, isWs)
        } catch (e: Exception) {
            val errMsg = e.message ?: "UDP tunnel error"
            XrayLogManager.appendLog("Proxied UDP tunnel setup failed for $destIpStr:$destPort: $errMsg", "UDP")
            onTunnelError?.invoke(errMsg)
            sessions.remove(session.key)
            session.close()
        }
    }

    private fun configureTlsSocket(rawSocket: Socket, profile: VlessProfile): SSLSocket {
        val isReality = profile.security.equals("reality", ignoreCase = true)
        val sniHost = if (profile.sni.isNotBlank()) {
            profile.sni
        } else if (profile.host.isNotBlank()) {
            profile.host
        } else {
            profile.address
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
            sslContext = SSLContext.getInstance("TLSv1.3").apply {
                init(null, arrayOf<TrustManager>(realityTrustManager), java.security.SecureRandom())
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
        if (sniHost.isNotBlank()) {
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
        sslSocket.startHandshake()
        return sslSocket
    }

    private fun startProxiedUdpPumping(session: ProxiedUdpSession, isWs: Boolean) {
        val outStream = session.outStream ?: return
        val inStream = session.inStream ?: return
        val bufferedOut = java.io.BufferedOutputStream(outStream, 65536)
        val bufferedIn = java.io.BufferedInputStream(inStream, 65536)

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

                    if (isWs) {
                        bufferedOut.write(WebSocketCodec.encodeFrame(packetBuffer))
                    } else {
                        bufferedOut.write(packetBuffer)
                    }
                    if (session.sendChannel.isEmpty) {
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

        // Downstream UDP receiver
        session.readerJob = scope.launch(Dispatchers.IO) {
            try {
                var isFirst = true
                val headerBuf = ByteArray(2)

                while (isActive && session.isConnected) {
                    if (isWs) {
                        val frame = WebSocketCodec.readFrame(bufferedIn) ?: break
                        if (frame.isEmpty()) continue

                        var offset = 0
                        if (isFirst) {
                            isFirst = false
                            if (frame.size >= 2) {
                                val addonLen = frame[1].toInt() and 0xFF
                                offset = 2 + addonLen
                            }
                        }

                        while (offset + 2 <= frame.size) {
                            val chunkLen = ((frame[offset].toInt() and 0xFF) shl 8) or (frame[offset + 1].toInt() and 0xFF)
                            offset += 2
                            if (offset + chunkLen > frame.size) break
                            val udpData = ByteArray(chunkLen)
                            System.arraycopy(frame, offset, udpData, 0, chunkLen)
                            offset += chunkLen

                            session.lastActiveTime = System.currentTimeMillis()
                            onTraffic(0L, udpData.size.toLong())

                            val respIpPacket = PacketBuilder.buildUdpPacket(
                                srcIp = session.serverIp,
                                dstIp = session.clientIp,
                                srcPort = session.serverPort,
                                dstPort = session.clientPort,
                                payload = udpData
                            )
                            sendToTun(respIpPacket)
                        }
                    } else {
                        if (isFirst) {
                            isFirst = false
                            // 2-byte server response header
                            readExact(bufferedIn, headerBuf, 2)
                            val addonLen = headerBuf[1].toInt() and 0xFF
                            if (addonLen > 0) {
                                val addon = ByteArray(addonLen)
                                readExact(bufferedIn, addon, addonLen)
                            }
                        }

                        // Read 2-byte length
                        readExact(bufferedIn, headerBuf, 2)
                        val chunkLen = ((headerBuf[0].toInt() and 0xFF) shl 8) or (headerBuf[1].toInt() and 0xFF)
                        if (chunkLen <= 0 || chunkLen > 65535) break

                        val udpData = ByteArray(chunkLen)
                        readExact(bufferedIn, udpData, chunkLen)

                        session.lastActiveTime = System.currentTimeMillis()
                        onTraffic(0L, udpData.size.toLong())

                        val respIpPacket = PacketBuilder.buildUdpPacket(
                            srcIp = session.serverIp,
                            dstIp = session.clientIp,
                            srcPort = session.serverPort,
                            dstPort = session.clientPort,
                            payload = udpData
                        )
                        sendToTun(respIpPacket)
                    }
                }
            } catch (_: Exception) {
                // Stream closed
            } finally {
                sessions.remove(session.key)
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

