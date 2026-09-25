package com.example.xray

import com.example.core.AppResult
import com.example.core.VpnException
import com.example.data.model.AppSettings
import com.example.data.model.EngineType
import com.example.data.model.TrafficStats
import com.example.data.model.VlessProfile
import com.example.vpn.engine.VpnEngine
import com.example.vpn.engine.NativeTunVpnEngine
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
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL

interface XrayEngine : VpnEngine {
    fun start(configJson: String, protectFd: (Int) -> Boolean): AppResult<Unit>
    fun restart(configJson: String, protectFd: (Int) -> Boolean): AppResult<Unit>
}

class XrayEngineImpl private constructor() : XrayEngine, NativeTunVpnEngine {

    companion object {
        val instance: XrayEngine by lazy { XrayEngineImpl() }
        const val ENGINE_VERSION = "Xray-core via XTLS/libXray v26.9.9"
    }

    override val engineType: EngineType = EngineType.XRAY
    override val engineVersion: String = ENGINE_VERSION

    private val isRunningFlag = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statsJob: Job? = null

    private val txBytesCounter = AtomicLong(0)
    private val rxBytesCounter = AtomicLong(0)
    private var lastTxBytes = 0L
    private var lastRxBytes = 0L

    private var activeProfile: VlessProfile? = null
    private val metricsPort = 49227

    private val _statsFlow = MutableStateFlow(TrafficStats())
    override val statsFlow: StateFlow<TrafficStats> = _statsFlow.asStateFlow()

    override fun start(
        profile: VlessProfile,
        settings: AppSettings,
        protectSocket: (Socket) -> Boolean,
        protectDatagram: (DatagramSocket) -> Boolean
    ): AppResult<Unit> {
        return AppResult.Error(
            VpnException.XrayStartupFailed("Xray-core requires the active Android TUN descriptor."),
            "Xray-core must be started by the Android VPN service."
        )
    }

    override fun startWithTun(
        profile: VlessProfile,
        settings: AppSettings,
        tunFd: Int,
        protectFd: (Int) -> Boolean
    ): AppResult<Unit> {
        activeProfile = profile
        val configJson = XrayConfigBuilder.buildJson(profile, settings)
        return start(configJson, tunFd, settings.dnsServer, settings.mtu, protectFd)
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

    override fun getActiveProxyName(): String? = activeProfile?.name

    override fun start(configJson: String, protectFd: (Int) -> Boolean): AppResult<Unit> {
        return AppResult.Error(
            VpnException.XrayStartupFailed("The active Android TUN descriptor was not supplied."),
            "Cannot start Xray-core without the VPN TUN descriptor."
        )
    }

    private fun start(configJson: String, tunFd: Int, dns: String, mtu: Int, protectFd: (Int) -> Boolean): AppResult<Unit> {
        if (isRunningFlag.get()) {
            XrayLogManager.appendLog("Engine is already running. Stopping previous instance...", "ENGINE")
            stop()
        }

        XrayLogManager.appendLog("Starting native Xray-core: $ENGINE_VERSION", "ENGINE")

        try {
            val config = JSONObject(configJson)
            val env = config.optJSONObject("env") ?: JSONObject().also { config.put("env", it) }
            env.put("xray.tun.fd", tunFd.toString())
            val inbounds = config.optJSONArray("inbounds") ?: org.json.JSONArray().also { config.put("inbounds", it) }
            if ((0 until inbounds.length()).none { inbounds.optJSONObject(it)?.optString("protocol") == "tun" }) {
                inbounds.put(JSONObject().apply {
                    put("tag", "android-tun-in")
                    put("port", 0)
                    put("protocol", "tun")
                    put("settings", JSONObject().apply {
                        put("name", "maximus-tun")
                        put("mtu", mtu)
                    })
                })
            }
            (0 until inbounds.length()).mapNotNull { inbounds.optJSONObject(it) }
                .filter { it.optString("protocol") == "tun" }
                .forEach { inbound ->
                    val tunSettings = inbound.optJSONObject("settings") ?: JSONObject().also { inbound.put("settings", it) }
                    tunSettings.put("mtu", mtu)
                }
            config.put("metrics", JSONObject().put("listen", "127.0.0.1:$metricsPort"))
            // Android VpnService.protect is the loop prevention mechanism; SO_MARK is Linux-root only.
            val outbounds = config.optJSONArray("outbounds") ?: org.json.JSONArray()
            (0 until outbounds.length()).forEach { index ->
                val stream = outbounds.optJSONObject(index)?.optJSONObject("streamSettings")
                stream?.optJSONObject("sockopt")?.remove("mark")
            }
            val nativeConfig = config.toString()
            XrayLogManager.appendLog("Native Xray configuration prepared with Android TUN fd $tunFd.", "ENGINE")

            val controller = createDialerController(protectFd)
            callLibXray("registerDialerController", controller)
            callLibXray("registerListenerController", controller)
            val dnsHost = dns.substringAfter("//", dns).substringBefore('/').substringBefore(':')
            val dnsIp = dnsHost.takeIf { it.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")) } ?: "1.1.1.1"
            val dnsEndpoint = "$dnsIp:53"
            callLibXray("setDNS", controller, dnsEndpoint)

            txBytesCounter.set(0)
            rxBytesCounter.set(0)
            lastTxBytes = 0L
            lastRxBytes = 0L

            val response = invokeXray("runXray", JSONObject().put("xrayJson", nativeConfig))
            if (!response.optBoolean("success", false)) {
                throw IllegalStateException(response.optString("error", "libXray rejected the configuration"))
            }
            val state = invokeXray("getXrayState")
            if (!state.optBoolean("success", false) || state.optJSONObject("data")?.optBoolean("running", false) != true) {
                throw IllegalStateException("libXray returned success but Xray-core is not running")
            }

            isRunningFlag.set(true)
            startStatsMonitor()
            XrayLogManager.appendLog("Native Xray-core started and attached to Android TUN.", "CORE")
            return AppResult.Success(Unit)
        } catch (e: Exception) {
            runCatching { invokeXray("stopXray") }
            runCatching { callLibXray("resetDNS") }
            isRunningFlag.set(false)
            XrayLogManager.appendLog("Failed to start Xray engine: ${e.message}", "ERROR")
            return AppResult.Error(
                VpnException.XrayStartupFailed(e.message ?: "Unknown startup failure", e),
                "Failed to initialize Xray engine: ${e.localizedMessage}"
            )
        }
    }

    override fun stop(): AppResult<Unit> {
        if (!isRunningFlag.get()) {
            return AppResult.Success(Unit)
        }

        XrayLogManager.appendLog("Stopping native Xray-core...", "ENGINE")
        runCatching { invokeXray("stopXray") }
        runCatching { callLibXray("resetDNS") }
        isRunningFlag.set(false)
        statsJob?.cancel()
        statsJob = null

        _statsFlow.value = TrafficStats(
            txBytes = txBytesCounter.get(),
            rxBytes = rxBytesCounter.get(),
            txSpeedBps = 0,
            rxSpeedBps = 0
        )

        XrayLogManager.appendLog("Xray engine shut down cleanly.", "ENGINE")
        return AppResult.Success(Unit)
    }

    override fun restart(configJson: String, protectFd: (Int) -> Boolean): AppResult<Unit> {
        XrayLogManager.appendLog("Restarting Xray engine with new configuration...", "ENGINE")
        stop()
        return start(configJson, protectFd)
    }

    override fun isRunning(): Boolean = isRunningFlag.get()

    override fun getStats(): TrafficStats = _statsFlow.value

    override fun getVersion(): String = ENGINE_VERSION

    private fun invokeXray(method: String, payload: JSONObject = JSONObject()): JSONObject {
        val request = JSONObject().put("apiVersion", 3).put("method", method).put("payload", payload)
        val response = callLibXray("invoke", request.toString()) as? String
            ?: throw IllegalStateException("libXray returned no response for $method")
        return JSONObject(response)
    }

    private fun callLibXray(method: String, vararg args: Any): Any? {
        val cls = Class.forName("libXray.LibXray")
        val target = cls.methods.firstOrNull { candidate ->
            candidate.name.equals(method, ignoreCase = true) && candidate.parameterTypes.size == args.size &&
                args.indices.all { i -> candidate.parameterTypes[i].isAssignableFrom(args[i].javaClass) || candidate.parameterTypes[i].isInterface }
        } ?: throw NoSuchMethodException("libXray.$method/${args.size}")
        return target.invoke(null, *args)
    }

    private fun createDialerController(protectFd: (Int) -> Boolean): Any {
        val controllerClass = Class.forName("libXray.DialerController")
        return Proxy.newProxyInstance(controllerClass.classLoader, arrayOf(controllerClass)) { _, method, args ->
            if (method.name.equals("protectFd", ignoreCase = true) || method.name.equals("protectFD", ignoreCase = true)) {
                protectFd((args?.firstOrNull() as? Number)?.toInt() ?: -1)
            } else null
        }
    }

    override fun recordTraffic(sent: Long, received: Long) {
        if (sent > 0) txBytesCounter.addAndGet(sent)
        if (received > 0) rxBytesCounter.addAndGet(received)
    }

    private fun startStatsMonitor() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive && isRunningFlag.get()) {
                delay(1000)
                readNativeMetrics()?.let { (tx, rx) ->
                    txBytesCounter.set(tx)
                    rxBytesCounter.set(rx)
                }
                val currentTx = txBytesCounter.get()
                val currentRx = rxBytesCounter.get()

                val txSpeed = (currentTx - lastTxBytes).coerceAtLeast(0)
                val rxSpeed = (currentRx - lastRxBytes).coerceAtLeast(0)

                lastTxBytes = currentTx
                lastRxBytes = currentRx

                _statsFlow.value = TrafficStats(
                    txBytes = currentTx,
                    rxBytes = currentRx,
                    txSpeedBps = txSpeed,
                    rxSpeedBps = rxSpeed
                )
            }
        }
    }

    private fun readNativeMetrics(): Pair<Long, Long>? = runCatching {
        val connection = URL("http://127.0.0.1:$metricsPort/debug/vars").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 400
            connection.readTimeout = 400
            val metrics = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optJSONObject("stats") ?: return null
            var tx = 0L
            var rx = 0L
            val keys = metrics.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!key.startsWith("stats_outbound>>>proxy>>>traffic>>>")) continue
                when {
                    key.endsWith(">>>uplink") -> tx += metrics.optLong(key)
                    key.endsWith(">>>downlink") -> rx += metrics.optLong(key)
                }
            }
            tx to rx
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
