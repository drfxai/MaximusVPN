package com.example.vpn.engine

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile

/** Selects the Kotlin packet tunnel for plaintext VLESS profiles that recent Xray cores reject on public IPs. */
object EngineSelectionPolicy {
    fun requiresKotlinTunnel(profile: VlessProfile): Boolean =
        profile.protocolType == ProtocolType.VLESS &&
            profile.transport.equals("tcp", ignoreCase = true) &&
            profile.security.isBlankOrNone() &&
            profile.encryption.isBlankOrNone() &&
            profile.flow.isBlank()

    private fun String.isBlankOrNone(): Boolean = isBlank() || equals("none", ignoreCase = true)
}
