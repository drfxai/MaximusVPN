package com.example.ui.home

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.model.ConnectionStatus
import com.example.data.model.TrafficStats
import com.example.data.model.VlessProfile
import com.example.ui.components.ConnectionButton
import com.example.ui.components.LatencyPill
import com.example.ui.components.StatusBadge
import com.example.ui.components.ThemeToggleSwitch
import com.example.ui.theme.AppTheme
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.VpnViewModel
import com.example.vpn.smart.SmartConnect
import java.util.Locale

@Composable
fun HomeScreen(
    vpnViewModel: VpnViewModel,
    settingsViewModel: SettingsViewModel,
    onNavigateToServers: () -> Unit,
    onRequestVpnPermission: () -> Unit,
    onNavigateToSecretChat: () -> Unit = {},
    onNavigateToGodBrowser: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val connectionState by vpnViewModel.connectionState.collectAsStateWithLifecycle()
    val activeProfile by vpnViewModel.selectedProfile.collectAsStateWithLifecycle()
    val smartRecommendation by vpnViewModel.smartRecommendation.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Top Bar: Branding on Left, Theme Toggle Switch on Right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0D0E15))
                        .border(1.dp, AppTheme.colors.borderMedium, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_maximus_logo),
                        contentDescription = "Maximus Spartan App Logo",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Maximus",
                        color = AppTheme.colors.textPrimary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "DrFXAi • Maximus VPN Core",
                        color = AppTheme.colors.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Top Sun/Moon Interactive Theme Toggle
            ThemeToggleSwitch(
                isDark = settings.darkTheme,
                onThemeChange = { isDark ->
                    settingsViewModel.setDarkTheme(isDark)
                }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Professional Mode Switcher Card (Daily Mode vs GOD Mode)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (settings.operationalMode == com.example.data.model.OperationalMode.GOD_MODE) {
                    Color(0xFF2E0F16)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                }
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (settings.operationalMode == com.example.data.model.OperationalMode.GOD_MODE) Color(0xFFE53935).copy(alpha = 0.6f) else AppTheme.colors.borderSubtle
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (settings.operationalMode == com.example.data.model.OperationalMode.GOD_MODE) Color(0xFFD32F2F) else AppTheme.colors.primary
                        ) {
                            Text(
                                text = settings.operationalMode.badge,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = settings.operationalMode.displayName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = AppTheme.colors.textPrimary
                        )
                    }

                    // Mode Toggle Switch Button
                    TextButton(
                        onClick = {
                            val nextMode = if (settings.operationalMode == com.example.data.model.OperationalMode.DAILY) {
                                com.example.data.model.OperationalMode.GOD_MODE
                            } else {
                                com.example.data.model.OperationalMode.DAILY
                            }
                            settingsViewModel.updateSettings(settings.copy(operationalMode = nextMode))
                            if (nextMode == com.example.data.model.OperationalMode.GOD_MODE) {
                                com.example.vpn.godmode.PsiphonConduitBridge.enableGodModeBridges()
                                com.example.vpn.godmode.MaximusMeshManager.startMesh()
                            } else {
                                com.example.vpn.godmode.PsiphonConduitBridge.disableGodModeBridges()
                                com.example.vpn.godmode.MaximusMeshManager.stopMesh()
                            }
                        }
                    ) {
                        Text(
                            text = if (settings.operationalMode == com.example.data.model.OperationalMode.DAILY) "Switch to GOD Mode ➔" else "Switch to Daily Mode ➔",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (settings.operationalMode == com.example.data.model.OperationalMode.GOD_MODE) Color(0xFFFF8A80) else AppTheme.colors.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = settings.operationalMode.subtitle,
                    fontSize = 11.sp,
                    color = AppTheme.colors.textSecondary
                )

                // Quick Tools & Cascade Path when GOD MODE is Active
                if (settings.operationalMode == com.example.data.model.OperationalMode.GOD_MODE) {
                    val bridges by com.example.vpn.godmode.PsiphonConduitBridge.bridgesStateFlow.collectAsStateWithLifecycle()
                    val meshPeers by com.example.vpn.godmode.MaximusMeshManager.peersFlow.collectAsStateWithLifecycle()
                    val verifiedBridgesCount = bridges.count { it.isVerified || it.latencyMs != null }

                    Spacer(modifier = Modifier.height(10.dp))

                    // God Mode Cascade Path & Live Network Mesh Indicator
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1A0A0F),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE53935).copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "CASCADE FAILOVER LADDER",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFF8A80),
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    text = "Bridges: $verifiedBridgesCount/${bridges.size} | Mesh: ${meshPeers.size} Peers",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFFCC80)
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "1. VLESS Reality ➔ 2. Hysteria2 ➔ 3. Psiphon/Conduit ➔ 4. P2P Mesh",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE0E0E0)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onNavigateToSecretChat,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B1FA2)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Secret Chat", fontSize = 11.sp)
                        }

                        Button(
                            onClick = onNavigateToGodBrowser,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Hardened Browser", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Connection State Pill / Badge Sub-header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusBadge(status = connectionState.status)
            LatencyPill(latencyMs = connectionState.pingMs ?: activeProfile?.lastLatencyMs)
        }

        val connectedProfile = connectionState.activeProfile ?: activeProfile
        if (connectionState.isConnected && connectedProfile?.security?.let { it.isBlank() || it.equals("none", ignoreCase = true) } == true) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2410)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB74D).copy(alpha = 0.55f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFB74D))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "This profile has no TLS/REALITY encryption between this device and the VPN server. Use a TLS or REALITY profile for a confidential connection.",
                        color = AppTheme.colors.textPrimary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Smart Connect Hero Card
        smartRecommendation?.let { smart ->
            SmartRecommendationCard(
                smart = smart,
                isConnected = connectionState.isConnected,
                onSmartConnect = {
                    vpnViewModel.connectSmart(context) {
                        onRequestVpnPermission()
                    }
                }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Error message banner if failed
        AnimatedVisibility(
            visible = connectionState.status == ConnectionStatus.FAILED && connectionState.errorMessage != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (AppTheme.colors.isDark) Color(0xFF241416) else Color(0xFFFEF2F2)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.statusError.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Error",
                        tint = AppTheme.colors.statusError,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = connectionState.errorMessage ?: "Connection failed",
                        color = AppTheme.colors.statusError,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // Connection Timer & Status HUD Pill
        val durationFormatted = formatDuration(connectionState.connectedDurationSeconds)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (connectionState.isConnected) {
                    AppTheme.colors.statusConnected.copy(alpha = 0.12f)
                } else {
                    AppTheme.colors.surfaceElevated.copy(alpha = 0.5f)
                },
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (connectionState.isConnected) {
                        AppTheme.colors.statusConnected.copy(alpha = 0.35f)
                    } else {
                        AppTheme.colors.borderSubtle
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (connectionState.isConnected) AppTheme.colors.statusConnected
                                else AppTheme.colors.textMuted
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (connectionState.isConnected) "VPN CONNECTED" else "DISCONNECTED",
                        color = if (connectionState.isConnected) AppTheme.colors.statusConnected else AppTheme.colors.textSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (connectionState.isConnected) durationFormatted else "00:00:00",
                color = if (connectionState.isConnected) AppTheme.colors.textPrimary else AppTheme.colors.textMuted,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.5.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Central Power Button
        ConnectionButton(
            status = connectionState.status,
            onClick = {
                vpnViewModel.toggleConnection(context) {
                    onRequestVpnPermission()
                }
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Active Server Card
        ServerSelectorCard(
            profile = activeProfile,
            pingMs = connectionState.pingMs,
            onClick = onNavigateToServers
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Real-Time Traffic Dashboard Grid
        TrafficDashboardCard(
            uploadBytes = connectionState.uploadBytes,
            downloadBytes = connectionState.downloadBytes,
            uploadSpeedBps = connectionState.uploadSpeedBps,
            downloadSpeedBps = connectionState.downloadSpeedBps,
            vpnIp = connectionState.vpnIp ?: "172.19.0.1",
            isConnected = connectionState.isConnected,
            activeProfile = activeProfile
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SmartRecommendationCard(
    smart: SmartConnect.SmartSelection,
    isConnected: Boolean,
    onSmartConnect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("smart_connect_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "SMART RECOMMENDATION",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "Score: %.1f".format(smart.overallScore),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = smart.profile.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Ping: ${smart.reasonPing}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.NetworkCheck,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Speed: ${smart.reasonDownload}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onSmartConnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .testTag("smart_connect_button"),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(imageVector = Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isConnected) "Switch to Optimal Node" else "Smart Connect (Recommended)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ServerSelectorCard(
    profile: VlessProfile?,
    pingMs: Long?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(if (AppTheme.colors.isDark) 2.dp else 4.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .testTag("selected_server_card"),
        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(AppTheme.colors.surfaceElevated)
                        .border(1.dp, AppTheme.colors.borderMedium, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = "Server",
                        tint = AppTheme.colors.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile?.name ?: "No Server Selected",
                        color = AppTheme.colors.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = profile?.displaySubtitle ?: "Tap to choose a proxy server",
                        color = AppTheme.colors.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                LatencyPill(latencyMs = pingMs ?: profile?.lastLatencyMs)
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Change Server",
                    tint = AppTheme.colors.textMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun TrafficDashboardCard(
    uploadBytes: Long,
    downloadBytes: Long,
    uploadSpeedBps: Long,
    downloadSpeedBps: Long,
    vpnIp: String,
    isConnected: Boolean,
    activeProfile: VlessProfile?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(if (AppTheme.colors.isDark) 2.dp else 4.dp, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "NETWORK TELEMETRY",
                color = AppTheme.colors.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Download Metric
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(AppTheme.colors.metricDownload)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Download",
                            tint = AppTheme.colors.metricDownload,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("DOWNLOAD", color = AppTheme.colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isConnected) TrafficStats.formatSpeed(downloadSpeedBps) else "0.0 B/s",
                        color = AppTheme.colors.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Total: ${TrafficStats.formatBytes(downloadBytes)}",
                        color = AppTheme.colors.textMuted,
                        fontSize = 11.sp
                    )
                }

                // Upload Metric
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(AppTheme.colors.metricUpload)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Upload",
                            tint = AppTheme.colors.metricUpload,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("UPLOAD", color = AppTheme.colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isConnected) TrafficStats.formatSpeed(uploadSpeedBps) else "0.0 B/s",
                        color = AppTheme.colors.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Total: ${TrafficStats.formatBytes(uploadBytes)}",
                        color = AppTheme.colors.textMuted,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AppTheme.colors.borderSubtle)
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Tunnel IP and Protocol details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Protocol",
                        tint = AppTheme.colors.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Protocol: ${activeProfile?.protocolType?.displayName ?: "VLESS"}",
                        color = AppTheme.colors.textSecondary,
                        fontSize = 11.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = "IP",
                        tint = AppTheme.colors.metricDownload,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Tunnel: $vpnIp",
                        color = AppTheme.colors.textSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
}
