package com.example.ui.diagnostics

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.ErrorSeverity
import com.example.data.model.ErrorSource
import com.example.ui.components.ThemeToggleSwitch
import com.example.ui.theme.AppTheme
import com.example.ui.viewmodel.DiagnosticsViewModel
import com.example.ui.viewmodel.SettingsViewModel

@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    settingsViewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val connState by viewModel.connectionState.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val healthState by viewModel.subsystemHealth.collectAsStateWithLifecycle()
    val tunnelTest by viewModel.tunnelConnectivity.collectAsStateWithLifecycle()
    val tunnelTestRunning by viewModel.isTunnelTestRunning.collectAsStateWithLifecycle()

    var logSearchQuery by remember { mutableStateOf("") }
    var showReportDialog by remember { mutableStateOf(false) }
    var selectedFilterCategory by remember { mutableStateOf("All") }
    val listState = rememberLazyListState()

    val filteredLogs = remember(logs, logSearchQuery) {
        if (logSearchQuery.isBlank()) logs
        else logs.filter { it.contains(logSearchQuery, ignoreCase = true) }
    }

    LaunchedEffect(logs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.scrollToItem(filteredLogs.size - 1)
        }
    }

    if (showReportDialog) {
        ReportIssueDialog(
            onDismiss = { showReportDialog = false },
            onSubmit = { source, severity, title, desc, notes ->
                viewModel.submitErrorReport(
                    source = source,
                    severity = severity,
                    title = title,
                    description = desc,
                    userNotes = notes
                )
                Toast.makeText(context, "Error report submitted & logged to telemetry", Toast.LENGTH_SHORT).show()
                showReportDialog = false
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Screen Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (onNavigateBack != null) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("diagnostics_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = AppTheme.colors.textPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Column {
                    Text("Diagnostics & Telemetry", color = AppTheme.colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Real-time engine logs & error reporting", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                }
            }

            ThemeToggleSwitch(
                isDark = settings.darkTheme,
                onThemeChange = { isDark ->
                    settingsViewModel.setDarkTheme(isDark)
                }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Button(
            onClick = { viewModel.testTunnelConnectivity() },
            enabled = !tunnelTestRunning,
            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.surfaceElevated),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().height(40.dp).testTag("test_tunnel_connectivity_button")
        ) {
            Text(
                text = if (tunnelTestRunning) "Testing tunneled internet…" else "Test end-to-end VPN connectivity",
                color = AppTheme.colors.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        tunnelTest?.let { result ->
            Text(
                text = if (result.reachable) {
                    "Tunnel traffic verified • HTTP ${result.httpStatus} • ${result.latencyMs} ms"
                } else {
                    "Tunnel test failed • ${result.errorMessage ?: "No response"}"
                },
                color = if (result.reachable) AppTheme.colors.statusConnected else AppTheme.colors.statusError,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp).testTag("tunnel_connectivity_result")
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Section Title: Engine Telemetry
        Text(
            text = "ENGINE TELEMETRY",
            color = AppTheme.colors.textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Action Buttons Row: Report Issue + Export Telemetry
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Report Error Button
            OutlinedButton(
                onClick = { showReportDialog = true },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = AppTheme.colors.statusError
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .testTag("report_issue_button")
            ) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = AppTheme.colors.statusError
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Report Error",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }

            // Export Report Button
            Button(
                onClick = {
                    val report = viewModel.generateDiagnosticReport()
                    val text = viewModel.formatReportText(report)
                    clipboardManager.setText(AnnotatedString(text))
                    Toast.makeText(context, "Sanitized report copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.surfaceElevated),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .testTag("export_diagnostics_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    tint = AppTheme.colors.primary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Export Report",
                    color = AppTheme.colors.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // System Telemetry Grid Card (Refactored to prevent horizontal text overflow)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DiagnosticGridCell(
                        title = "VpnService",
                        value = if (connState.isConnected) "ACTIVE" else "IDLE",
                        color = if (connState.isConnected) AppTheme.colors.statusConnected else AppTheme.colors.textMuted,
                        modifier = Modifier.weight(1f)
                    )
                    DiagnosticGridCell(
                        title = "Core Engine",
                        value = if (connState.isConnected) (connState.activeProfile?.engineType?.displayName ?: "XRAY") else "STANDBY",
                        color = if (connState.isConnected) AppTheme.colors.primary else AppTheme.colors.textMuted,
                        modifier = Modifier.weight(1f)
                    )
                    DiagnosticGridCell(
                        title = "Tunnel IP",
                        value = connState.vpnIp ?: "None",
                        color = AppTheme.colors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DiagnosticGridCell(
                        title = "Active Node",
                        value = connState.activeProfile?.name ?: "Disconnected",
                        color = AppTheme.colors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    DiagnosticGridCell(
                        title = "Latency",
                        value = connState.pingMs?.let { "${it}ms" } ?: "N/A",
                        color = if ((connState.pingMs ?: 999) < 200) AppTheme.colors.statusConnected else AppTheme.colors.statusWarning,
                        modifier = Modifier.weight(1f)
                    )
                    DiagnosticGridCell(
                        title = "Privacy Mode",
                        value = "REDACTED",
                        color = AppTheme.colors.statusConnected,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Subsystem Diagnostic Audit Bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceElevated),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.HealthAndSafety,
                        contentDescription = null,
                        tint = AppTheme.colors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text("Subsystem Health", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
                        Text("UI: ${healthState.uiState} • Engine: ${healthState.vpnEngineState}", fontSize = 10.sp, color = AppTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                TextButton(
                    onClick = { viewModel.runHealthCheck() },
                    modifier = Modifier.testTag("audit_subsystems_button")
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp), tint = AppTheme.colors.primary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Run Audit", fontSize = 11.sp, color = AppTheme.colors.primary, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Log Category Filter Chips with Live Counts
        val totalLogs = logs.size
        val errorLogsCount = remember(logs) { logs.count { it.contains("[ERROR]") || it.contains("[WARN]") || it.contains("[FATAL]") || it.contains("[CRASH]") || it.contains("[REPORT]") } }
        val vpnLogsCount = remember(logs) { logs.count { it.contains("[VPN]") || it.contains("[XRAY]") || it.contains("[MIHOMO]") || it.contains("[TUNNEL]") || it.contains("[DNS]") } }
        val serverLogsCount = remember(logs) { logs.count { it.contains("[SERVER]") || it.contains("[PING]") || it.contains("[FAILOVER]") } }
        val uiLogsCount = remember(logs) { logs.count { it.contains("[UI]") || it.contains("[APP]") || it.contains("[SETTINGS]") || it.contains("[PARSER]") } }

        val filterCategories = listOf(
            "All ($totalLogs)",
            "Errors & Reports ($errorLogsCount)",
            "VPN & Core ($vpnLogsCount)",
            "Server & Net ($serverLogsCount)",
            "UI & App ($uiLogsCount)"
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            filterCategories.forEach { categoryLabel ->
                val categoryKey = categoryLabel.substringBefore(" ")
                val isSelected = selectedFilterCategory == categoryKey
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) AppTheme.colors.primary.copy(alpha = 0.2f) else AppTheme.colors.surfaceCard)
                        .border(1.dp, if (isSelected) AppTheme.colors.primary else AppTheme.colors.borderSubtle, RoundedCornerShape(8.dp))
                        .clickable { selectedFilterCategory = categoryKey }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = categoryLabel,
                        color = if (isSelected) AppTheme.colors.primary else AppTheme.colors.textMuted,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Live Log Controls Header: Custom Non-clipping Search Bar + Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Sleek Custom Search Input Box
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppTheme.colors.surfaceCard)
                    .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = AppTheme.colors.textMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (logSearchQuery.isEmpty()) {
                            Text(
                                text = "Search logs & traces...",
                                color = AppTheme.colors.textMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = logSearchQuery,
                            onValueChange = { logSearchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = AppTheme.colors.textPrimary,
                                fontSize = 12.sp
                            ),
                            cursorBrush = SolidColor(AppTheme.colors.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (logSearchQuery.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear Search",
                            tint = AppTheme.colors.textMuted,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { logSearchQuery = "" }
                        )
                    }
                }
            }

            // Copy Logs Button
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppTheme.colors.surfaceCard)
                    .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(10.dp))
                    .clickable {
                        val allLogsText = logs.joinToString("\n")
                        clipboardManager.setText(AnnotatedString(allLogsText))
                        Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy Logs",
                    tint = AppTheme.colors.primary,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Clear Logs Button
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppTheme.colors.surfaceCard)
                    .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(10.dp))
                    .clickable { viewModel.clearLogs() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Clear Logs",
                    tint = AppTheme.colors.statusError,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Log Filtering Calculation
        val displayLogs = remember(logs, logSearchQuery, selectedFilterCategory) {
            logs.filter { line ->
                val matchesQuery = logSearchQuery.isBlank() || line.contains(logSearchQuery, ignoreCase = true)
                val matchesCategory = when (selectedFilterCategory) {
                    "Errors" -> line.contains("[ERROR]") || line.contains("[WARN]") || line.contains("[FATAL]") || line.contains("[CRASH]") || line.contains("[REPORT]")
                    "VPN" -> line.contains("[VPN]") || line.contains("[XRAY]") || line.contains("[MIHOMO]") || line.contains("[TUNNEL]") || line.contains("[DNS]") || line.contains("[ENGINE]")
                    "Server" -> line.contains("[SERVER]") || line.contains("[PING]") || line.contains("[FAILOVER]")
                    "UI" -> line.contains("[UI]") || line.contains("[APP]") || line.contains("[SETTINGS]") || line.contains("[PARSER]")
                    else -> true
                }
                matchesQuery && matchesCategory
            }
        }

        // Live Log Console Box
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.consoleBackground),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (displayLogs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (logSearchQuery.isNotBlank() || selectedFilterCategory != "All") "No logs matching current filter" else "No log entries captured yet.\nPerform an action or click 'Report Error' to log telemetry.",
                        color = AppTheme.colors.textMuted,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(displayLogs) { logLine ->
                        val textColor = when {
                            logLine.contains("[REPORT]") -> Color(0xFF38BDF8) // Bright Cyan for User & Backend Error Reports
                            logLine.contains("[FATAL]") || logLine.contains("[CRASH]") -> Color(0xFFFF3366)
                            logLine.contains("[ERROR]") -> AppTheme.colors.statusError
                            logLine.contains("[WARN]") -> AppTheme.colors.statusWarning
                            logLine.contains("[CONFIG]") || logLine.contains("[PARSER]") -> AppTheme.colors.metricDownload
                            logLine.contains("[TUNNEL]") || logLine.contains("[VPN]") || logLine.contains("[ENGINE]") -> AppTheme.colors.primary
                            logLine.contains("[SERVER]") || logLine.contains("[DNS]") -> Color(0xFF818CF8)
                            logLine.contains("[UI]") || logLine.contains("[APP]") -> Color(0xFFFBBF24)
                            else -> if (AppTheme.colors.isDark) AppTheme.colors.textSecondary else Color(0xFF94A3B8)
                        }
                        Text(
                            text = logLine,
                            color = textColor,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticGridCell(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            color = AppTheme.colors.textMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ReportIssueDialog(
    onDismiss: () -> Unit,
    onSubmit: (source: ErrorSource, severity: ErrorSeverity, title: String, description: String, userNotes: String) -> Unit
) {
    var selectedSource by remember { mutableStateOf(ErrorSource.UI) }
    var selectedSeverity by remember { mutableStateOf(ErrorSeverity.ERROR) }
    var issueTitle by remember { mutableStateOf("") }
    var issueDesc by remember { mutableStateOf("") }
    var userNotes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.colors.surfaceCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    tint = AppTheme.colors.statusError,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Report An Error",
                    color = AppTheme.colors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Submit error details for UI or Backend subsystem diagnostics.",
                    fontSize = 12.sp,
                    color = AppTheme.colors.textSecondary
                )

                // Error Source Selection
                Text("ERROR SOURCE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textMuted)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ErrorSource.entries.forEach { src ->
                        val isSel = selectedSource == src
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) AppTheme.colors.primary.copy(alpha = 0.2f) else AppTheme.colors.surfaceElevated)
                                .border(1.dp, if (isSel) AppTheme.colors.primary else AppTheme.colors.borderSubtle, RoundedCornerShape(8.dp))
                                .clickable { selectedSource = src }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = src.displayName.substringBefore(" "),
                                fontSize = 11.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSel) AppTheme.colors.primary else AppTheme.colors.textSecondary
                            )
                        }
                    }
                }

                // Severity Selection
                Text("SEVERITY", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textMuted)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ErrorSeverity.entries.forEach { sev ->
                        val isSel = selectedSeverity == sev
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) AppTheme.colors.statusError.copy(alpha = 0.2f) else AppTheme.colors.surfaceElevated)
                                .border(1.dp, if (isSel) AppTheme.colors.statusError else AppTheme.colors.borderSubtle, RoundedCornerShape(8.dp))
                                .clickable { selectedSeverity = sev }
                                .padding(vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = sev.levelTag,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSel) AppTheme.colors.statusError else AppTheme.colors.textMuted
                            )
                        }
                    }
                }

                // Title Input
                OutlinedTextField(
                    value = issueTitle,
                    onValueChange = { issueTitle = it },
                    label = { Text("Issue Title", fontSize = 11.sp) },
                    placeholder = { Text("e.g. Handshake failed / Screen freeze", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.colors.primary,
                        unfocusedBorderColor = AppTheme.colors.borderSubtle,
                        focusedTextColor = AppTheme.colors.textPrimary,
                        unfocusedTextColor = AppTheme.colors.textPrimary
                    )
                )

                // Description Input
                OutlinedTextField(
                    value = issueDesc,
                    onValueChange = { issueDesc = it },
                    label = { Text("Description & Error Log", fontSize = 11.sp) },
                    placeholder = { Text("Describe what happened or paste stack trace...", fontSize = 11.sp) },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.colors.primary,
                        unfocusedBorderColor = AppTheme.colors.borderSubtle,
                        focusedTextColor = AppTheme.colors.textPrimary,
                        unfocusedTextColor = AppTheme.colors.textPrimary
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (issueTitle.isNotBlank()) {
                        onSubmit(selectedSource, selectedSeverity, issueTitle, issueDesc, userNotes)
                    }
                },
                enabled = issueTitle.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary)
            ) {
                Text("Submit Report", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AppTheme.colors.textMuted)
            }
        }
    )
}
