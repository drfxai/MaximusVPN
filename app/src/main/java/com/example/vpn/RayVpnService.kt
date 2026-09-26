package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.core.SecretRedactor
import com.example.data.database.AppDatabase
import com.example.data.model.AppSettings
import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.EngineType
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.RoutingMode
import com.example.data.model.VlessProfile
import com.example.data.repository.ServerRepository
import com.example.data.repository.SettingsRepository
import com.example.vpn.engine.MihomoEngine
import com.example.vpn.engine.EngineSelectionPolicy
import com.example.vpn.engine.VpnEngine
import com.example.vpn.engine.NativeTunVpnEngine
import com.example.xray.XrayConfigBuilder
import com.example.xray.XrayEngine
import com.example.xray.XrayEngineImpl
import com.example.xray.XrayLogManager
import java.net.DatagramSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress

class RayVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.example.raytunnel.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.raytunnel.ACTION_DISCONNECT"
        const val ACTION_RECONNECT = "com.example.raytunnel.ACTION_RECONNECT"
        const val EXTRA_PROFILE_ID = "com.example.raytunnel.EXTRA_PROFILE_ID"

        const val NOTIFICATION_CHANNEL_ID = "raytunnel_vpn_channel"
        const val NOTIFICATION_ID = 1001

        private val _vpnState = MutableStateFlow(ConnectionState())
        val vpnState: StateFlow<ConnectionState> = _vpnState.asStateFlow()

        fun updateState(state: ConnectionState) {
            _vpnState.value = state
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connectionMutex = Mutex()
    private var connectJob: Job? = null
    private var durationJob: Job? = null
    private var pingJob: Job? = null

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelManager: TunnelManager? = null
    private var activeEngine: VpnEngine = XrayEngineImpl.instance
    private var failoverManager: com.example.vpn.smart.FailoverManager? = null
    private val serviceTxBytes = java.util.concurrent.atomic.AtomicLong(0)
    private val serviceRxBytes = java.util.concurrent.atomic.AtomicLong(0)
    private val protectionFailureHandled = java.util.concurrent.atomic.AtomicBoolean(false)

    private lateinit var serverRepository: ServerRepository
    private lateinit var settingsRepository: SettingsRepository
    private var activeProfile: VlessProfile? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var settingsObserverJob: Job? = null
    private var lastObservedMode: com.example.data.model.OperationalMode? = null

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getInstance(applicationContext)
        serverRepository = ServerRepository(db.serverProfileDao())
        settingsRepository = com.example.RayApplication.instance.settingsRepository
        createNotificationChannel()
        registerNetworkCallback()
        observeSettingsFlow()
    }

    private fun observeSettingsFlow() {
        settingsObserverJob?.cancel()
        settingsObserverJob = serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                val prevMode = lastObservedMode
                lastObservedMode = settings.operationalMode
                if (prevMode != null && prevMode != settings.operationalMode && _vpnState.value.isConnected) {
                    handleLiveModeSwitch(prevMode, settings.operationalMode, settings)
                }
            }
        }
    }

    private suspend fun handleLiveModeSwitch(
        oldMode: com.example.data.model.OperationalMode,
        newMode: com.example.data.model.OperationalMode,
        settings: com.example.data.model.AppSettings
    ) {
        val safeProfileName = com.example.core.SecretRedactor.redact(activeProfile?.name ?: "")
        if (newMode == com.example.data.model.OperationalMode.GOD_MODE) {
            XrayLogManager.i("GOD_MODE", "⚡ LIVE RECONFIGURATION: Switched to GOD Mode on active tunnel '$safeProfileName'.")
            com.example.vpn.godmode.PsiphonConduitBridge.enableGodModeBridges()
            com.example.vpn.godmode.MaximusMeshManager.startMesh()

            activeProfile?.let { prof ->
                failoverManager?.startMonitoring(prof, settings)
            }
            showForegroundNotification("⚡ GOD Mode Active: ${activeProfile?.name ?: "Tunnel"}")
        } else {
            XrayLogManager.i("DAILY_MODE", "LIVE RECONFIGURATION: Switched to Daily Mode on active tunnel '$safeProfileName'.")
            com.example.vpn.godmode.PsiphonConduitBridge.disableGodModeBridges()
            com.example.vpn.godmode.MaximusMeshManager.stopMesh()

            if (activeProfile?.id?.startsWith("bridge-") == true || activeProfile?.id?.startsWith("mesh-") == true) {
                val allProfiles = try {
                    serverRepository.allProfiles.first()
                } catch (_: Exception) { emptyList() }
                val standardNodes = allProfiles.filter { !it.id.startsWith("bridge-") && !it.id.startsWith("mesh-") }
                val bestDaily = com.example.vpn.smart.SmartConnect.selectBestNode(standardNodes, settings.scoringProfile)?.profile ?: standardNodes.firstOrNull()
                if (bestDaily != null) {
                    XrayLogManager.i("DAILY_MODE", "Reverting from emergency bridge to primary daily node: ${bestDaily.name}")
                    connect(bestDaily)
                    return
                }
            }

            activeProfile?.let { prof ->
                failoverManager?.startMonitoring(prof, settings)
            }
            showForegroundNotification("Connected to ${activeProfile?.name ?: "Maximus"}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)

        when (action) {
            ACTION_CONNECT -> {
                val fgOk = showForegroundNotification("Starting Maximus VPN...")
                if (!fgOk) {
                    XrayLogManager.e("VPN", "Aborting onStartCommand: startForeground failed.")
                    updateState(_vpnState.value.copy(
                        status = ConnectionStatus.FAILED,
                        errorMessage = "Foreground notification could not be started."
                    ))
                    stopSelf()
                    return START_NOT_STICKY
                }

                connectJob?.cancel()
                connectJob = serviceScope.launch {
                    val profile = if (!profileId.isNullOrBlank()) {
                        serverRepository.getProfileById(profileId)
                    } else null

                    val targetProfile = profile
                        ?: settingsRepository.getSettings().selectedProfileId?.let { serverRepository.getProfileById(it) }
                        ?: serverRepository.getAllProfilesOnce().firstOrNull()

                    if (targetProfile != null) {
                        connect(targetProfile)
                    } else {
                        val err = "No valid server profile found to connect."
                        XrayLogManager.e("VPN", err)
                        updateState(_vpnState.value.copy(
                            status = ConnectionStatus.FAILED,
                            errorMessage = err
                        ))
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            ACTION_DISCONNECT -> {
                connectJob?.cancel()
                connectJob = null
                serviceScope.launch {
                    disconnect()
                }
            }
            ACTION_RECONNECT -> {
                val fgOk = showForegroundNotification("Reconnecting Maximus VPN...")
                if (!fgOk) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                connectJob?.cancel()
                connectJob = serviceScope.launch {
                    activeProfile?.let { connect(it) }
                }
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun connect(profile: VlessProfile): Unit = connectionMutex.withLock {
        connectLocked(profile)
    }

    private suspend fun connectLocked(profile: VlessProfile): Unit = withContext(Dispatchers.IO) {
        try {
            disconnectResources()
            protectionFailureHandled.set(false)

            // 1. Diagnostic step 1: Validate Android VPN permission
            val prepareIntent = VpnService.prepare(this@RayVpnService)
            if (prepareIntent != null) {
                val err = "VPN permission is not granted by user (VpnService.prepare returned intent)."
                XrayLogManager.e("VPN", "[DIAGNOSTICS] 1. VpnService.prepare() check: Permission DENIED.")
                updateState(_vpnState.value.copy(
                    status = ConnectionStatus.FAILED,
                    errorMessage = "VPN permission not granted by Android system."
                ))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@withContext
            }
            XrayLogManager.i("VPN", "[DIAGNOSTICS] 1. VpnService.prepare() check: Permission GRANTED.")

            activeProfile = profile
            val settings = settingsRepository.getSettings()

            updateState(ConnectionState(
                status = ConnectionStatus.PREPARING,
                activeProfile = profile
            ))

            // 2. Diagnostic step 2: Start foreground service immediately
            if (!showForegroundNotification("Preparing VPN interface...")) {
                XrayLogManager.e("VPN", "[DIAGNOSTICS] 2. Foreground service start FAILED.")
                updateState(_vpnState.value.copy(
                    status = ConnectionStatus.FAILED,
                    errorMessage = "Failed to start foreground service notification."
                ))
                stopSelf()
                return@withContext
            }
            XrayLogManager.i("VPN", "[DIAGNOSTICS] 2. Foreground service started successfully.")

            // 3. Diagnostic step 3: Validate VLESS Profile Configuration
            try {
                com.example.vless.VlessValidator.validate(profile)
                com.example.vpn.engine.RuntimeCapabilities.requireSupported(profile)
            } catch (e: Exception) {
                XrayLogManager.e("VPN", "Profile validation error: ${e.message}", e)
                updateState(_vpnState.value.copy(
                    status = ConnectionStatus.FAILED,
                    errorMessage = "Unsupported or invalid proxy profile: ${e.localizedMessage}"
                ))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@withContext
            }
            val safeName = com.example.core.SecretRedactor.redact(profile.name)
            XrayLogManager.i("VPN", "[DIAGNOSTICS] 3. Profile configuration validated for '$safeName'")

            // 4. Diagnostic step 4 & 5: Configure and establish Android VpnService TUN interface
            val safeMtu = settings.mtu.coerceIn(1280, 1500)
            val primaryDns = if (settings.dnsServer.isNotBlank() && !settings.dnsServer.startsWith("https://")) {
                settings.dnsServer
            } else {
                "1.1.1.1"
            }

            val builder = Builder()
                .setSession("Maximus - ${profile.name}")
                .setMtu(safeMtu)
                .setBlocking(true)
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)

            try {
                builder.addDnsServer(primaryDns)
            } catch (e: Exception) {
                XrayLogManager.e("DNS", "Failed to add primary DNS $primaryDns: ${e.message}", e)
                throw IllegalStateException("Failed to assign primary DNS $primaryDns: ${e.message}")
            }

            if (settings.customDns.isNotBlank() && !settings.customDns.startsWith("https://") && settings.customDns != primaryDns) {
                try {
                    builder.addDnsServer(settings.customDns)
                } catch (e: Exception) {
                    XrayLogManager.w("DNS", "Failed to add secondary DNS: ${e.localizedMessage}")
                }
            } else {
                try {
                    builder.addDnsServer("8.8.8.8")
                } catch (_: Exception) {}
            }

            if (settings.ipv6Enabled) {
                try {
                    builder.addAddress("fdfe:dcba:9876::1", 126)
                    builder.addRoute("::", 0)
                } catch (e: Exception) {
                    XrayLogManager.w("VPN", "IPv6 address configuration skipped: ${e.message}")
                }
            }

            XrayLogManager.i("VPN", "[DIAGNOSTICS] 4. Builder.establish() invoked (MTU=$safeMtu, Address=172.19.0.1/30, DNS=$primaryDns).")
            val pfd = try {
                builder.establish()
            } catch (e: Exception) {
                XrayLogManager.e("VPN", "builder.establish() threw exception: ${e.javaClass.simpleName}: ${e.message}", e)
                null
            }

            if (pfd == null) {
                val err = "VpnService.Builder.establish() returned null. Android VPN permission may be revoked or another VPN is active."
                XrayLogManager.e("VPN", "[DIAGNOSTICS] 5. Builder.establish() returned null.")
                XrayLogManager.e("VPN", err)
                updateState(_vpnState.value.copy(
                    status = ConnectionStatus.FAILED,
                    errorMessage = "VPN interface establishment failed (permission revoked or another VPN active)."
                ))
                disconnectResources()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@withContext
            }

            if (!coroutineContext.isActive) {
                try { pfd.close() } catch (_: Exception) {}
                return@withContext
            }

            vpnInterface = pfd
            XrayLogManager.i("VPN", "[DIAGNOSTICS] 5. Builder.establish() returned non-null ParcelFileDescriptor.")
            XrayLogManager.i("VPN", "[DIAGNOSTICS] 6. TUN file descriptor number: #${pfd.fd}.")

            Socket().use { probe ->
                check(safeProtectSocket(probe)) {
                    "Android rejected VPN socket protection; VPN forwarding cannot start. Check VPN permission."
                }
            }
            XrayLogManager.i("VPN", "[DIAGNOSTICS] Upstream TCP socket protection probe succeeded.")

            // 6. Update Connection State to VPN_INTERFACE_ESTABLISHED
            serviceTxBytes.set(0)
            serviceRxBytes.set(0)
            updateState(_vpnState.value.copy(
                status = ConnectionStatus.VPN_INTERFACE_ESTABLISHED,
                activeProfile = profile,
                vpnIp = "172.19.0.1",
                errorMessage = null,
                uploadBytes = 0,
                downloadBytes = 0,
                uploadSpeedBps = 0,
                downloadSpeedBps = 0
            ))
            showForegroundNotification("Android VPN Active (Establishing Proxy...)")

            // 7. Diagnostic step 7: Select and start active engine
            activeEngine = if (EngineSelectionPolicy.requiresKotlinTunnel(profile)) {
                com.example.vpn.engine.KotlinTunnelEngine.instance
            } else when (settings.preferredEngine) {
                EngineType.MIHOMO -> {
                    if (profile.protocolType == ProtocolType.HTTP || profile.protocolType == ProtocolType.SOCKS5) {
                        com.example.vpn.engine.KotlinTunnelEngine.instance
                    } else {
                        com.example.vpn.engine.MihomoEngine.instance
                    }
                }
                EngineType.XRAY -> {
                    if (profile.protocolType == ProtocolType.HYSTERIA2 || profile.protocolType == ProtocolType.TUIC) {
                        com.example.vpn.engine.MihomoEngine.instance
                    } else if (profile.protocolType == ProtocolType.HTTP || profile.protocolType == ProtocolType.SOCKS5) {
                        com.example.vpn.engine.KotlinTunnelEngine.instance
                    } else {
                        XrayEngineImpl.instance
                    }
                }
                EngineType.AUTO -> {
                    if (profile.engineType == EngineType.MIHOMO ||
                        profile.profileType == ProfileType.MIHOMO_YAML ||
                        profile.protocolType == ProtocolType.HYSTERIA2 ||
                        profile.protocolType == ProtocolType.TUIC
                    ) {
                        com.example.vpn.engine.MihomoEngine.instance
                    } else if (profile.protocolType == ProtocolType.HTTP || profile.protocolType == ProtocolType.SOCKS5) {
                        com.example.vpn.engine.KotlinTunnelEngine.instance
                    } else {
                        XrayEngineImpl.instance
                    }
                }
            }

            updateState(_vpnState.value.copy(activeEngineName = activeEngine.engineVersion))

            updateState(_vpnState.value.copy(status = ConnectionStatus.PROXY_CONNECTING))
            showForegroundNotification("Connecting Proxy via ${activeEngine.engineType.displayName}...")

            val startResult = if (activeEngine is NativeTunVpnEngine) {
                (activeEngine as NativeTunVpnEngine).startWithTun(profile, settings.copy(mtu = safeMtu), pfd.fd) { fd -> safeProtectFd(fd) }
            } else {
                activeEngine.start(
                    profile = profile,
                    settings = settings,
                    protectSocket = { socket: Socket -> safeProtectSocket(socket) },
                    protectDatagram = { dSocket: DatagramSocket -> safeProtectDatagram(dSocket) }
                )
            }
            XrayLogManager.i("VPN", "[DIAGNOSTICS] 7. Proxy engine start result: $startResult.")
            if (startResult is com.example.core.AppResult.Error) throw startResult.exception

            if (!coroutineContext.isActive) {
                disconnectResources()
                return@withContext
            }

            // Native Xray owns the TUN fd directly; the Kotlin packet loop must not read it concurrently.
            if (activeEngine !is NativeTunVpnEngine) tunnelManager = TunnelManager(
                vpnInterface = pfd,
                profile = profile,
                settings = settings.copy(mtu = safeMtu),
                protectSocket = { socket -> safeProtectSocket(socket) },
                protectDatagram = { datagramSocket -> safeProtectDatagram(datagramSocket) },
                onTraffic = { sent, received ->
                    if (sent > 0) serviceTxBytes.addAndGet(sent)
                    if (received > 0) serviceRxBytes.addAndGet(received)
                    activeEngine.recordTraffic(sent, received)
                },
                onFatalTunnelFailure = { cause ->
                    XrayLogManager.e("VPN", "Fatal TUN failure: ${cause.message}", cause)
                    serviceScope.launch {
                        updateState(_vpnState.value.copy(
                            status = ConnectionStatus.FAILED,
                            errorMessage = "TUN packet loop terminated: ${cause.message}"
                        ))
                        disconnectResources()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                },
                onTunnelError = { reason ->
                    if (reason.contains("socket protection failed", ignoreCase = true)) {
                        if (protectionFailureHandled.compareAndSet(false, true)) {
                            serviceScope.launch {
                                XrayLogManager.e("VPN", "Android rejected upstream socket protection. Closing VPN instead of switching servers.")
                                updateState(_vpnState.value.copy(
                                    status = ConnectionStatus.FAILED,
                                    errorMessage = "Android rejected upstream socket protection. Re-enable VPN permission and reconnect."
                                ))
                                disconnectResources()
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }
                    } else {
                        failoverManager?.reportTunnelError(reason)
                    }
                }
            )
            tunnelManager?.start()
            XrayLogManager.i("VPN", "[DIAGNOSTICS] 8. ${if (activeEngine is NativeTunVpnEngine) "Native Xray TUN loop started" else "TUN packet loop started"}.")

            if (!coroutineContext.isActive) {
                disconnectResources()
                return@withContext
            }

            XrayLogManager.i("VPN", "[DIAGNOSTICS] 9. Upstream socket protection probe passed.")

            // 10. Update Connection State to CONNECTED
            val startTime = System.currentTimeMillis()
            updateState(_vpnState.value.copy(
                status = ConnectionStatus.CONNECTED,
                activeProfile = profile,
                lastConnectedTime = startTime,
                vpnIp = "172.19.0.1",
                errorMessage = null
            ))

            showForegroundNotification("Connected to ${profile.name}")
            XrayLogManager.i("VPN", "[DIAGNOSTICS] 10. Connection lifecycle complete. Final state: CONNECTED.")

            // Initialize Operational Mode features
            if (settings.operationalMode == com.example.data.model.OperationalMode.GOD_MODE) {
                com.example.vpn.godmode.PsiphonConduitBridge.enableGodModeBridges()
                com.example.vpn.godmode.MaximusMeshManager.startMesh()
                XrayLogManager.i("GOD_MODE", "GOD Mode Active: Volunteer Psiphon/Conduit bridges & P2P Mesh enabled.")
            } else {
                com.example.vpn.godmode.PsiphonConduitBridge.disableGodModeBridges()
                com.example.vpn.godmode.MaximusMeshManager.stopMesh()
            }

            // Start Failover Manager & Watchdogs
            failoverManager?.stopMonitoring()
            failoverManager = com.example.vpn.smart.FailoverManager(
                serverRepository = serverRepository,
                protectSocket = { socket -> safeProtectSocket(socket) },
                onTriggerSwitch = { newProfile, reason ->
                    serviceScope.launch {
                        XrayLogManager.w("FAILOVER", "Executing auto-failover to '${newProfile.name}': $reason")
                        connect(newProfile)
                    }
                }
            ).apply {
                startMonitoring(profile, settings)
            }

            startDurationAndPingWatchers(profile, startTime)

        } catch (e: CancellationException) {
            disconnectResources()
            throw e
        } catch (e: Exception) {
            XrayLogManager.e("VPN", "Fatal error establishing VPN connection: ${e.message}", e)
            updateState(_vpnState.value.copy(
                status = ConnectionStatus.FAILED,
                errorMessage = e.localizedMessage ?: "Unknown connection failure"
            ))
            disconnectResources()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun safeProtectSocket(socket: Socket): Boolean {
        try {
            return protectTcpSocket(socket) { protect(it) }
        } catch (e: Exception) {
            XrayLogManager.w("SOCKET", "Unable to prepare or protect TCP socket: ${e.message}")
        }
        return false
    }

    private fun safeProtectFd(fd: Int): Boolean = try {
        protect(fd)
    } catch (e: Exception) {
        XrayLogManager.e("VPN", "Failed to protect native Xray socket fd $fd", e)
        false
    }

    private fun safeProtectDatagram(datagramSocket: DatagramSocket): Boolean {
        try {
            return protect(datagramSocket)
        } catch (e: Exception) {
            XrayLogManager.d("SOCKET", "protect(DatagramSocket) exception: ${e.message}")
        }
        return false
    }

    private fun startDurationAndPingWatchers(profile: VlessProfile, startTime: Long) {
        durationJob?.cancel()
        var lastTx = 0L
        var lastRx = 0L
        durationJob = serviceScope.launch {
            while (isActive && _vpnState.value.isConnected) {
                delay(1000)
                val durationSec = (System.currentTimeMillis() - startTime) / 1000
                val stats = activeEngine.getStats()

                val currentTx = maxOf(stats.txBytes, serviceTxBytes.get())
                val currentRx = maxOf(stats.rxBytes, serviceRxBytes.get())

                val calcTxSpeed = (currentTx - lastTx).coerceAtLeast(0L)
                val calcRxSpeed = (currentRx - lastRx).coerceAtLeast(0L)
                lastTx = currentTx
                lastRx = currentRx

                val speedTx = if (stats.txSpeedBps > 0) stats.txSpeedBps else calcTxSpeed
                val speedRx = if (stats.rxSpeedBps > 0) stats.rxSpeedBps else calcRxSpeed

                updateState(_vpnState.value.copy(
                    connectedDurationSeconds = durationSec,
                    uploadBytes = currentTx,
                    downloadBytes = currentRx,
                    uploadSpeedBps = speedTx,
                    downloadSpeedBps = speedRx
                ))
            }
        }

        pingJob?.cancel()
        pingJob = serviceScope.launch {
            while (isActive && _vpnState.value.isConnected) {
                try {
                    val socket = Socket()
                    check(safeProtectSocket(socket)) { "VPN socket protection failed" }
                    val sStart = System.currentTimeMillis()
                    socket.connect(InetSocketAddress(profile.address, profile.port), 3000)
                    val latency = System.currentTimeMillis() - sStart
                    socket.close()
                    updateState(_vpnState.value.copy(pingMs = latency))
                    serverRepository.updateLatency(profile.id, latency)
                } catch (_: Exception) {
                    // Ping failed
                }
                delay(10000)
            }
        }
    }

    private suspend fun disconnect() = connectionMutex.withLock {
        disconnectLocked()
    }

    private suspend fun disconnectLocked() = withContext(Dispatchers.IO) {
        XrayLogManager.appendLog("Initiating clean VPN disconnection...", "VPN")
        updateState(_vpnState.value.copy(status = ConnectionStatus.DISCONNECTING))

        disconnectResources()

        updateState(ConnectionState(
            status = ConnectionStatus.DISCONNECTED,
            activeProfile = null,
            connectedDurationSeconds = 0,
            uploadBytes = 0,
            downloadBytes = 0,
            uploadSpeedBps = 0,
            downloadSpeedBps = 0
        ))

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        XrayLogManager.appendLog("VPN successfully disconnected and interface closed.", "VPN")
    }

    private fun disconnectResources() {
        durationJob?.cancel()
        durationJob = null
        pingJob?.cancel()
        pingJob = null

        serviceTxBytes.set(0)
        serviceRxBytes.set(0)

        failoverManager?.stopMonitoring()
        failoverManager = null

        tunnelManager?.stop()
        tunnelManager = null

        // Stop Xray before closing the TUN descriptor it owns.
        activeEngine.stop()

        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null

        val currentMode = settingsRepository.getSettings().operationalMode
        if (currentMode != com.example.data.model.OperationalMode.GOD_MODE) {
            com.example.vpn.godmode.PsiphonConduitBridge.disableGodModeBridges()
            com.example.vpn.godmode.MaximusMeshManager.stopMesh()
        }
    }

    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                XrayLogManager.appendLog("Underlying network available.", "NETWORK")
                if (_vpnState.value.status == ConnectionStatus.RECONNECTING) {
                    activeProfile?.let { prof ->
                        serviceScope.launch {
                            XrayLogManager.appendLog("Network restored. Auto-reconnecting to ${prof.name}...", "VPN")
                            connect(prof)
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                XrayLogManager.appendLog("Underlying network connection lost.", "NETWORK")
                val settings = settingsRepository.getSettings()
                if (_vpnState.value.isConnected && settings.autoReconnect) {
                    XrayLogManager.appendLog("Auto-reconnect is enabled. Waiting for network recovery...", "VPN")
                    updateState(_vpnState.value.copy(status = ConnectionStatus.RECONNECTING))
                }
            }
        }

        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            XrayLogManager.w("NETWORK", "Failed to register network callback: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun showForegroundNotification(statusText: String): Boolean {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = Intent(this, RayVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val reconnectIntent = Intent(this, RayVpnService::class.java).apply {
            action = ACTION_RECONNECT
        }
        val reconnectPendingIntent = PendingIntent.getService(
            this,
            2,
            reconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val profileName = activeProfile?.name ?: "VLESS Tunnel"

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Maximus — $profileName")
            .setContentText(statusText)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectPendingIntent)
            .addAction(android.R.drawable.ic_menu_rotate, "Reconnect", reconnectPendingIntent)
            .build()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } catch (e: Exception) {
                    XrayLogManager.w("VPN", "Special use FGS startup failed: ${e.message}. Falling back to standard startForeground.")
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            XrayLogManager.e("VPN", "Fatal: startForeground() failed: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }

    override fun onRevoke() {
        XrayLogManager.appendLog("VPN service revoked by system or another VPN application.", "VPN")
        disconnectResources()
        updateState(ConnectionState(status = ConnectionStatus.DISCONNECTED))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        connectJob?.cancel()
        serviceScope.coroutineContext[Job]?.cancel()
        settingsObserverJob?.cancel()
        settingsObserverJob = null
        disconnectResources()
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
