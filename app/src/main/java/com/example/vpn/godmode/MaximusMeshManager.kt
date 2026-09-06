package com.example.vpn.godmode

import com.example.data.model.EngineType
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.ServerCategory
import com.example.data.model.VlessProfile
import com.example.xray.XrayLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GOD MODE: Dedicated Maximus P2P Mesh Network.
 *
 * Discovers nearby Maximus nodes across WiFi, hotspot, and intranet broadcast channels
 * to maintain peer-to-peer data relays when upstream internet is severed.
 */
object MaximusMeshManager {

    private const val MESH_PORT = 19842
    private const val BROADCAST_MAGIC = "MAXIMUS_MESH_V1"

    data class PeerNode(
        val peerId: String,
        val ipAddress: String,
        val port: Int,
        val lastSeen: Long = System.currentTimeMillis(),
        val latencyMs: Long = 15L,
        val relayCapacityMbps: Double = 10.0,
        val isRelayAvailable: Boolean = true
    )

    private val isMeshRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var meshJob: Job? = null

    private val localPeerId = "PEER-" + UUID.randomUUID().toString().substring(0, 8).uppercase()
    private val activePeers = ConcurrentHashMap<String, PeerNode>()

    private val _peersFlow = MutableStateFlow<List<PeerNode>>(emptyList())
    val peersFlow: StateFlow<List<PeerNode>> = _peersFlow.asStateFlow()

    private val _activeRelayFlow = MutableStateFlow<PeerNode?>(null)
    val activeRelayFlow: StateFlow<PeerNode?> = _activeRelayFlow.asStateFlow()

    fun startMesh() {
        if (isMeshRunning.getAndSet(true)) return

        XrayLogManager.appendLog("GOD MODE: Starting Maximus P2P Decentralized Mesh...", "MESH")

        meshJob = scope.launch {
            launch { broadcastAnnouncements() }
            launch { listenForAnnouncements() }
            launch { pruneStalePeers() }
        }
    }

    fun stopMesh() {
        isMeshRunning.set(false)
        meshJob?.cancel()
        meshJob = null
        activePeers.clear()
        _peersFlow.value = emptyList()
        _activeRelayFlow.value = null
        XrayLogManager.appendLog("GOD MODE: Maximus Mesh stopped.", "MESH")
    }

    fun isRunning(): Boolean = isMeshRunning.get()

    fun getLocalPeerId(): String = localPeerId

    fun getBestRelayPeer(): PeerNode? {
        return activePeers.values
            .filter { it.isRelayAvailable }
            .minByOrNull { it.latencyMs }
    }

    private suspend fun broadcastAnnouncements() {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true

            while (scope.isActive && isMeshRunning.get()) {
                try {
                    val message = "$BROADCAST_MAGIC:$localPeerId:10808"
                    val bytes = message.toByteArray(Charsets.UTF_8)
                    val packet = DatagramPacket(
                        bytes,
                        bytes.size,
                        InetAddress.getByName("255.255.255.255"),
                        MESH_PORT
                    )
                    socket.send(packet)
                } catch (_: Exception) {}

                delay(5000)
            }
        } catch (_: Exception) {
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private suspend fun listenForAnnouncements() {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(MESH_PORT)
            socket.broadcast = true
            val buffer = ByteArray(1024)

            while (scope.isActive && isMeshRunning.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val rawMsg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (rawMsg.startsWith(BROADCAST_MAGIC)) {
                        val parts = rawMsg.split(":")
                        if (parts.size >= 3) {
                            val peerId = parts[1]
                            val port = parts[2].toIntOrNull() ?: 10808
                            val senderIp = packet.address.hostAddress ?: ""

                            if (peerId != localPeerId && senderIp.isNotBlank()) {
                                val node = PeerNode(
                                    peerId = peerId,
                                    ipAddress = senderIp,
                                    port = port,
                                    lastSeen = System.currentTimeMillis()
                                )
                                activePeers[peerId] = node
                                val peersList = activePeers.values.toList()
                                _peersFlow.value = peersList
                                if (_activeRelayFlow.value == null) {
                                    _activeRelayFlow.value = node
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private suspend fun pruneStalePeers() {
        while (scope.isActive && isMeshRunning.get()) {
            delay(10000)
            val now = System.currentTimeMillis()
            val removed = activePeers.entries.removeIf { now - it.value.lastSeen > 25000 }
            if (removed) {
                val peersList = activePeers.values.toList()
                _peersFlow.value = peersList
                _activeRelayFlow.value = getBestRelayPeer()
            }
        }
    }

    /**
     * Converts a local mesh peer node into an executable VlessProfile (SOCKS5/HTTP peer relay).
     */
    fun asVlessProfile(peer: PeerNode): VlessProfile {
        return VlessProfile(
            id = "mesh-" + peer.peerId,
            name = "📡 Mesh Peer (${peer.peerId})",
            address = peer.ipAddress,
            port = peer.port,
            uuid = UUID.randomUUID().toString(),
            encryption = "none",
            transport = "tcp",
            security = "none",
            isFavorite = true,
            lastLatencyMs = peer.latencyMs,
            overallScore = 88.0,
            category = ServerCategory.FAST,
            profileType = ProfileType.VLESS,
            protocolType = ProtocolType.SOCKS5,
            engineType = EngineType.XRAY
        )
    }
}
