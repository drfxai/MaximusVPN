package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.example.RayApplication
import com.example.core.SecretRedactor
import com.example.data.model.ConnectionStatus
import com.example.data.model.DiagnosticReport
import com.example.data.repository.SettingsRepository
import com.example.vpn.VpnController
import com.example.xray.XrayEngine
import com.example.xray.XrayEngineImpl
import com.example.xray.XrayLogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import com.example.data.model.ErrorReport
import com.example.data.model.ErrorSeverity
import com.example.data.model.ErrorSource
import com.example.data.model.SubsystemHealth

class DiagnosticsViewModel(
    private val settingsRepository: SettingsRepository = RayApplication.instance.settingsRepository,
    private val xrayEngine: XrayEngine = XrayEngineImpl.instance
) : ViewModel() {

    val logs: StateFlow<List<String>> = XrayLogManager.logsFlow
    val connectionState = VpnController.connectionState

    private val _reportedErrors = MutableStateFlow<List<ErrorReport>>(emptyList())
    val reportedErrors: StateFlow<List<ErrorReport>> = _reportedErrors.asStateFlow()

    private val _subsystemHealth = MutableStateFlow(SubsystemHealth())
    val subsystemHealth: StateFlow<SubsystemHealth> = _subsystemHealth.asStateFlow()

    fun clearLogs() {
        XrayLogManager.clear()
    }

    fun submitErrorReport(
        source: ErrorSource,
        severity: ErrorSeverity,
        title: String,
        description: String,
        userNotes: String = "",
        throwable: Throwable? = null
    ): ErrorReport {
        val sanitizedLogs = XrayLogManager.getLogs().takeLast(25)
        val report = ErrorReport(
            source = source,
            severity = severity,
            title = title,
            description = description,
            userNotes = userNotes,
            sanitizedLogs = sanitizedLogs
        )

        _reportedErrors.value = listOf(report) + _reportedErrors.value

        // Log directly into XrayLogManager with tagged category
        val logTag = "${source.tagPrefix}_REPORT"
        val logMessage = "[$title] $description ${if (userNotes.isNotBlank()) "(Note: $userNotes)" else ""}"
        
        when (severity) {
            ErrorSeverity.CRITICAL -> XrayLogManager.fatal(logTag, logMessage, throwable)
            ErrorSeverity.ERROR -> XrayLogManager.e(logTag, logMessage, throwable)
            ErrorSeverity.WARNING -> XrayLogManager.w(logTag, logMessage, throwable)
            ErrorSeverity.INFO -> XrayLogManager.i(logTag, logMessage)
        }

        return report
    }

    fun runHealthCheck(): SubsystemHealth {
        val conn = connectionState.value
        val settings = settingsRepository.getSettings()

        val uiState = "HEALTHY (Compose M3)"
        val vpnState = if (conn.isConnected) "CONNECTED (${conn.vpnIp})" else "IDLE (Ready)"
        val dnsState = if (settings.dnsServer.startsWith("https://")) "DoH configured; runtime status not measured" else "STANDARD (${settings.dnsServer})"
        val routingState = "Configured: ${settings.routingMode.title}; routing not tested"

        val health = SubsystemHealth(
            uiState = uiState,
            vpnEngineState = vpnState,
            dnsResolverState = dnsState,
            networkRoutingState = routingState,
            lastCheckedTimestamp = System.currentTimeMillis()
        )

        _subsystemHealth.value = health

        XrayLogManager.i("HEALTH_CHECK", "Subsystem audit completed: UI=$uiState | VPN=$vpnState | DNS=$dnsState | Routing=$routingState")
        return health
    }

    fun generateDiagnosticReport(): DiagnosticReport {
        val conn = connectionState.value
        val settings = settingsRepository.getSettings()
        val profile = conn.activeProfile

        val profileSummary = if (profile != null) {
            "${profile.name} (${profile.address}:${profile.port} • ${profile.transport.uppercase()}/${profile.security.ifBlank { "none" }.uppercase()})"
        } else {
            "No active server connected"
        }

        val engineName = if (conn.isVpnInterfaceActive) "Maximus Kotlin TunnelManager" else "Inactive"
        val protocolName = profile?.protocolType?.displayName ?: "None"

        return DiagnosticReport(
            appVersion = "Maximus v${com.example.BuildConfig.VERSION_NAME} (${com.example.BuildConfig.VERSION_CODE}, by DrFXAi)",
            vpnServiceRunning = conn.isVpnInterfaceActive,
            activeEngine = engineName,
            activeServerSummary = profileSummary,
            activeProtocol = protocolName,
            routingMode = settings.routingMode.title,
            dnsServer = settings.dnsServer,
            dohWorking = null,
            dnsLeakDetected = null,
            resolverIp = null,
            networkType = if (conn.isVpnInterfaceActive) "Android TUN (${conn.vpnIp ?: "unknown"}); end-to-end connectivity not tested" else "Direct Interface",
            lastLatencyMs = conn.pingMs,
            connectionState = conn.status.name,
            sanitizedLogs = XrayLogManager.getLogs(),
            lastError = conn.errorMessage
        )
    }

    fun formatReportText(report: DiagnosticReport): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date(report.generatedAt))
        val sb = StringBuilder()
        sb.appendLine("==========================================")
        sb.appendLine("        MAXIMUS VPN DIAGNOSTIC REPORT     ")
        sb.appendLine("               (By DrFXAi)                ")
        sb.appendLine("==========================================")
        sb.appendLine("Timestamp: $dateStr")
        sb.appendLine("App Version: ${report.appVersion}")
        sb.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE}, API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("VPN Service Status: ${if (report.vpnServiceRunning) "ACTIVE (TUN ESTABLISHED)" else "INACTIVE"}")
        sb.appendLine("Active Core Engine: ${report.activeEngine}")
        sb.appendLine("Active Protocol: ${report.activeProtocol}")
        sb.appendLine("Native Engine: Not integrated; forwarding uses Kotlin")
        sb.appendLine("Connection State: ${report.connectionState}")
        sb.appendLine("Active Server: ${report.activeServerSummary}")
        sb.appendLine("Configured Routing Mode: ${report.routingMode}")
        sb.appendLine("Configured DNS: ${report.dnsServer}")
        sb.appendLine("DoH Runtime Verification: ${report.dohWorking?.toString() ?: "Not measured"}")
        sb.appendLine("DNS Leak Test: ${report.dnsLeakDetected?.toString() ?: "Not performed"}")
        sb.appendLine("Network Type: ${report.networkType}")
        sb.appendLine("Latency: ${report.lastLatencyMs?.let { "${it}ms" } ?: "N/A"}")
        if (report.lastError != null) {
            sb.appendLine("Last Error Reported: ${report.lastError}")
        }
        sb.appendLine("\n--- SANITIZED LOG TRACE (UUIDs & CREDENTIALS REDACTED) ---")
        if (report.sanitizedLogs.isEmpty()) {
            sb.appendLine("[No log entries available]")
        } else {
            report.sanitizedLogs.takeLast(250).forEach { logLine ->
                sb.appendLine(SecretRedactor.redact(logLine))
            }
        }
        sb.appendLine("==========================================")
        sb.appendLine("END OF DIAGNOSTIC REPORT")
        sb.appendLine("==========================================")
        return SecretRedactor.redact(sb.toString())
    }
}
