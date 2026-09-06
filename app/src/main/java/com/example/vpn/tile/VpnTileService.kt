package com.example.vpn.tile

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.example.MainActivity
import com.example.R
import com.example.data.database.AppDatabase
import com.example.data.model.ConnectionStatus
import com.example.data.repository.ServerRepository
import com.example.data.repository.SettingsRepository
import com.example.vpn.RayVpnService
import com.example.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            RayVpnService.vpnState.collectLatest { state ->
                updateTileState(state.status, state.activeProfile?.name)
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val currentState = RayVpnService.vpnState.value

        if (currentState.isConnected || currentState.status == ConnectionStatus.CONNECTING) {
            VpnController.stopVpn(applicationContext)
        } else {
            val prepIntent = VpnController.prepareVpn(applicationContext)
            if (prepIntent != null) {
                // Needs user permission; launch MainActivity
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivityAndCollapse(intent)
            } else {
                serviceScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getInstance(applicationContext)
                    val serverRepo = ServerRepository(db.serverProfileDao())
                    val settingsRepo = SettingsRepository(applicationContext)
                    val settings = settingsRepo.getSettings()

                    val targetProfile = if (settings.selectedProfileId != null) {
                        serverRepo.getProfileById(settings.selectedProfileId)
                    } else {
                        serverRepo.getAllProfilesOnce().firstOrNull()
                    }

                    if (targetProfile != null) {
                        VpnController.startVpn(applicationContext, targetProfile)
                    } else {
                        val intent = Intent(this@VpnTileService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivityAndCollapse(intent)
                    }
                }
            }
        }
    }

    private fun updateTileState(status: ConnectionStatus, profileName: String?) {
        val tile = qsTile ?: return

        when (status) {
            ConnectionStatus.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Maximus VPN"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = profileName ?: "Connected"
                }
            }
            ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "Maximus VPN"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Connecting..."
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Maximus VPN"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Disconnected"
                }
            }
        }

        tile.updateTile()
    }
}
