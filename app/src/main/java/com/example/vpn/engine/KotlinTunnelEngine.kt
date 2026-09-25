package com.example.vpn.engine

import com.example.core.AppResult
import com.example.data.model.AppSettings
import com.example.data.model.EngineType
import com.example.data.model.TrafficStats
import com.example.data.model.VlessProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Lifecycle and counters for HTTP CONNECT/SOCKS nodes forwarded by TunnelManager. */
class KotlinTunnelEngine private constructor() : VpnEngine {
    companion object {
        val instance: VpnEngine by lazy { KotlinTunnelEngine() }
    }

    override val engineType: EngineType = EngineType.XRAY
    override val engineVersion: String = "Maximus Kotlin TunnelManager (HTTP/SOCKS5 compatibility)"
    private val running = AtomicBoolean(false)
    private val tx = AtomicLong(0)
    private val rx = AtomicLong(0)
    private val _stats = MutableStateFlow(TrafficStats())
    override val statsFlow: StateFlow<TrafficStats> = _stats.asStateFlow()

    override fun start(profile: VlessProfile, settings: AppSettings, protectSocket: (Socket) -> Boolean, protectDatagram: (DatagramSocket) -> Boolean): AppResult<Unit> {
        tx.set(0)
        rx.set(0)
        _stats.value = TrafficStats()
        running.set(true)
        return AppResult.Success(Unit)
    }

    override fun stop(): AppResult<Unit> {
        running.set(false)
        _stats.value = TrafficStats(txBytes = tx.get(), rxBytes = rx.get())
        return AppResult.Success(Unit)
    }

    override fun restart(profile: VlessProfile, settings: AppSettings, protectSocket: (Socket) -> Boolean, protectDatagram: (DatagramSocket) -> Boolean): AppResult<Unit> {
        stop()
        return start(profile, settings, protectSocket, protectDatagram)
    }

    override fun isRunning(): Boolean = running.get()
    override fun getStats(): TrafficStats = _stats.value

    override fun recordTraffic(sent: Long, received: Long) {
        if (sent > 0) tx.addAndGet(sent)
        if (received > 0) rx.addAndGet(received)
        _stats.value = TrafficStats(txBytes = tx.get(), rxBytes = rx.get())
    }
}
