package com.example.vpn

import android.os.ParcelFileDescriptor
import com.example.data.model.AppSettings
import com.example.data.model.VlessProfile
import com.example.vpn.packet.IPv4Header
import com.example.vpn.packet.IpProtocol
import com.example.vpn.packet.TcpHeader
import com.example.vpn.packet.UdpHeader
import com.example.vpn.tunnel.DnsRelay
import com.example.vpn.tunnel.IcmpHandler
import com.example.vpn.tunnel.TcpVlessTunnel
import com.example.vpn.tunnel.UdpRelay
import com.example.xray.XrayLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.Socket
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TunnelManager(
    private val vpnInterface: ParcelFileDescriptor,
    private val profile: VlessProfile?,
    private val settings: AppSettings,
    private val protectSocket: (Socket) -> Boolean,
    private val protectDatagram: (DatagramSocket) -> Boolean,
    private val onTraffic: (sent: Long, received: Long) -> Unit,
    private val onFatalTunnelFailure: (Throwable) -> Unit,
    private val onTunnelError: ((String) -> Unit)? = null
) {

    private val isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tunnelJob: Job? = null

    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null
    private val outputLock = Any()
    private val consecutiveWriteErrors = AtomicInteger(0)

    private var dnsRelay: DnsRelay? = null
    private var icmpHandler: IcmpHandler? = null
    private var tcpTunnel: TcpVlessTunnel? = null
    private var udpRelay: UdpRelay? = null

    fun start() {
        if (isRunning.getAndSet(true)) return

        XrayLogManager.appendLog("Starting TUN device I/O handler with MTU ${settings.mtu}...", "TUNNEL")

        val fileDescriptor = vpnInterface.fileDescriptor
        val inStream = FileInputStream(fileDescriptor)
        inputStream = inStream
        val outStream = FileOutputStream(fileDescriptor)
        outputStream = outStream
        consecutiveWriteErrors.set(0)

        val sendToTunFunc: (ByteArray) -> Unit = send@{ packet ->
            if (!isRunning.get()) return@send
            try {
                synchronized(outputLock) {
                    if (!isRunning.get()) return@synchronized
                    val out = outputStream
                    if (out != null) {
                        out.write(packet)
                        consecutiveWriteErrors.set(0)
                    }
                }
            } catch (e: Exception) {
                if (!isRunning.get()) return@send
                val errors = consecutiveWriteErrors.incrementAndGet()
                XrayLogManager.e("TUNNEL", "Failed packet delivery to TUN output stream (consecutive error #$errors): ${e.message}", e)
                if (errors >= 10 || e is IOException) {
                    if (isRunning.get()) {
                        XrayLogManager.e("TUNNEL", "Critical TUN write failure threshold exceeded ($errors errors). Notifying fatal tunnel failure.")
                        onFatalTunnelFailure(e)
                    }
                }
            }
        }

        dnsRelay = DnsRelay(
            scope = scope,
            defaultDnsServer = if (settings.dnsServer.isNotBlank()) settings.dnsServer else "https://8.8.8.8/dns-query",
            fallbackDnsServer = if (settings.customDns.isNotBlank()) settings.customDns else "8.8.8.8",
            protectSocket = protectSocket,
            protectDatagram = protectDatagram,
            sendToTun = sendToTunFunc,
            onTraffic = onTraffic
        )

        icmpHandler = IcmpHandler(
            sendToTun = sendToTunFunc,
            onTraffic = onTraffic
        )

        tcpTunnel = TcpVlessTunnel(
            scope = scope,
            profile = profile,
            settings = settings,
            protectSocket = protectSocket,
            sendToTun = sendToTunFunc,
            onTraffic = onTraffic,
            onTunnelError = onTunnelError
        )

        udpRelay = UdpRelay(
            scope = scope,
            profile = profile,
            settings = settings,
            protectSocket = protectSocket,
            protectDatagram = protectDatagram,
            sendToTun = sendToTunFunc,
            onTraffic = onTraffic,
            onTunnelError = onTunnelError
        )

        XrayLogManager.appendLog("TUN transparent router active: DNS, ICMP, and TCP/UDP VLESS bridges initialized.", "TUNNEL")

        tunnelJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(settings.mtu + 500)

            try {
                while (isActive && isRunning.get()) {
                    val length = inStream.read(buffer)
                    if (length > 0) {
                        processPacket(buffer, length)
                    } else if (length < 0) {
                        XrayLogManager.w("TUNNEL", "TUN input stream read returned EOF (-1). OS closed TUN interface.")
                        if (isRunning.get()) {
                            onFatalTunnelFailure(IOException("TUN input stream EOF (-1)"))
                        }
                        break
                    } else {
                        kotlinx.coroutines.delay(5)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    XrayLogManager.e("TUNNEL", "Fatal exception in TUN I/O read loop: ${e.message}", e)
                    onFatalTunnelFailure(e)
                }
            } finally {
                try { inStream.close() } catch (_: Exception) {}
                try {
                    synchronized(outputLock) {
                        outputStream?.close()
                        outputStream = null
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun processPacket(buffer: ByteArray, length: Int) {
        val ipHeader = IPv4Header.parse(buffer, 0, length) ?: return
        // Fragment reassembly is not implemented. Never interpret fragments as transport headers.
        if ((ipHeader.flags and 0x3fff) != 0) return

        when (ipHeader.protocol) {
            IpProtocol.ICMP -> {
                icmpHandler?.handleIcmpPacket(ipHeader, buffer, length)
            }
            IpProtocol.UDP -> {
                val ipHeaderLen = ipHeader.ihl * 4
                val udpHeader = UdpHeader.parse(buffer, ipHeaderLen, ipHeader.totalLength) ?: return
                if (udpHeader.dstPort == 53) {
                    dnsRelay?.handleDnsPacket(ipHeader, udpHeader, buffer)
                } else {
                    udpRelay?.handleUdpPacket(ipHeader, udpHeader, buffer)
                }
            }
            IpProtocol.TCP -> {
                val ipHeaderLen = ipHeader.ihl * 4
                val tcpHeader = TcpHeader.parse(buffer, 0, ipHeaderLen, ipHeader.totalLength) ?: return
                tcpTunnel?.handleTcpPacket(ipHeader, tcpHeader, buffer)
            }
            else -> {
                // Ignore unhandled protocol
            }
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        XrayLogManager.appendLog("Stopping TUN device I/O handler and closing file descriptor...", "TUNNEL")
        tunnelJob?.cancel()
        tunnelJob = null

        tcpTunnel?.closeAll()
        udpRelay?.closeAll()
        scope.cancel()

        try {
            inputStream?.close()
            inputStream = null
        } catch (_: Exception) {}

        try {
            synchronized(outputLock) {
                outputStream?.close()
                outputStream = null
            }
        } catch (_: Exception) {}
    }
}
