package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.RayApplication
import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.VlessProfile
import com.example.data.repository.ServerRepository
import com.example.data.repository.SettingsRepository
import com.example.vpn.RayVpnService
import com.example.vpn.VpnController
import com.example.vpn.smart.SmartConnect
import com.example.xray.XrayLogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VpnViewModel(
    private val serverRepository: ServerRepository = RayApplication.instance.serverRepository,
    private val settingsRepository: SettingsRepository = RayApplication.instance.settingsRepository
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = VpnController.connectionState

    private val _pendingProfile = MutableStateFlow<VlessProfile?>(null)
    val pendingProfile: StateFlow<VlessProfile?> = _pendingProfile.asStateFlow()

    val selectedProfile: StateFlow<VlessProfile?> = combine(
        serverRepository.allProfiles,
        settingsRepository.settingsFlow
    ) { profiles, settings ->
        if (profiles.isEmpty()) return@combine null
        val targetId = settings.selectedProfileId
        profiles.firstOrNull { it.id == targetId } ?: profiles.first()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    val smartRecommendation: StateFlow<SmartConnect.SmartSelection?> = combine(
        serverRepository.allProfiles,
        settingsRepository.settingsFlow
    ) { profiles, settings ->
        SmartConnect.selectBestNode(profiles, settings.scoringProfile)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    fun prepareConnect(profile: VlessProfile?) {
        _pendingProfile.value = profile
        RayVpnService.updateState(
            VpnController.connectionState.value.copy(
                status = ConnectionStatus.PREPARING,
                activeProfile = profile ?: selectedProfile.value
            )
        )
    }

    fun startOrRequestPermission(
        context: Context,
        profile: VlessProfile?,
        onRequestPermission: (Intent) -> Unit
    ) {
        val targetProfile = profile ?: selectedProfile.value ?: smartRecommendation.value?.profile
        val prepareIntent = VpnController.prepareVpn(context)
        if (prepareIntent != null) {
            XrayLogManager.i("VPN", "VPN permission required before connecting '${targetProfile?.name ?: "default"}'")
            prepareConnect(targetProfile)
            onRequestPermission(prepareIntent)
        } else if (targetProfile != null) {
            VpnController.startVpn(context, targetProfile)
        } else {
            viewModelScope.launch {
                val fallback = serverRepository.getAllProfilesOnce().firstOrNull()
                if (fallback != null) {
                    VpnController.startVpn(context, fallback)
                } else {
                    RayVpnService.updateState(
                        VpnController.connectionState.value.copy(
                            status = ConnectionStatus.FAILED,
                            errorMessage = "No server profile available to connect."
                        )
                    )
                }
            }
        }
    }

    fun onPermissionGranted(context: Context) {
        viewModelScope.launch {
            val profileToConnect = _pendingProfile.value
                ?: selectedProfile.value
                ?: smartRecommendation.value?.profile
                ?: serverRepository.getAllProfilesOnce().firstOrNull()

            _pendingProfile.value = null

            if (profileToConnect != null) {
                XrayLogManager.i("VPN", "Permission granted by user. Explicitly starting VPN tunnel for '${profileToConnect.name}'")
                VpnController.startVpn(context, profileToConnect)
            } else {
                XrayLogManager.w("VPN", "Permission granted but no profile available.")
                RayVpnService.updateState(
                    VpnController.connectionState.value.copy(
                        status = ConnectionStatus.FAILED,
                        errorMessage = "No server profile available after permission grant."
                    )
                )
            }
        }
    }

    fun onPermissionDenied() {
        _pendingProfile.value = null
        XrayLogManager.w("VPN", "User denied VPN permission dialog.")
        RayVpnService.updateState(
            VpnController.connectionState.value.copy(
                status = ConnectionStatus.FAILED,
                errorMessage = "VPN permission was denied by user."
            )
        )
    }

    fun toggleConnection(context: Context, onRequestPermission: ((Intent) -> Unit)? = null) {
        val currentState = connectionState.value
        if (currentState.isConnected || currentState.isBusy) {
            VpnController.stopVpn(context)
        } else {
            val targetProfile = selectedProfile.value ?: smartRecommendation.value?.profile
            startOrRequestPermission(context, targetProfile) { intent ->
                onRequestPermission?.invoke(intent)
            }
        }
    }

    fun connectSmart(context: Context, onRequestPermission: ((Intent) -> Unit)? = null) {
        val smartProfile = smartRecommendation.value?.profile
        if (smartProfile != null) {
            selectServer(smartProfile)
            startOrRequestPermission(context, smartProfile) { intent ->
                onRequestPermission?.invoke(intent)
            }
        } else {
            toggleConnection(context, onRequestPermission)
        }
    }

    fun selectServer(profile: VlessProfile) {
        settingsRepository.setSelectedProfileId(profile.id)
    }

    fun reconnect(context: Context) {
        VpnController.reconnectVpn(context)
    }
}
