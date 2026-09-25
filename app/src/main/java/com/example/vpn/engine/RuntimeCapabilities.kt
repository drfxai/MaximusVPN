package com.example.vpn.engine

import com.example.data.model.ProtocolType
import com.example.data.model.ProfileType
import com.example.data.model.VlessProfile

/** Import compatibility is broader than the Xray-core and Kotlin forwarding runtimes. */
object RuntimeCapabilities {
    fun unsupportedReason(profile: VlessProfile): String? = when {
        profile.profileType == ProfileType.MIHOMO_YAML ->
            "A bundled Mihomo configuration needs the native Mihomo core, which is not included. Select an Xray-compatible node instead."
        profile.profileType == ProfileType.XRAY_JSON -> null
        profile.protocolType !in setOf(ProtocolType.VLESS, ProtocolType.TROJAN, ProtocolType.VMESS, ProtocolType.SHADOWSOCKS) ->
            "${profile.protocolType.displayName} is not supported by the bundled Xray core."
        profile.transport.lowercase() !in setOf("tcp", "ws", "grpc", "http", "h2") ->
            "Transport ${profile.transport} is not supported by the current Xray configuration adapter."
        profile.security.lowercase() !in setOf("", "none", "tls", "reality") ->
            "Security ${profile.security} is not supported by the bundled Xray core."
        profile.fingerprint.equals("unsafe", ignoreCase = true) ->
            "Disabling TLS certificate verification is not supported."
        profile.headerType.lowercase() !in setOf("", "none", "http") -> "TCP header ${profile.headerType} is not supported by the Xray configuration adapter."
        profile.protocolType == ProtocolType.VLESS && profile.encryption.lowercase() !in setOf("", "none") ->
            "This VLESS encryption mode requires a native Xray core."
        profile.protocolType == ProtocolType.TROJAN && !profile.security.equals("tls", ignoreCase = true) ->
            "Trojan requires TLS; refusing to send credentials over an unencrypted transport."
        else -> null
    }
    fun requireSupported(profile: VlessProfile) {
        unsupportedReason(profile)?.let { throw IllegalArgumentException(it) }
    }
}
