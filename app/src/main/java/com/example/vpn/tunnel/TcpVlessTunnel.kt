package com.example.vpn.tunnel

import com.example.data.model.AppSettings
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vless.VlessHeader
import com.example.vpn.packet.IPv4Header
import com.example.vpn.packet.IpProtocol
import com.example.vpn.packet.PacketBuilder
import com.example.vpn.packet.TcpHeader
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
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class TcpVlessTunnel(
    private val scope: CoroutineScope,
    private val profile: VlessProfile?,
    private val settings: AppSettings,
    private val protectSocket: (Socket) -> Boolean,
    private val sendToTun: (ByteArray) -> Unit,
    private val onTraffic: (sent: Long, received: Long) -> Unit,
    private val onTunnelError: ((String) -> Unit)? = null
) {

    companion object {
        const val MAX_CONCURRENT_TCP_SESSIONS = 512
        const val SESSION_IDLE_TIMEOUT_MS = 60000L
    }

    private class TcpSession(
        val key: String,
        val clientIp: ByteArray,
        val serverIp: ByteArray,
        val clientPort: Int,
        val serverPort: Int,
        var clientSeq: Long,
        var ourSeq: Long,
        var protocolType: ProtocolType = ProtocolType.VLESS,
        var isWs: Boolean = false,
        var socket: Socket? = null,
        var outStream: OutputStream? = null,
        var inStream: InputStream? = null,
        @Volatile var isConnected: Boolean = false,
        @Volatile var isClosed: Boolean = false,
        @Volatile var lastActiveTime: Long = System.currentTimeMillis(),
        val outgoingChannel: Channel<ByteArray> = Channel(Channel.UNLIMITED),
        var writerJob: Job? = null,
        var readerJob: Job? = null
    )

    private val sessions = ConcurrentHashMap<String, TcpSession>()
    private val isnGenerator = AtomicLong(100000L)
    private var cleanupJob: Job? = null

    init {
        startSessionCleanupWatcher()
    }

    fun handleTcpPacket(
        ipHeader: IPv4Header,
        tcpHeader: TcpHeader,
        packetData: ByteArray
    ) {
        val key = "${ipHeader.srcIpStr}:${tcpHeader.srcPort}->${ipHeader.dstIpStr}:${tcpHeader.dstPort}"

        if (tcpHeader.isRst) {
            closeSession(key)
            return
        }

        val decision = RoutingEngine.evaluate(
            dstIp = ipHeader.dstIp,
            dstPort = tcpHeader.dstPort,
            protocol = IpProtocol.TCP,
            settings = settings,
            isTunnelConnected = (profile != null)
        )

        if (decision == RoutingDecision.BLOCK) {
            if (tcpHeader.isSyn && !tcpHeader.isAck) {
                sendRst(ipHeader, tcpHeader)
            }
            return
        }

        if (tcpHeader.isSyn && !tcpHeader.isAck) {
            // Guard against unbounded session exhaustion / DoS
            if (sessions.size >= MAX_CONCURRENT_TCP_SESSIONS) {
                pruneOldestIdleSession()
                if (sessions.size >= MAX_CONCURRENT_TCP_SESSIONS) {
                    // Refuse connection when capacity is saturated
                    sendRst(ipHeader, tcpHeader)
                    return
                }
            }
            handleSyn(key, ipHeader, tcpHeader, decision)
            return
        }

        val session = sessions[key]
        if (session == null) {
            if (!tcpHeader.isRst && !tcpHeader.isFin) {
                sendRst(ipHeader, tcpHeader)
            }
            return
        }

        session.lastActiveTime = System.currentTimeMillis()

        if (tcpHeader.isFin) {
            handleFin(session, tcpHeader)
            return
        }

        val payloadLen = tcpHeader.payloadLength
        if (payloadLen > 0) {
            handleData(session, tcpHeader, packetData)
        }
    }

    private fun handleSyn(
        key: String,
        ipHeader: IPv4Header,
        tcpHeader: TcpHeader,
        decision: RoutingDecision
    ) {
        val clientInitialSeq = tcpHeader.sequenceNumber
        val ourInitialSeq = isnGenerator.addAndGet(20000L)

        val session = TcpSession(
            key = key,
            clientIp = ipHeader.srcIp,
            serverIp = ipHeader.dstIp,
            clientPort = tcpHeader.srcPort,
            serverPort = tcpHeader.dstPort,
            clientSeq = clientInitialSeq + 1,
            ourSeq = ourInitialSeq + 1
        )
        sessions[key] = session

        // 1. Respond with SYN + ACK
        val synAckPacket = PacketBuilder.buildTcpPacket(
            srcIp = session.serverIp,
            dstIp = session.clientIp,
            srcPort = session.serverPort,
            dstPort = session.clientPort,
            seq = ourInitialSeq,
            ack = session.clientSeq,
            flags = 0x12, // SYN | ACK
            windowSize = 65535
        )
        sendToTun(synAckPacket)

        // 2. Establish Upstream Connection asynchronously
        scope.launch(Dispatchers.IO) {
            establishUpstream(session, ipHeader.dstIpStr, tcpHeader.dstPort, decision)
        }
    }

    private fun handleData(
        session: TcpSession,
        tcpHeader: TcpHeader,
        packetData: ByteArray
    ) {
        val payloadLen = tcpHeader.payloadLength
        if (payloadLen <= 0 || payloadLen > packetData.size) return

        val payload = ByteArray(payloadLen)
        System.arraycopy(packetData, tcpHeader.payloadOffset, payload, 0, payloadLen)

        val nextExpectedSeq = tcpHeader.sequenceNumber + payloadLen
        if (nextExpectedSeq > session.clientSeq) {
            session.clientSeq = nextExpectedSeq
        }

        val ackPacket = PacketBuilder.buildTcpPacket(
            srcIp = session.serverIp,
            dstIp = session.clientIp,
            srcPort = session.serverPort,
            dstPort = session.clientPort,
            seq = session.ourSeq,
            ack = session.clientSeq,
            flags = 0x10, // ACK
            windowSize = 65535
        )
        sendToTun(ackPacket)

        session.outgoingChannel.trySend(payload)
    }

    private fun handleFin(session: TcpSession, tcpHeader: TcpHeader) {
        val nextExpectedSeq = tcpHeader.sequenceNumber + 1
        if (nextExpectedSeq > session.clientSeq) {
            session.clientSeq = nextExpectedSeq
        }

        val finAckPacket = PacketBuilder.buildTcpPacket(
            srcIp = session.serverIp,
            dstIp = session.clientIp,
            srcPort = session.serverPort,
            dstPort = session.clientPort,
            seq = session.ourSeq,
            ack = session.clientSeq,
            flags = 0x11, // FIN | ACK
            windowSize = 65535
        )
        sendToTun(finAckPacket)

        closeSession(session.key)
    }

    private fun sendRst(ipHeader: IPv4Header, tcpHeader: TcpHeader) {
        val rstPacket = PacketBuilder.buildTcpPacket(
            srcIp = ipHeader.dstIp,
            dstIp = ipHeader.srcIp,
            srcPort = tcpHeader.dstPort,
            dstPort = tcpHeader.srcPort,
            seq = if (tcpHeader.isAck) tcpHeader.ackNumber else 0L,
            ack = tcpHeader.sequenceNumber + 1,
            flags = 0x14, // RST | ACK
            windowSize = 0
        )
        sendToTun(rstPacket)
    }

    private suspend fun establishUpstream(
        session: TcpSession,
        destIpStr: String,
        destPort: Int,
        decision: RoutingDecision
    ) {
        var rawSocket: Socket? = null
        try {
            if (decision == RoutingDecision.DIRECT) {
                rawSocket = Socket()
                try { protectSocket(rawSocket) } catch (_: Exception) {}
                rawSocket.tcpNoDelay = true
                rawSocket.keepAlive = true
                try { rawSocket.receiveBufferSize = 524288 } catch (_: Exception) {}
                try { rawSocket.sendBufferSize = 524288 } catch (_: Exception) {}
                try { rawSocket.trafficClass = 0x10 } catch (_: Exception) {}
                rawSocket.soTimeout = 0
                rawSocket.connect(InetSocketAddress(destIpStr, destPort), 5000)

                session.protocolType = ProtocolType.MIXED
                session.isWs = false
                session.socket = rawSocket
                session.outStream = rawSocket.getOutputStream()
                session.inStream = rawSocket.getInputStream()
                session.isConnected = true

                startPumping(session)
                return
            }

            val targetProfile = profile ?: throw IllegalStateException("No active profile for proxy routing")

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

                val headerLine = readHttpLine(inStream)
                if (headerLine.contains("301") || headerLine.contains("302")) {
                    var location = ""
                    while (true) {
                        val line = readHttpLine(inStream)
                        if (line.isEmpty() || line == "\r") break
                        if (line.startsWith("Location:", ignoreCase = true)) {
                            location = line.substringAfter(":").trim()
                        }
                    }
                    val locInfo = if (location.isNotBlank()) " (Location: $location)" else ""
                    throw IllegalStateException("WebSocket redirect detected: $headerLine$locInfo")
                }
                if (!headerLine.contains("101")) {
                    throw IllegalStateException("WebSocket handshake failed: $headerLine")
                }
                while (true) {
                    val line = readHttpLine(inStream)
                    if (line.isEmpty() || line == "\r") break
                }
            }

            // Protocol specific request header
            when (targetProfile.protocolType) {
                ProtocolType.VLESS -> {
                    val uuidBytes = VlessHeader.uuidToBytes(targetProfile.uuid)
                    val vlessReq = VlessHeader.encodeRequest(
                        uuidBytes = uuidBytes,
                        command = VlessHeader.COMMAND_TCP,
                        destPort = destPort,
                        destAddress = destIpStr
                    )
                    if (isWs) {
                        outStream.write(WebSocketCodec.encodeFrame(vlessReq))
                    } else {
                        outStream.write(vlessReq)
                    }
                }
                ProtocolType.TROJAN -> {
                    val trojanReq = buildTrojanRequest(targetProfile.uuid, destIpStr, destPort)
                    if (isWs) {
                        outStream.write(WebSocketCodec.encodeFrame(trojanReq))
                    } else {
                        outStream.write(trojanReq)
                    }
                }
                ProtocolType.SOCKS5 -> {
                    performSocks5Handshake(inStream, outStream, destIpStr, destPort)
                }
                ProtocolType.HTTP -> {
                    val httpConnect = "CONNECT $destIpStr:$destPort HTTP/1.1\r\nHost: $destIpStr:$destPort\r\n\r\n"
                    outStream.write(httpConnect.toByteArray(Charsets.UTF_8))
                    outStream.flush()
                    val responseLine = readHttpLine(inStream)
                    if (!responseLine.contains("200")) {
                        throw IllegalStateException("HTTP CONNECT proxy failed: $responseLine")
                    }
                    while (true) {
                        val line = readHttpLine(inStream)
                        if (line.isEmpty() || line == "\r") break
                    }
                }
                else -> {
                    val uuidBytes = VlessHeader.uuidToBytes(targetProfile.uuid)
                    val req = VlessHeader.encodeRequest(uuidBytes, VlessHeader.COMMAND_TCP, destPort, destIpStr)
                    if (isWs) outStream.write(WebSocketCodec.encodeFrame(req)) else outStream.write(req)
                }
            }
            outStream.flush()

            session.protocolType = targetProfile.protocolType
            session.isWs = isWs
            session.socket = activeSocket
            session.outStream = outStream
            session.inStream = inStream
            session.isConnected = true

            startPumping(session)
        } catch (e: Exception) {
            val errMsg = e.message ?: "TCP tunnel error"
            XrayLogManager.appendLog("TCP tunnel error for $destIpStr:$destPort: $errMsg", "TCP")
            onTunnelError?.invoke(errMsg)
            val rstPacket = PacketBuilder.buildTcpPacket(
                srcIp = session.serverIp,
                dstIp = session.clientIp,
                srcPort = session.serverPort,
                dstPort = session.clientPort,
                seq = session.ourSeq,
                ack = session.clientSeq,
                flags = 0x14, // RST | ACK
                windowSize = 0
            )
            sendToTun(rstPacket)
            closeSession(session.key)
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
            // For REALITY: Strict peer certificate verification against server public key / fingerprint
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
                SSLContext.getInstance("TLS").apply { init(null, null, java.security.SecureRandom()) }
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

        // Enable TLS 1.3 & TLS 1.2
        val supportedProtocols = sslSocket.supportedProtocols.toList()
        val enabledProtocols = mutableListOf<String>()
        if (supportedProtocols.contains("TLSv1.3")) enabledProtocols.add("TLSv1.3")
        if (supportedProtocols.contains("TLSv1.2")) enabledProtocols.add("TLSv1.2")
        if (enabledProtocols.isNotEmpty()) {
            params.protocols = enabledProtocols.toTypedArray()
        }

        // Configure ALPN if requested
        if (profile.alpn.isNotBlank()) {
            val alpnList = profile.alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (alpnList.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                params.applicationProtocols = alpnList.toTypedArray()
            }
        }

        // Configure Custom Cipher Suites if provided
        if (profile.cipherSuites.isNotBlank()) {
            val suites = profile.cipherSuites.split(Regex("[:,;]")).map { it.trim() }.filter { it.isNotEmpty() }
            val supported = sslSocket.supportedCipherSuites.toSet()
            val validSuites = suites.filter { supported.contains(it) }
            if (validSuites.isNotEmpty()) {
                params.cipherSuites = validSuites.toTypedArray()
            }
        }

        sslSocket.sslParameters = params
        sslSocket.startHandshake()
        return sslSocket
    }

    private fun buildTrojanRequest(password: String, destIp: String, destPort: Int): ByteArray {
        val digest = MessageDigest.getInstance("SHA-224")
        val hashBytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        val hexHash = hashBytes.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)

        val out = java.io.ByteArrayOutputStream()
        out.write(hexHash)
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
        out.write(1) // Command: CONNECT TCP

        val ipBytes = RoutingEngine.parseIpv4(destIp)
        if (ipBytes != null) {
            out.write(1) // IPv4
            out.write(ipBytes)
        } else {
            out.write(3) // Domain
            val domainBytes = destIp.toByteArray(Charsets.UTF_8)
            out.write(domainBytes.size)
            out.write(domainBytes)
        }
        out.write((destPort shr 8) and 0xFF)
        out.write(destPort and 0xFF)
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    private fun performSocks5Handshake(
        inStream: InputStream,
        outStream: OutputStream,
        destIp: String,
        destPort: Int
    ) {
        outStream.write(byteArrayOf(0x05, 0x01, 0x00))
        outStream.flush()

        val resp = ByteArray(2)
        readExact(inStream, resp, 2)
        if (resp[0] != 0x05.toByte() || resp[1] != 0x00.toByte()) {
            throw IllegalStateException("SOCKS5 server authentication negotiation failed")
        }

        val out = java.io.ByteArrayOutputStream()
        out.write(0x05)
        out.write(0x01)
        out.write(0x00)

        val ipBytes = RoutingEngine.parseIpv4(destIp)
        if (ipBytes != null) {
            out.write(0x01)
            out.write(ipBytes)
        } else {
            out.write(0x03)
            val domainBytes = destIp.toByteArray(Charsets.UTF_8)
            out.write(domainBytes.size)
            out.write(domainBytes)
        }
        out.write((destPort shr 8) and 0xFF)
        out.write(destPort and 0xFF)

        outStream.write(out.toByteArray())
        outStream.flush()

        val replyHeader = ByteArray(4)
        readExact(inStream, replyHeader, 4)
        if (replyHeader[1] != 0x00.toByte()) {
            throw IllegalStateException("SOCKS5 connect error code: ${replyHeader[1]}")
        }
        when (replyHeader[3].toInt() and 0xFF) {
            1 -> readExact(inStream, ByteArray(4 + 2), 6)
            3 -> {
                val len = inStream.read()
                readExact(inStream, ByteArray(len + 2), len + 2)
            }
            4 -> readExact(inStream, ByteArray(16 + 2), 18)
        }
    }

    private fun readExact(stream: InputStream, buffer: ByteArray, length: Int) {
        var total = 0
        while (total < length) {
            val read = stream.read(buffer, total, length - total)
            if (read == -1) throw java.io.EOFException("Unexpected EOF from upstream proxy")
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

    private fun startPumping(session: TcpSession) {
        val outStream = session.outStream ?: return
        val inStream = session.inStream ?: return
        val bufferedOut = java.io.BufferedOutputStream(outStream, 65536)
        val bufferedIn = java.io.BufferedInputStream(inStream, 65536)

        // 1. Upstream Writer (Device -> Server / Upload stream)
        session.writerJob = scope.launch(Dispatchers.IO) {
            try {
                for (chunk in session.outgoingChannel) {
                    if (session.isClosed || !session.isConnected) break
                    if (session.isWs) {
                        val framed = WebSocketCodec.encodeFrame(chunk)
                        bufferedOut.write(framed)
                    } else {
                        bufferedOut.write(chunk)
                    }
                    if (session.outgoingChannel.isEmpty) {
                        bufferedOut.flush()
                    }
                    session.lastActiveTime = System.currentTimeMillis()
                    onTraffic(chunk.size.toLong(), 0L)
                }
                bufferedOut.flush()
            } catch (_: Exception) {
                closeSession(session.key)
            }
        }

        // 2. Upstream Reader (Server -> Device / Download stream)
        session.readerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(65536)
            val headerBuffer = java.io.ByteArrayOutputStream()
            val maxMss = (settings.mtu - 40).coerceIn(1200, 1460)

            try {
                var isFirstResponse = true

                while (isActive && session.isConnected && !session.isClosed) {
                    var dataBytes: ByteArray
                    if (session.isWs) {
                        val frame = WebSocketCodec.readFrame(bufferedIn) ?: break
                        if (frame.isEmpty()) continue
                        dataBytes = frame
                    } else {
                        val bytesRead = bufferedIn.read(buffer)
                        if (bytesRead <= 0) break
                        dataBytes = ByteArray(bytesRead)
                        System.arraycopy(buffer, 0, dataBytes, 0, bytesRead)
                    }

                    if (isFirstResponse && session.protocolType == ProtocolType.VLESS) {
                        headerBuffer.write(dataBytes)
                        val buffered = headerBuffer.toByteArray()
                        if (buffered.size < 2) {
                            continue
                        }
                        val addonLen = buffered[1].toInt() and 0xFF
                        val targetHeaderLen = 2 + addonLen
                        if (buffered.size < targetHeaderLen) {
                            continue
                        }
                        isFirstResponse = false
                        val extraBytes = buffered.size - targetHeaderLen
                        if (extraBytes <= 0) {
                            continue
                        }
                        val payloadOnly = ByteArray(extraBytes)
                        System.arraycopy(buffered, targetHeaderLen, payloadOnly, 0, extraBytes)
                        dataBytes = payloadOnly
                    }

                    val validLen = dataBytes.size
                    if (validLen <= 0) continue

                    session.lastActiveTime = System.currentTimeMillis()
                    onTraffic(0L, validLen.toLong())

                    var chunkOffset = 0
                    while (chunkOffset < dataBytes.size && isActive && !session.isClosed) {
                        val chunkSize = (dataBytes.size - chunkOffset).coerceAtMost(maxMss)
                        val chunk = ByteArray(chunkSize)
                        System.arraycopy(dataBytes, chunkOffset, chunk, 0, chunkSize)

                        val dataPacket = PacketBuilder.buildTcpPacket(
                            srcIp = session.serverIp,
                            dstIp = session.clientIp,
                            srcPort = session.serverPort,
                            dstPort = session.clientPort,
                            seq = session.ourSeq,
                            ack = session.clientSeq,
                            flags = 0x18, // PSH | ACK
                            windowSize = 65535,
                            payload = chunk
                        )

                        session.ourSeq += chunkSize
                        chunkOffset += chunkSize
                        sendToTun(dataPacket)
                    }
                }
            } catch (_: Exception) {
                // Closed
            } finally {
                if (!session.isClosed && session.isConnected && scope.isActive) {
                    val finPacket = PacketBuilder.buildTcpPacket(
                        srcIp = session.serverIp,
                        dstIp = session.clientIp,
                        srcPort = session.serverPort,
                        dstPort = session.clientPort,
                        seq = session.ourSeq,
                        ack = session.clientSeq,
                        flags = 0x11, // FIN | ACK
                        windowSize = 65535
                    )
                    sendToTun(finPacket)
                }
                closeSession(session.key)
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
        oldestKey?.let { closeSession(it) }
    }

    private fun closeSession(key: String) {
        val session = sessions.remove(key) ?: return
        session.isClosed = true
        session.isConnected = false
        session.outgoingChannel.close()
        session.writerJob?.cancel()
        session.readerJob?.cancel()
        try { session.outStream?.close() } catch (_: Exception) {}
        try { session.inStream?.close() } catch (_: Exception) {}
        try { session.socket?.close() } catch (_: Exception) {}
    }

    private fun startSessionCleanupWatcher() {
        cleanupJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(20000)
                val now = System.currentTimeMillis()
                val iterator = sessions.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (now - entry.value.lastActiveTime > SESSION_IDLE_TIMEOUT_MS) {
                        entry.value.isClosed = true
                        entry.value.isConnected = false
                        entry.value.outgoingChannel.close()
                        entry.value.writerJob?.cancel()
                        entry.value.readerJob?.cancel()
                        try { entry.value.socket?.close() } catch (_: Exception) {}
                        iterator.remove()
                    }
                }
            }
        }
    }

    fun closeAll() {
        cleanupJob?.cancel()
        val keys = sessions.keys().toList()
        for (k in keys) {
            closeSession(k)
        }
    }
}

object WebSocketCodec {
    private val random = java.util.Random()

    fun encodeFrame(payload: ByteArray): ByteArray {
        val maskKey = ByteArray(4)
        random.nextBytes(maskKey)
        val len = payload.size
        val out = java.io.ByteArrayOutputStream(len + 14)

        out.write(0x82) // FIN + Binary Frame

        when {
            len <= 125 -> {
                out.write(0x80 or len)
            }
            len <= 65535 -> {
                out.write(0x80 or 126)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(0x80 or 127)
                for (i in 7 downTo 0) {
                    out.write(((len.toLong() shr (i * 8)) and 0xFF).toInt())
                }
            }
        }

        out.write(maskKey)

        val masked = ByteArray(len)
        for (i in 0 until len) {
            masked[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
        }
        out.write(masked)
        return out.toByteArray()
    }

    fun readFrame(inStream: InputStream): ByteArray? {
        val b0 = inStream.read()
        if (b0 == -1) return null
        val opcode = b0 and 0x0F
        val b1 = inStream.read()
        if (b1 == -1) return null
        val masked = (b1 and 0x80) != 0
        var payloadLen = (b1 and 0x7F).toLong()

        if (payloadLen == 126L) {
            val len0 = inStream.read()
            val len1 = inStream.read()
            if (len0 == -1 || len1 == -1) return null
            payloadLen = (((len0 and 0xFF) shl 8) or (len1 and 0xFF)).toLong()
        } else if (payloadLen == 127L) {
            payloadLen = 0L
            for (i in 0 until 8) {
                val b = inStream.read()
                if (b == -1) return null
                payloadLen = (payloadLen shl 8) or ((b and 0xFF).toLong())
            }
        }

        val maskKey = if (masked) {
            val k = ByteArray(4)
            var read = 0
            while (read < 4) {
                val r = inStream.read(k, read, 4 - read)
                if (r == -1) return null
                read += r
            }
            k
        } else null

        if (opcode == 0x08) return null // Close frame
        if (opcode == 0x09 || opcode == 0x0A) {
            // Ping or Pong
            var skipped = 0L
            while (skipped < payloadLen) {
                val s = inStream.skip(payloadLen - skipped)
                if (s <= 0) break
                skipped += s
            }
            return ByteArray(0)
        }

        if (payloadLen > 10 * 1024 * 1024) return null

        val payload = ByteArray(payloadLen.toInt())
        var totalRead = 0
        while (totalRead < payloadLen.toInt()) {
            val r = inStream.read(payload, totalRead, payloadLen.toInt() - totalRead)
            if (r == -1) return null
            totalRead += r
        }

        if (maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }

        return payload
    }
}
