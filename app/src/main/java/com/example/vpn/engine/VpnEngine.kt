package com.example.vpn.engine

import com.example.core.AppResult
import com.example.data.model.AppSettings
import com.example.data.model.EngineType
import com.example.data.model.ProxyNode
import com.example.data.model.TrafficStats
import com.example.data.model.VlessProfile
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramSocket
import java.net.Socket

interface VpnEngine {
    val engineType: EngineType
    val engineVersion: String
    val statsFlow: StateFlow<TrafficStats>

    fun start(
        profile: VlessProfile,
        settings: AppSettings,
        protectSocket: (Socket) -> Boolean,
        protectDatagram: (DatagramSocket) -> Boolean
    ): AppResult<Unit>

    fun stop(): AppResult<Unit>

    fun restart(
        profile: VlessProfile,
        settings: AppSettings,
        protectSocket: (Socket) -> Boolean,
        protectDatagram: (DatagramSocket) -> Boolean
    ): AppResult<Unit>

    fun isRunning(): Boolean
    fun getStats(): TrafficStats
    fun getVersion(): String = engineVersion

    fun selectProxyInGroup(groupName: String, proxyName: String): Boolean = false
    fun getActiveProxyName(): String? = null
    fun recordTraffic(sent: Long, received: Long) {}
}
