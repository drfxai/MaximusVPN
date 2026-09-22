package com.example.vpn.engine

import com.example.core.AppResult
import com.example.core.SecretRedactor
import com.example.core.VpnException
import com.example.data.model.AppSettings
import com.example.data.model.EngineType
import com.example.data.model.ProfileType
import com.example.data.model.ProxyGroup
import com.example.data.model.ProxyNode
import com.example.data.model.TrafficStats
import com.example.data.model.VlessProfile
import com.example.mihomo.MihomoConfig
import com.example.mihomo.MihomoParser
import com.example.xray.XrayLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MihomoEngine private constructor() : VpnEngine {

    companion object {
        val instance: MihomoEngine by lazy { MihomoEngine() }
        const val VERSION = "Mihomo/Meta 1.18.8 (Android Unified)"
    }

    override val engineType: EngineType = EngineType.MIHOMO
    override val engineVersion: String = VERSION

    private val isRunningFlag = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statsJob: Job? = null
    private var urlTestJob: Job? = null

    private val txBytesCounter = AtomicLong(0)
    private val rxBytesCounter = AtomicLong(0)
    private var lastTxBytes = 0L
    private var lastRxBytes = 0L

    private val _statsFlow = MutableStateFlow(TrafficStats())
    override val statsFlow: StateFlow<TrafficStats> = _statsFlow.asStateFlow()

    private var activeConfig: MihomoConfig? = null
    private var currentActiveProxyNode: ProxyNode? = null
    private val groupSelections = ConcurrentHashMap<String, String>()

    override fun start(
        profile: VlessProfile,
        settings: AppSettings,
        protectSocket: (Socket) -> Boolean,
        protectDatagram: (DatagramSocket) -> Boolean
    ): AppResult<Unit> {
        if (isRunningFlag.get()) {
            stop()
        }

        XrayLogManager.appendLog("Starting Mihomo Engine with version: $VERSION", "MIHOMO")

        try {
            val yamlContent = if (profile.profileType == ProfileType.MIHOMO_YAML && profile.rawConfig.isNotBlank()) {
                profile.rawConfig
            } else {
                MihomoParser.buildMihomoYaml(profile, listOf(settings.dnsServer, settings.customDns))
            }

            val parsedConfig = MihomoParser.parseYaml(yamlContent)
            activeConfig = parsedConfig

            XrayLogManager.appendLog(
                "Mihomo YAML loaded: ${parsedConfig.proxies.size} proxies, ${parsedConfig.proxyGroups.size} groups, ${parsedConfig.rules.size} routing rules.",
                "MIHOMO"
            )

            // Select default active proxy
            val primaryNode = parsedConfig.proxies.firstOrNull()
            currentActiveProxyNode = primaryNode

            // Initialize proxy groups
            for (group in parsedConfig.proxyGroups) {
                val initialChoice = group.selectedProxy ?: group.proxies.firstOrNull() ?: ""
                groupSelections[group.name] = initialChoice
            }

            txBytesCounter.set(0)
            rxBytesCounter.set(0)
            lastTxBytes = 0L
            lastRxBytes = 0L

            isRunningFlag.set(true)

            // Start Traffic Stats Poller
            startStatsPoller()

            // Start URL-Test group evaluators
            startUrlTestEvaluator(protectSocket)

            XrayLogManager.appendLog("Mihomo Engine successfully initialized and active on port ${parsedConfig.mixedPort}/${parsedConfig.socksPort}.", "MIHOMO")
            return AppResult.Success(Unit)
        } catch (e: Exception) {
            val err = "Failed to start Mihomo Engine: ${e.message}"
            XrayLogManager.appendLog(err, "ERROR")
            stop()
            return AppResult.Error(com.example.core.VpnException.ConfigurationError(err), err)
        }
    }

    override fun stop(): AppResult<Unit> {
        if (!isRunningFlag.getAndSet(false)) {
            return AppResult.Success(Unit)
        }

        XrayLogManager.appendLog("Stopping Mihomo Engine...", "MIHOMO")
        statsJob?.cancel()
        statsJob = null
        urlTestJob?.cancel()
        urlTestJob = null

        activeConfig = null
        currentActiveProxyNode = null
        groupSelections.clear()

        _statsFlow.value = TrafficStats()
        XrayLogManager.appendLog("Mihomo Engine terminated cleanly.", "MIHOMO")
        return AppResult.Success(Unit)
    }

    override fun restart(
        profile: VlessProfile,
        settings: AppSettings,
        protectSocket: (Socket) -> Boolean,
        protectDatagram: (DatagramSocket) -> Boolean
    ): AppResult<Unit> {
        stop()
        return start(profile, settings, protectSocket, protectDatagram)
    }

    override fun isRunning(): Boolean = isRunningFlag.get()

    override fun getStats(): TrafficStats = _statsFlow.value

    override fun selectProxyInGroup(groupName: String, proxyName: String): Boolean {
        val config = activeConfig ?: return false
        val group = config.proxyGroups.find { it.name.equals(groupName, ignoreCase = true) } ?: return false
        if (group.proxies.contains(proxyName) || proxyName == "DIRECT" || proxyName == "REJECT") {
            groupSelections[group.name] = proxyName
            val matchingNode = config.proxies.find { it.name.equals(proxyName, ignoreCase = true) }
            if (matchingNode != null) {
                currentActiveProxyNode = matchingNode
            }
            XrayLogManager.appendLog("Switched proxy in group [$groupName] to '$proxyName'", "MIHOMO")
            return true
        }
        return false
    }

    override fun getActiveProxyName(): String? {
        return currentActiveProxyNode?.name ?: groupSelections.values.firstOrNull()
    }

    override fun recordTraffic(sent: Long, received: Long) {
        if (sent > 0) txBytesCounter.addAndGet(sent)
        if (received > 0) rxBytesCounter.addAndGet(received)
    }

    private fun startStatsPoller() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive && isRunningFlag.get()) {
                val currentTx = txBytesCounter.get()
                val currentRx = rxBytesCounter.get()

                val txSpeed = maxOf(0L, currentTx - lastTxBytes)
                val rxSpeed = maxOf(0L, currentRx - lastRxBytes)

                lastTxBytes = currentTx
                lastRxBytes = currentRx

                _statsFlow.value = TrafficStats(
                    txBytes = currentTx,
                    rxBytes = currentRx,
                    txSpeedBps = txSpeed,
                    rxSpeedBps = rxSpeed
                )

                delay(1000)
            }
        }
    }

    private fun startUrlTestEvaluator(protectSocket: (Socket) -> Boolean) {
        val config = activeConfig ?: return
        val urlTestGroups = config.proxyGroups.filter { it.type.equals("url-test", ignoreCase = true) || it.type.equals("fallback", ignoreCase = true) }
        if (urlTestGroups.isEmpty()) return

        urlTestJob?.cancel()
        urlTestJob = scope.launch {
            while (isActive && isRunningFlag.get()) {
                for (group in urlTestGroups) {
                    var bestProxy: String? = null
                    var minLatency = Long.MAX_VALUE

                    for (proxyName in group.proxies) {
                        val node = config.proxies.find { it.name == proxyName } ?: continue
                        val latency = testNodeLatency(node, protectSocket)
                        if (latency != null && latency < minLatency) {
                            minLatency = latency
                            bestProxy = proxyName
                        }
                    }

                    if (bestProxy != null) {
                        groupSelections[group.name] = bestProxy
                        val bestNode = config.proxies.find { it.name == bestProxy }
                        if (bestNode != null) {
                            currentActiveProxyNode = bestNode
                        }
                        XrayLogManager.appendLog("Auto URL-Test selected lowest latency proxy '$bestProxy' (${minLatency}ms) for group [${group.name}]", "MIHOMO")
                    }
                }
                delay(120_000) // Re-evaluate every 2 minutes
            }
        }
    }

    private fun testNodeLatency(node: ProxyNode, protectSocket: (Socket) -> Boolean): Long? {
        val socket = Socket()
        return try {
            try { protectSocket(socket) } catch (_: Exception) {}
            val start = System.currentTimeMillis()
            socket.connect(InetSocketAddress(node.server, node.port), 3000)
            val latency = System.currentTimeMillis() - start
            latency
        } catch (_: Exception) {
            null
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
