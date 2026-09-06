package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.AppSettings
import com.example.data.model.EngineType
import com.example.data.model.RoutingMode
import com.example.data.model.ScoringProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("raytunnel_settings", Context.MODE_PRIVATE)

    private val _settingsFlow = MutableStateFlow(loadSettings())
    val settingsFlow: StateFlow<AppSettings> = _settingsFlow.asStateFlow()

    private fun loadSettings(): AppSettings {
        val routingModeName = prefs.getString("routing_mode", RoutingMode.RULE_BYPASS_LAN.name) ?: RoutingMode.RULE_BYPASS_LAN.name
        val routingMode = try {
            RoutingMode.valueOf(routingModeName)
        } catch (_: Exception) {
            RoutingMode.RULE_BYPASS_LAN
        }

        val preferredEngineName = prefs.getString("preferred_engine", EngineType.AUTO.name) ?: EngineType.AUTO.name
        val preferredEngine = try {
            EngineType.valueOf(preferredEngineName)
        } catch (_: Exception) {
            EngineType.AUTO
        }

        val scoringProfileName = prefs.getString("scoring_profile", ScoringProfile.BALANCED.name) ?: ScoringProfile.BALANCED.name
        val scoringProfile = try {
            ScoringProfile.valueOf(scoringProfileName)
        } catch (_: Exception) {
            ScoringProfile.BALANCED
        }

        val operationalModeName = prefs.getString("operational_mode", AppSettings().operationalMode.name) ?: AppSettings().operationalMode.name
        val operationalMode = try {
            com.example.data.model.OperationalMode.valueOf(operationalModeName)
        } catch (_: Exception) {
            com.example.data.model.OperationalMode.DAILY
        }

        val desyncProfileName = prefs.getString("desync_profile", "BALANCED") ?: "BALANCED"
        val desyncProfile = try {
            com.example.data.model.DesyncProfile.valueOf(desyncProfileName)
        } catch (_: Exception) {
            com.example.data.model.DesyncProfile.BALANCED
        }

        val desyncMethodName = prefs.getString("desync_method", "FAKE_SNI") ?: "FAKE_SNI"
        val desyncMethod = try {
            com.example.data.model.DesyncMethod.valueOf(desyncMethodName)
        } catch (_: Exception) {
            com.example.data.model.DesyncMethod.FAKE_SNI
        }

        val desyncConfig = com.example.data.model.DesyncConfig(
            enabled = prefs.getBoolean("desync_enabled", true),
            profile = desyncProfile,
            method = desyncMethod,
            splitPosition = prefs.getInt("desync_split_pos", 2),
            fakeSniPool = prefs.getString("desync_fake_sni", "www.cloudflare.com,www.google.com,speed.cloudflare.com,cdn.jsdelivr.net,www.bing.com,www.microsoft.com") ?: "www.cloudflare.com,www.google.com",
            customSni = prefs.getString("desync_custom_sni", "") ?: ""
        )

        return AppSettings(
            darkTheme = prefs.getBoolean("dark_theme", true),
            operationalMode = operationalMode,
            routingMode = routingMode,
            dnsServer = prefs.getString("dns_server", "https://8.8.8.8/dns-query") ?: "https://8.8.8.8/dns-query",
            customDns = prefs.getString("custom_dns", "1.1.1.1") ?: "1.1.1.1",
            killSwitchEnabled = prefs.getBoolean("kill_switch", false),
            ipv6Enabled = prefs.getBoolean("ipv6_enabled", true),
            autoReconnect = prefs.getBoolean("auto_reconnect", true),
            autoConnectOnBoot = prefs.getBoolean("auto_boot", false),
            logLevel = prefs.getString("log_level", "warning") ?: "warning",
            selectedProfileId = prefs.getString("selected_profile_id", null),
            preferredEngine = preferredEngine,
            mtu = prefs.getInt("mtu", 1500),
            customBypassRules = prefs.getString("bypass_rules", "localhost,127.0.0.1,*.local,*.lan") ?: "",
            scoringProfile = scoringProfile,
            autoFailoverEnabled = prefs.getBoolean("auto_failover", true),
            failoverThresholdMs = prefs.getLong("failover_threshold", 800L),
            failoverPacketLossThreshold = prefs.getFloat("failover_loss", 30f).toDouble(),
            benchmarkConcurrency = prefs.getInt("benchmark_concurrency", 6),
            defaultDesyncConfig = desyncConfig,
            godModeMeshEnabled = prefs.getBoolean("god_mode_mesh", true),
            godModePsiphonEnabled = prefs.getBoolean("god_mode_psiphon", true),
            godModeConduitEnabled = prefs.getBoolean("god_mode_conduit", true)
        )
    }

    fun getSettings(): AppSettings = _settingsFlow.value

    fun updateSettings(settings: AppSettings) {
        prefs.edit()
            .putBoolean("dark_theme", settings.darkTheme)
            .putString("operational_mode", settings.operationalMode.name)
            .putString("routing_mode", settings.routingMode.name)
            .putString("dns_server", settings.dnsServer)
            .putString("custom_dns", settings.customDns)
            .putBoolean("kill_switch", settings.killSwitchEnabled)
            .putBoolean("ipv6_enabled", settings.ipv6Enabled)
            .putBoolean("auto_reconnect", settings.autoReconnect)
            .putBoolean("auto_boot", settings.autoConnectOnBoot)
            .putString("log_level", settings.logLevel)
            .putString("selected_profile_id", settings.selectedProfileId)
            .putString("preferred_engine", settings.preferredEngine.name)
            .putInt("mtu", settings.mtu)
            .putString("bypass_rules", settings.customBypassRules)
            .putString("scoring_profile", settings.scoringProfile.name)
            .putBoolean("auto_failover", settings.autoFailoverEnabled)
            .putLong("failover_threshold", settings.failoverThresholdMs)
            .putFloat("failover_loss", settings.failoverPacketLossThreshold.toFloat())
            .putInt("benchmark_concurrency", settings.benchmarkConcurrency)
            .putBoolean("desync_enabled", settings.defaultDesyncConfig.enabled)
            .putString("desync_profile", settings.defaultDesyncConfig.profile.name)
            .putString("desync_method", settings.defaultDesyncConfig.method.name)
            .putInt("desync_split_pos", settings.defaultDesyncConfig.splitPosition)
            .putString("desync_fake_sni", settings.defaultDesyncConfig.fakeSniPool)
            .putString("desync_custom_sni", settings.defaultDesyncConfig.customSni)
            .putBoolean("god_mode_mesh", settings.godModeMeshEnabled)
            .putBoolean("god_mode_psiphon", settings.godModePsiphonEnabled)
            .putBoolean("god_mode_conduit", settings.godModeConduitEnabled)
            .apply()

        _settingsFlow.value = settings
    }

    fun setOperationalMode(mode: com.example.data.model.OperationalMode) {
        val current = _settingsFlow.value
        updateSettings(current.copy(operationalMode = mode))
    }

    fun setDarkTheme(darkTheme: Boolean) {
        val current = _settingsFlow.value
        updateSettings(current.copy(darkTheme = darkTheme))
    }

    fun setSelectedProfileId(id: String?) {
        val current = _settingsFlow.value
        updateSettings(current.copy(selectedProfileId = id))
    }

    fun setScoringProfile(profile: ScoringProfile) {
        val current = _settingsFlow.value
        updateSettings(current.copy(scoringProfile = profile))
    }
}
