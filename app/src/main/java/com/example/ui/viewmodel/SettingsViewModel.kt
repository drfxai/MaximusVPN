package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.RayApplication
import com.example.core.SecretRedactor
import com.example.data.model.AppSettings
import com.example.data.model.EngineType
import com.example.data.model.RoutingMode
import com.example.data.model.ScoringProfile
import com.example.data.model.VlessProfile
import com.example.data.repository.ServerRepository
import com.example.data.repository.SettingsRepository
import com.example.xray.XrayConfigBuilder
import com.example.xray.XrayLogManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class SettingsViewModel(
    private val settingsRepository: SettingsRepository = RayApplication.instance.settingsRepository,
    private val serverRepository: ServerRepository = RayApplication.instance.serverRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settingsFlow

    val activeProfile: StateFlow<VlessProfile?> = combine(
        serverRepository.allProfiles,
        settingsRepository.settingsFlow
    ) { profiles, settings ->
        profiles.firstOrNull { it.id == settings.selectedProfileId }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    fun setDarkTheme(darkTheme: Boolean) {
        XrayLogManager.i("UI", "Theme changed: darkTheme = $darkTheme")
        settingsRepository.setDarkTheme(darkTheme)
    }

    fun toggleDarkTheme() {
        val current = settingsRepository.getSettings()
        val next = !current.darkTheme
        XrayLogManager.i("UI", "Toggled dark theme to: $next")
        settingsRepository.setDarkTheme(next)
    }

    fun setScoringProfile(profile: ScoringProfile) {
        val current = settingsRepository.getSettings()
        XrayLogManager.i("SETTINGS", "Scoring profile updated: ${current.scoringProfile.title} -> ${profile.title}")
        settingsRepository.updateSettings(current.copy(scoringProfile = profile))
    }

    fun setAutoFailover(enabled: Boolean) {
        val current = settingsRepository.getSettings()
        XrayLogManager.i("SETTINGS", "Auto failover updated: $enabled")
        settingsRepository.updateSettings(current.copy(autoFailoverEnabled = enabled))
    }

    fun setRoutingMode(mode: RoutingMode) {
        val current = settingsRepository.getSettings()
        XrayLogManager.i("SETTINGS", "Routing mode updated: ${current.routingMode.name} -> ${mode.name}")
        settingsRepository.updateSettings(current.copy(routingMode = mode))
    }

    fun setDnsServer(dns: String) {
        val current = settingsRepository.getSettings()
        XrayLogManager.i("SETTINGS", "DNS server updated: ${current.dnsServer} -> $dns")
        settingsRepository.updateSettings(current.copy(dnsServer = dns))
    }

    fun setCustomDns(dns: String) {
        val current = settingsRepository.getSettings()
        XrayLogManager.i("SETTINGS", "Custom DNS updated: $dns")
        settingsRepository.updateSettings(current.copy(customDns = dns))
    }

    fun setKillSwitch(enabled: Boolean) {
        val current = settingsRepository.getSettings()
        XrayLogManager.i("SETTINGS", "Kill switch updated: $enabled")
        settingsRepository.updateSettings(current.copy(killSwitchEnabled = enabled))
    }

    fun setIpv6(enabled: Boolean) {
        val current = settingsRepository.getSettings()
        XrayLogManager.i("SETTINGS", "IPv6 routing updated: $enabled")
        settingsRepository.updateSettings(current.copy(ipv6Enabled = enabled))
    }

    fun setAutoReconnect(enabled: Boolean) {
        val current = settingsRepository.getSettings()
        XrayLogManager.i("SETTINGS", "Auto-reconnect updated: $enabled")
        settingsRepository.updateSettings(current.copy(autoReconnect = enabled))
    }

    fun setPreferredEngine(engine: EngineType) {
        val current = settingsRepository.getSettings()
        XrayLogManager.i("SETTINGS", "Preferred engine updated: ${current.preferredEngine.displayName} -> ${engine.displayName}")
        settingsRepository.updateSettings(current.copy(preferredEngine = engine))
    }

    fun setMtu(mtu: Int) {
        val current = settingsRepository.getSettings()
        settingsRepository.updateSettings(current.copy(mtu = mtu.coerceIn(1280, 9000)))
    }

    fun setLogLevel(level: String) {
        val current = settingsRepository.getSettings()
        XrayLogManager.i("SETTINGS", "Log level updated: $level")
        settingsRepository.updateSettings(current.copy(logLevel = level))
    }

    fun setCustomBypassRules(rules: String) {
        val current = settingsRepository.getSettings()
        XrayLogManager.i("SETTINGS", "Custom bypass rules updated (${rules.lines().size} lines)")
        settingsRepository.updateSettings(current.copy(customBypassRules = rules))
    }

    suspend fun getPreviewConfigJson(): String {
        val currentSettings = settingsRepository.getSettings()
        val profile = currentSettings.selectedProfileId?.let { serverRepository.getProfileById(it) }
            ?: VlessProfile(
                name = "Preview Server",
                address = "example.com",
                port = 443,
                uuid = "00000000-0000-0000-0000-000000000000",
                transport = "tcp",
                security = "reality",
                sni = "example.com",
                publicKey = "SAMPLE_KEY",
                flow = "xtls-rprx-vision"
            )
        val rawJson = XrayConfigBuilder.buildJson(profile, currentSettings)
        return SecretRedactor.redact(rawJson)
    }

    fun updateSettings(newSettings: AppSettings) {
        settingsRepository.updateSettings(newSettings)
    }

    fun resetToDefaults() {
        settingsRepository.updateSettings(AppSettings())
    }
}
