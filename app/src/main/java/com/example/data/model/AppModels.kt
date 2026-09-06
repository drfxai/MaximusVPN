package com.example.data.model

data class TrafficStats(
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val txSpeedBps: Long = 0,
    val rxSpeedBps: Long = 0
) {
    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val z = (63 - java.lang.Long.numberOfLeadingZeros(bytes)) / 10
            return String.format(
                java.util.Locale.US,
                "%.1f %sB",
                bytes.toDouble() / (1L shl (z * 10)),
                " KMGTPE"[z]
            )
        }

        fun formatSpeed(bytesPerSec: Long): String {
            return "${formatBytes(bytesPerSec)}/s"
        }
    }
}

sealed class ServerTestStatus {
    data class Available(val latencyMs: Long) : ServerTestStatus()
    data class Slow(val latencyMs: Long) : ServerTestStatus()
    data class Unavailable(val reason: String) : ServerTestStatus()
    data class InvalidConfig(val error: String) : ServerTestStatus()
    object Testing : ServerTestStatus()
    object Idle : ServerTestStatus()
}

data class ServerTestResult(
    val serverId: String,
    val status: ServerTestStatus,
    val checkedAt: Long = System.currentTimeMillis()
)

enum class ServerCategory(val displayName: String, val badge: String, val iconText: String) {
    ELITE("Elite", "⚡ Elite", "⚡"),
    FAST("Fast", "🚀 Fast", "🚀"),
    BALANCED("Balanced", "⚖ Balanced", "⚖"),
    STABLE("Stable", "🟢 Stable", "🟢"),
    SLOW("Slow", "🟡 Slow", "🟡"),
    UNSTABLE("Unstable", "🔴 Unstable", "🔴"),
    OFFLINE("Offline", "⚫ Offline", "⚫");

    companion object {
        fun fromScoreAndHealth(overallScore: Double, latencyMs: Long?, packetLoss: Double, stability: Double): ServerCategory {
            if (latencyMs == null || latencyMs <= 0 || latencyMs > 4000 || packetLoss >= 60.0) {
                return OFFLINE
            }
            if (packetLoss >= 25.0 || stability < 40.0) {
                return UNSTABLE
            }
            return when {
                overallScore >= 88.0 && latencyMs < 120 && packetLoss < 2.0 && stability >= 85.0 -> ELITE
                overallScore >= 75.0 && latencyMs < 200 -> FAST
                overallScore >= 58.0 -> BALANCED
                stability >= 80.0 && packetLoss < 5.0 -> STABLE
                latencyMs > 350 || overallScore < 35.0 -> SLOW
                else -> BALANCED
            }
        }
    }
}

enum class ScoringProfile(val title: String, val description: String) {
    BALANCED("Balanced", "45% Speed, 30% Ping, 15% Stability, 10% Packet Loss"),
    GAMING("Gaming", "45% Latency, 25% Jitter, 20% Packet Loss, 10% Speed"),
    STREAMING("Streaming", "50% Bandwidth, 25% Stability, 15% Latency, 10% Packet Loss"),
    DOWNLOADING("Downloading", "65% Speed, 20% Stability, 10% Reliability, 5% Latency"),
    LOW_LATENCY("Low Latency", "60% Latency, 20% Jitter, 10% Packet Loss, 10% Speed"),
    MAX_STABILITY("Maximum Stability", "45% Stability, 30% Success Rate, 15% Packet Loss, 10% Latency")
}

enum class BenchmarkMode(val title: String, val description: String, val targetBytes: Long, val concurrency: Int) {
    QUICK("Quick Test", "Ping, Handshakes, TTFB (~1 MB)", 1_000_000L, 8),
    BALANCED("Balanced Test", "Speed, Ping, Jitter (~5 MB)", 5_000_000L, 6),
    DEEP("Deep Test", "Multi-sample Ping, Jitter, Packet Loss, Speed (~15 MB)", 15_000_000L, 4)
}

enum class Top10Category(val title: String, val subtitle: String) {
    OVERALL("TOP 10 OVERALL", "Highest combined performance scores"),
    FASTEST("TOP 10 FASTEST", "Top download & upload throughput"),
    LOWEST_PING("TOP 10 LOWEST PING", "Lowest network response latency"),
    MOST_STABLE("TOP 10 MOST STABLE", "Highest consistency and lowest jitter"),
    LOWEST_PACKET_LOSS("TOP 10 LOWEST PACKET LOSS", "Lowest dropped packet percentages"),
    GAMING("TOP 10 BEST FOR GAMING", "Optimized for latency, jitter & zero loss"),
    STREAMING("TOP 10 BEST FOR STREAMING", "Optimized for sustained high bitrate"),
    BALANCED("TOP 10 BEST BALANCED", "Well-rounded daily browsing profiles")
}

data class UniversalImportResult(
    val filesProcessed: Int = 0,
    val configurationsFound: Int = 0,
    val vlessCount: Int = 0,
    val vmessCount: Int = 0,
    val trojanCount: Int = 0,
    val hysteria2Count: Int = 0,
    val tuicCount: Int = 0,
    val shadowsocksCount: Int = 0,
    val socks5Count: Int = 0,
    val httpCount: Int = 0,
    val validCount: Int = 0,
    val duplicateCount: Int = 0,
    val invalidCount: Int = 0,
    val unsupportedCount: Int = 0,
    val validProfiles: List<VlessProfile> = emptyList(),
    val duplicateProfiles: List<VlessProfile> = emptyList(),
    val invalidEntries: List<String> = emptyList()
)

data class BenchmarkProgress(
    val total: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val duplicates: Int = 0,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val currentServerName: String = "",
    val activeWorkers: Int = 0,
    val estimatedBytes: Long = 0L,
    val mode: BenchmarkMode = BenchmarkMode.BALANCED
) {
    val remaining: Int get() = (total - completed).coerceAtLeast(0)
    val progressFraction: Float get() = if (total > 0) completed.toFloat() / total.toFloat() else 0f
}

data class BenchmarkStageResult(
    val serverId: String,
    val dnsLatencyMs: Long = 0,
    val tcpHandshakeMs: Long = 0,
    val tlsHandshakeMs: Long = 0,
    val proxyHandshakeMs: Long = 0,
    val ttfbMs: Long = 0,
    val pingMs: Long = 0,
    val downloadMbps: Double = 0.0,
    val uploadMbps: Double = 0.0,
    val jitterMs: Long = 0,
    val packetLossPercent: Double = 0.0,
    val successRatePercent: Double = 100.0,
    val stabilityPercent: Double = 100.0,
    val speedScore: Double = 0.0,
    val latencyScore: Double = 0.0,
    val stabilityScore: Double = 0.0,
    val reliabilityScore: Double = 0.0,
    val overallScore: Double = 0.0,
    val category: ServerCategory = ServerCategory.BALANCED,
    val isSuccess: Boolean = true,
    val errorMessage: String? = null,
    val testedAt: Long = System.currentTimeMillis()
)

enum class RoutingMode(val title: String, val description: String) {
    GLOBAL("Global Proxy", "Route all device network traffic through the proxy tunnel"),
    RULE_BYPASS_LAN("Bypass LAN & Direct Local", "Bypass local private subnets (192.168.x, 10.x) and route international traffic"),
    BYPASS_SELECTED("Custom Bypass List", "Bypass traffic matching specific user-defined domain and IP lists")
}

enum class ProfileType(val displayName: String, val badge: String) {
    VLESS("VLESS Link", "VLESS"),
    XRAY_JSON("Xray JSON", "XRAY JSON"),
    MIHOMO_YAML("Mihomo YAML", "MIHOMO"),
    SUBSCRIPTION("Subscription", "SUB")
}

enum class EngineType(val displayName: String) {
    XRAY("Xray-core"),
    MIHOMO("Mihomo/Meta"),
    AUTO("Auto Detect")
}

enum class ProtocolType(val displayName: String) {
    VLESS("VLESS"),
    HYSTERIA2("Hysteria2"),
    SHADOWSOCKS("Shadowsocks"),
    TROJAN("Trojan"),
    VMESS("VMess"),
    TUIC("TUIC"),
    HTTP("HTTP"),
    SOCKS5("SOCKS5"),
    MIXED("Mixed")
}

data class ProxyNode(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val type: ProtocolType = ProtocolType.VLESS,
    val server: String,
    val port: Int,
    val uuid: String = "",
    val password: String = "",
    val cipher: String = "none",
    val network: String = "tcp", // tcp, ws, grpc, http, h2
    val tls: Boolean = false,
    val reality: Boolean = false,
    val sni: String = "",
    val host: String = "",
    val path: String = "",
    val serviceName: String = "",
    val flow: String = "", // xtls-rprx-vision
    val fingerprint: String = "chrome",
    val publicKey: String = "", // REALITY pbk
    val shortId: String = "", // REALITY sid
    val spiderX: String = "", // REALITY spx
    val alpn: List<String> = emptyList(),
    val udp: Boolean = true,
    val latencyMs: Long? = null,
    val countryCode: String? = null
)

data class ProxyGroup(
    val name: String,
    val type: String, // select, url-test, fallback, load-balance
    val proxies: List<String> = emptyList(),
    val url: String = "http://www.gstatic.com/generate_204",
    val intervalSeconds: Int = 300,
    val toleranceMs: Int = 150,
    val selectedProxy: String? = null,
    val lazy: Boolean = true
)

data class DnsConfiguration(
    val servers: List<String> = listOf("https://8.8.8.8/dns-query", "https://1.1.1.1/dns-query", "8.8.8.8", "1.1.1.1"),
    val dohEnabled: Boolean = true,
    val defaultNameserver: String = "8.8.8.8",
    val domainStrategy: String = "IPIfNonMatch",
    val fallbackFilter: Boolean = true,
    val fakeIpEnabled: Boolean = false,
    val hijackDns: Boolean = true
)

data class RoutingConfiguration(
    val domainStrategy: String = "IPIfNonMatch",
    val rules: List<String> = emptyList(),
    val customBypassRules: List<String> = emptyList()
)

enum class OperationalMode(val displayName: String, val subtitle: String, val badge: String) {
    DAILY("Daily Mode", "High Speed • Cloudflare Workers & Reality CDN • Light/Balanced Desync", "⚡ DAILY"),
    GOD_MODE("GOD Mode", "Anti-Censorship Shield • Psiphon + Conduit • P2P Mesh • E2EE Chat", "🛡️ GOD MODE")
}

enum class DesyncMethod(val displayName: String, val description: String) {
    NONE("Off", "No packet desync applied"),
    SPLIT("Split Payload", "Fragments TLS ClientHello / TCP payload into small segments to bypass simple DPI"),
    DISORDER("Disorder Segments", "Sends fragmented segments with out-of-order sequence to disrupt middlebox reassembly"),
    FAKE_SNI("Fake SNI Injection", "Injects decoy SNI before real TLS ClientHello"),
    OOB("Out-of-Band (OOB)", "Injects TCP urgent/OOB byte to invalidate DPI stream inspection"),
    DISORDER_OOB("Disorder + OOB", "Combines out-of-order segment delivery with TCP urgent out-of-band byte")
}

enum class DesyncProfile(val displayName: String, val defaultMethod: DesyncMethod, val description: String) {
    OFF("Off", DesyncMethod.NONE, "Direct unfragmented transmission"),
    LIGHT("Light", DesyncMethod.SPLIT, "Splits TLS ClientHello at byte 2 to evade basic SNI filters"),
    BALANCED("Balanced", DesyncMethod.FAKE_SNI, "Split payload + Decoy Fake SNI domain injection"),
    SEVERE("Severe", DesyncMethod.DISORDER_OOB, "Full disordering + Out-of-Band urgent markers + Fake SNI"),
    ADAPTIVE("Adaptive", DesyncMethod.SPLIT, "Dynamically escalates desync depth upon packet loss or handshake reset"),
    CUSTOM("Custom", DesyncMethod.SPLIT, "User-configured split offset, fake SNI pool, and disorder params")
}

data class DesyncConfig(
    val enabled: Boolean = true,
    val profile: DesyncProfile = DesyncProfile.BALANCED,
    val method: DesyncMethod = DesyncMethod.FAKE_SNI,
    val splitPosition: Int = 2,
    val fakeSniPool: String = "www.cloudflare.com,www.google.com,speed.cloudflare.com,cdn.jsdelivr.net,www.bing.com,www.microsoft.com",
    val oobByte: Byte = 0x00,
    val customSni: String = "",
    val disorderCount: Int = 2
)

data class SubscriptionInfo(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val lastUpdated: Long = 0L,
    val autoRefresh: Boolean = true,
    val refreshIntervalMinutes: Int = 1440,
    val nodeCount: Int = 0,
    val lastError: String? = null
)

data class AppSettings(
    val darkTheme: Boolean = true,
    val operationalMode: OperationalMode = OperationalMode.DAILY,
    val routingMode: RoutingMode = RoutingMode.RULE_BYPASS_LAN,
    val dnsServer: String = "https://8.8.8.8/dns-query",
    val customDns: String = "1.1.1.1",
    val killSwitchEnabled: Boolean = false,
    val ipv6Enabled: Boolean = true,
    val autoReconnect: Boolean = true,
    val autoConnectOnBoot: Boolean = false,
    val logLevel: String = "warning",
    val selectedProfileId: String? = null,
    val preferredEngine: EngineType = EngineType.AUTO,
    val mtu: Int = 1500,
    val customBypassRules: String = "localhost,127.0.0.1,*.local,*.lan",
    val subscriptionUrls: String = "",
    val scoringProfile: ScoringProfile = ScoringProfile.BALANCED,
    val autoFailoverEnabled: Boolean = true,
    val failoverThresholdMs: Long = 800L,
    val failoverPacketLossThreshold: Double = 30.0,
    val benchmarkConcurrency: Int = 6,
    val defaultDesyncConfig: DesyncConfig = DesyncConfig(),
    val godModeMeshEnabled: Boolean = true,
    val godModePsiphonEnabled: Boolean = true,
    val godModeConduitEnabled: Boolean = true
)

data class DiagnosticReport(
    val generatedAt: Long = System.currentTimeMillis(),
    val appVersion: String,
    val vpnServiceRunning: Boolean,
    val activeEngine: String,
    val activeServerSummary: String,
    val activeProtocol: String,
    val routingMode: String,
    val dnsServer: String,
    val dohWorking: Boolean,
    val dnsLeakDetected: Boolean,
    val resolverIp: String?,
    val networkType: String,
    val lastLatencyMs: Long?,
    val connectionState: String,
    val sanitizedLogs: List<String>,
    val lastError: String?
)

enum class ErrorSource(val displayName: String, val tagPrefix: String) {
    UI("UI Layer / Frontend", "UI"),
    BACKEND("Backend / Core Controller", "BACKEND"),
    CORE_ENGINE("Xray & Mihomo Engines", "ENGINE"),
    NETWORK("VPN Tunnel & Sockets", "VPN"),
    PARSER("Config & Subscription Parsers", "PARSER")
}

enum class ErrorSeverity(val displayName: String, val levelTag: String) {
    CRITICAL("Fatal / Crash", "FATAL"),
    ERROR("Error / Failed Operation", "ERROR"),
    WARNING("Warning / Degradation", "WARN"),
    INFO("Diagnostic Note", "INFO")
}

data class ErrorReport(
    val id: String = java.util.UUID.randomUUID().toString().take(8),
    val timestamp: Long = System.currentTimeMillis(),
    val source: ErrorSource,
    val severity: ErrorSeverity,
    val title: String,
    val description: String,
    val userNotes: String = "",
    val sanitizedLogs: List<String> = emptyList(),
    val deviceModel: String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
    val androidVersion: String = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
)

data class SubsystemHealth(
    val uiState: String = "HEALTHY",
    val vpnEngineState: String = "READY",
    val dnsResolverState: String = "SECURE",
    val networkRoutingState: String = "OPTIMAL",
    val lastCheckedTimestamp: Long = System.currentTimeMillis()
)

