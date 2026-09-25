package com.example.vpn.smart

import com.example.core.SecretRedactor
import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.data.model.ServerTestStatus
import com.example.data.model.VlessProfile
import com.example.data.repository.ServerRepository
import com.example.vpn.godmode.MaximusMeshManager
import com.example.vpn.godmode.PsiphonConduitBridge
import com.example.xray.XrayLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

/**
 * GOD MODE: Resilient 4-Tier Emergency Cascade & Auto-Failover Watchdog.
 *
 * Ordered Failover Ladder:
 * 1. Tier 1: Primary Reality / Hysteria2 endpoints (lowest latency, high score)
 * 2. Tier 2: Secondary Reality / VLESS backup nodes
 * 3. Tier 3: Verified Psiphon / Conduit volunteer proxy bridges
 * 4. Tier 4: Local Maximus P2P Mesh relays (WiFi / hotspot offline relay)
 *
 * Features continuous bidirectional health monitoring (escalates on severe censorship,
 * de-escalates back to Tier 1 when primary network connectivity recovers).
 */
class FailoverManager(
    private val serverRepository: ServerRepository,
    private val protectSocket: ((Socket) -> Boolean)? = null,
    private val onTriggerSwitch: (VlessProfile, String) -> Unit
) {
    enum class CascadeTier(val stageNumber: Int, val title: String, val badge: String) {
        TIER_1_PRIMARY_REALITY(1, "Tier 1: Primary Reality / Hysteria2", "TIER 1 - PRIMARY"),
        TIER_2_SECONDARY_NODES(2, "Tier 2: Backup Reality Nodes", "TIER 2 - BACKUP"),
        TIER_3_VOLUNTEER_BRIDGES(3, "Tier 3: Psiphon / Conduit Bridges", "TIER 3 - BRIDGES"),
        TIER_4_LOCAL_MESH(4, "Tier 4: Maximus P2P Mesh Relays", "TIER 4 - P2P MESH"),
        DEGRADED_OFFLINE(5, "Degraded: Outlets Severed", "DEGRADED")
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var monitorJob: Job? = null
    private var recoveryProbeJob: Job? = null

    private val _currentTier = MutableStateFlow(CascadeTier.TIER_1_PRIMARY_REALITY)
    val currentTier: StateFlow<CascadeTier> = _currentTier.asStateFlow()

    private val _failoverEvents = MutableStateFlow<String?>(null)
    val failoverEvents: StateFlow<String?> = _failoverEvents.asStateFlow()

    private var consecutiveFailures = 0
    private var currentActiveProfile: VlessProfile? = null
    private var currentActiveSettings: AppSettings? = null

    fun startMonitoring(currentProfile: VlessProfile, settings: AppSettings) {
        stopMonitoring()
        if (!settings.autoFailoverEnabled) return

        currentActiveProfile = currentProfile
        currentActiveSettings = settings
        _currentTier.value = determineInitialTier(currentProfile)
        consecutiveFailures = 0

        monitorJob = scope.launch {
            val safeName = SecretRedactor.redact(currentProfile.name)
            XrayLogManager.i("FAILOVER", "Failover watchdog active for '$safeName' [Mode: ${settings.operationalMode.displayName}, Tier: ${_currentTier.value.badge}].")

            while (isActive) {
                delay(12000) // Check health every 12 seconds

                val isHealthy = checkHealth(currentProfile, settings.failoverThresholdMs)
                if (!isHealthy) {
                    consecutiveFailures++
                    XrayLogManager.w("FAILOVER", "Node $safeName is unavailable or exceeds the configured ${settings.failoverThresholdMs}ms latency limit ($consecutiveFailures/3).")

                    // Require 3 consecutive failures to avoid flapping
                    if (consecutiveFailures >= 3) {
                        // The cooldown must survive a service switch, which creates a new manager.
                        if (acquireFailoverCooldown(25000)) {
                            attemptFailover(currentProfile, settings)
                        }
                    }
                } else {
                    consecutiveFailures = 0
                }
            }
        }

        // In GOD MODE: If running on emergency bridge or mesh (Tier 3/4), start background recovery probe
        if (settings.operationalMode == OperationalMode.GOD_MODE) {
            startRecoveryWatchdog(settings)
        }
    }

    /**
     * Reports runtime connection or handshake failures (e.g. WebSocket 301/404, TLS drop)
     * detected by active tunnel packet streaming.
     */
    fun reportTunnelError(reason: String) {
        val profile = currentActiveProfile ?: return
        val settings = currentActiveSettings ?: return
        if (monitorJob?.isActive != true) return

        consecutiveFailures++
        val safeName = SecretRedactor.redact(profile.name)
        XrayLogManager.w("FAILOVER", "Active tunnel error reported on '$safeName' ($consecutiveFailures/3): $reason")

        if (consecutiveFailures >= 3) {
            if (acquireFailoverCooldown(20000)) {
                scope.launch {
                    attemptFailover(profile, settings)
                }
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        recoveryProbeJob?.cancel()
        recoveryProbeJob = null
        consecutiveFailures = 0
        currentActiveProfile = null
        currentActiveSettings = null
    }

    private fun determineInitialTier(profile: VlessProfile): CascadeTier {
        return when {
            profile.id.startsWith("mesh-") -> CascadeTier.TIER_4_LOCAL_MESH
            profile.id.startsWith("bridge-") -> CascadeTier.TIER_3_VOLUNTEER_BRIDGES
            profile.overallScore >= 75.0 -> CascadeTier.TIER_1_PRIMARY_REALITY
            else -> CascadeTier.TIER_2_SECONDARY_NODES
        }
    }

    private suspend fun checkHealth(profile: VlessProfile, latencyThreshold: Long): Boolean {
        val result = com.example.vpn.ServerTester.testServer(
            profile = profile,
            timeoutMs = 3000,
            protectSocket = protectSocket
        )
        return when (val status = result.status) {
            is ServerTestStatus.Available -> status.latencyMs < latencyThreshold
            is ServerTestStatus.Slow -> status.latencyMs < latencyThreshold
            else -> false
        }
    }

    /**
     * Periodically probes Tier 1 primary servers when running in emergency fallback mode,
     * enabling automatic de-escalation once upstream internet filtering ceases.
     */
    private fun startRecoveryWatchdog(settings: AppSettings) {
        recoveryProbeJob?.cancel()
        recoveryProbeJob = scope.launch {
            while (isActive) {
                delay(30000) // Check primary recovery every 30s
                if (_currentTier.value == CascadeTier.TIER_3_VOLUNTEER_BRIDGES || _currentTier.value == CascadeTier.TIER_4_LOCAL_MESH) {
                    try {
                        val profiles = serverRepository.allProfiles.first()
                        val topPrimary = profiles.filter { !it.id.startsWith("bridge-") && !it.id.startsWith("mesh-") }
                            .maxByOrNull { it.overallScore }

                        if (topPrimary != null && checkHealth(topPrimary, 450L)) {
                            val reason = "GOD Mode Recovery: Primary network connectivity restored! De-escalating to Tier 1 (${topPrimary.name})."
                            _currentTier.value = CascadeTier.TIER_1_PRIMARY_REALITY
                            _failoverEvents.value = reason
                            XrayLogManager.i("GOD_MODE", reason)
                            onTriggerSwitch(topPrimary, reason)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private suspend fun attemptFailover(degradedProfile: VlessProfile, settings: AppSettings) {
        val allProfiles = try {
            serverRepository.allProfiles.first()
        } catch (_: Exception) {
            emptyList()
        }

        val safeDegraded = SecretRedactor.redact(degradedProfile.name)

        // Non-degraded candidates pool (excluding current failing node, bridges, mesh)
        val nonDegraded = eligibleFallbacks(allProfiles, degradedProfile)
        val scoredCandidates = nonDegraded.filter { it.overallScore > 0 }
        val candidateProfiles = if (scoredCandidates.isNotEmpty()) scoredCandidates else nonDegraded

        // --- GOD MODE CASCADE LADDER ---
        if (settings.operationalMode == OperationalMode.GOD_MODE) {
            XrayLogManager.w("GOD_MODE", "GOD Mode Cascade engaged due to outage on $safeDegraded!")

            // Step 1: Secondary Reality / VLESS nodes
            val bestFallback = SmartConnect.selectBestNode(candidateProfiles, settings.scoringProfile)

            if (bestFallback != null) {
                _currentTier.value = CascadeTier.TIER_2_SECONDARY_NODES
                val reason = "GOD Mode Cascade [Tier 2]: Switched to backup node ${bestFallback.profile.name}"
                _failoverEvents.value = reason
                XrayLogManager.i("GOD_MODE", reason)
                onTriggerSwitch(bestFallback.profile, reason)
                return
            }

            // Discovery and TCP reachability are not authenticated proxy credentials.
            // Never invent a VLESS UUID or send traffic to an unverified LAN beacon.
            _currentTier.value = CascadeTier.DEGRADED_OFFLINE
            XrayLogManager.e("GOD_MODE", "All cascade tiers exhausted for $safeDegraded. No verified fallback profile is available.")
        }

        // Standard Daily Mode fallback
        val fallback = SmartConnect.selectBestNode(candidateProfiles, settings.scoringProfile)
            ?: candidateProfiles.firstOrNull()?.let {
                SmartConnect.SmartSelection(
                    profile = it,
                    reasonPing = "Fallback",
                    reasonDownload = "Standard",
                    reasonStability = "Normal",
                    reasonPacketLoss = "0%",
                    overallScore = it.overallScore,
                    description = "Fallback to ${it.name}"
                )
            }

        if (fallback != null) {
            val reason = "Failover: Availability or latency policy triggered on $safeDegraded. Switched to ${fallback.profile.name}."
            _failoverEvents.value = reason
            XrayLogManager.i("FAILOVER", reason)
            onTriggerSwitch(fallback.profile, reason)
        } else {
            XrayLogManager.w("FAILOVER", "No distinct supported fallback node is available for $safeDegraded; keeping the current connection.")
        }
    }

    companion object {
        private val lastFailoverAt = java.util.concurrent.atomic.AtomicLong(0L)

        private fun acquireFailoverCooldown(intervalMs: Long): Boolean {
            val now = System.nanoTime() / 1_000_000
            while (true) {
                val previous = lastFailoverAt.get()
                if (previous != 0L && now - previous < intervalMs) return false
                if (lastFailoverAt.compareAndSet(previous, now)) return true
            }
        }

        internal fun eligibleFallbacks(profiles: List<VlessProfile>, current: VlessProfile): List<VlessProfile> = profiles.filter {
            com.example.vpn.engine.RuntimeCapabilities.unsupportedReason(it) == null &&
                it.id != current.id && it.effectiveFingerprint != current.effectiveFingerprint &&
                !(it.address.equals(current.address, ignoreCase = true) && it.port == current.port) &&
                !it.id.startsWith("bridge-") && !it.id.startsWith("mesh-")
        }
    }
}
