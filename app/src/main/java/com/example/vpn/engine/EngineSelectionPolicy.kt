package com.example.vpn.engine

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile

/** Describes profiles forwarded by the Kotlin packet loop instead of the native Xray TUN. */
object EngineSelectionPolicy {
    /** Profiles forwarded by TunnelManager, which currently understands IPv4 packets only. */
    fun usesKotlinPacketTunnel(profile: VlessProfile): Boolean =
        requiresKotlinTunnel(profile) || profile.protocolType in setOf(ProtocolType.HTTP, ProtocolType.SOCKS5)

    fun requiresKotlinTunnel(profile: VlessProfile): Boolean =
        profile.protocolType == ProtocolType.VLESS &&
            profile.transport.equals("tcp", ignoreCase = true) &&
            profile.security.isBlankOrNone() &&
            profile.encryption.isBlankOrNone() &&
            profile.flow.isBlank()

    private fun String.isBlankOrNone(): Boolean = isBlank() || equals("none", ignoreCase = true)
}
