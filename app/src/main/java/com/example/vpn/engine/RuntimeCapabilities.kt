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
        profile.protocolType !in setOf(ProtocolType.VLESS, ProtocolType.TROJAN, ProtocolType.VMESS, ProtocolType.SHADOWSOCKS, ProtocolType.HTTP, ProtocolType.SOCKS5) ->
            "${profile.protocolType.displayName} is not supported by the bundled Xray core or Kotlin compatibility tunnel."
        profile.transport.lowercase() !in setOf("tcp", "ws", "grpc", "http", "h2") ->
            "Transport ${profile.transport} is not supported by the current Xray configuration adapter."
        profile.security.lowercase() !in setOf("", "none", "tls", "reality") ->
            "Security ${profile.security} is not supported by the bundled Xray core."
        profile.security.equals("reality", ignoreCase = true) && (profile.publicKey.isBlank() || profile.sni.isBlank()) ->
            "REALITY requires both the server public key and SNI."
        profile.fingerprint.equals("unsafe", ignoreCase = true) ->
            "Disabling TLS certificate verification is not supported."
        profile.headerType.lowercase() !in setOf("", "none", "http") -> "TCP header ${profile.headerType} is not supported by the Xray configuration adapter."
        profile.protocolType == ProtocolType.VLESS && profile.encryption.lowercase() !in setOf("", "none") ->
            "This VLESS encryption mode requires a native Xray core."
        profile.protocolType == ProtocolType.TROJAN && !profile.security.equals("tls", ignoreCase = true) ->
            "Trojan requires TLS; refusing to send credentials over an unencrypted transport."
        profile.flow.isNotBlank() && (profile.protocolType != ProtocolType.VLESS || profile.transport.lowercase() !in setOf("", "tcp") || profile.security.lowercase() !in setOf("tls", "reality")) ->
            "VLESS flow ${profile.flow} requires VLESS over TCP with TLS or REALITY."
        profile.protocolType in setOf(ProtocolType.HTTP, ProtocolType.SOCKS5) && profile.transport.lowercase() !in setOf("", "tcp") ->
            "HTTP and SOCKS proxies require TCP transport."
        profile.protocolType in setOf(ProtocolType.HTTP, ProtocolType.SOCKS5) && (profile.uuid.isNotBlank() || profile.security.lowercase() !in setOf("", "none")) ->
            "Authenticated HTTP/SOCKS proxies are not supported by the Kotlin compatibility tunnel."
        else -> null
    }
    fun requireSupported(profile: VlessProfile) {
        unsupportedReason(profile)?.let { throw IllegalArgumentException(it) }
    }
}
