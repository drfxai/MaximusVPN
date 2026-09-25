package com.example.vpn.engine

import com.example.data.model.ProtocolType
import com.example.data.model.ProfileType
import com.example.data.model.VlessProfile

/** Import compatibility is broader than the embedded forwarding runtime. */
object RuntimeCapabilities {
    fun unsupportedReason(profile: VlessProfile): String? = when {
        profile.profileType == ProfileType.MIHOMO_YAML ->
            "A bundled Mihomo configuration needs the native Mihomo core, which is not included. Select an individually imported supported node instead."
        profile.protocolType !in setOf(ProtocolType.VLESS, ProtocolType.TROJAN, ProtocolType.HTTP, ProtocolType.SOCKS5) ->
            "${profile.protocolType.displayName} needs a native proxy core, which is not included in this build."
        profile.transport.lowercase() !in setOf("tcp", "ws") ->
            "Transport ${profile.transport} is not supported by the embedded tunnel. Use TCP or WebSocket."
        profile.security.lowercase() !in setOf("", "none", "tls") ->
            "Security ${profile.security} requires a native proxy core."
        profile.fingerprint.equals("unsafe", ignoreCase = true) ->
            "Disabling TLS certificate verification is not supported."
        profile.flow.isNotBlank() -> "VLESS flow ${profile.flow} requires a native Xray core."
        profile.headerType.lowercase() !in setOf("", "none") -> "TCP header obfuscation is not supported by the embedded tunnel."
        profile.protocolType == ProtocolType.VLESS && profile.encryption.lowercase() !in setOf("", "none") ->
            "This VLESS encryption mode requires a native Xray core."
        profile.protocolType == ProtocolType.TROJAN && !profile.security.equals("tls", ignoreCase = true) ->
            "Trojan requires TLS; refusing to send credentials over an unencrypted transport."
        profile.protocolType in setOf(ProtocolType.HTTP, ProtocolType.SOCKS5) && profile.transport != "tcp" ->
            "HTTP and SOCKS proxies require TCP transport."
        profile.protocolType in setOf(ProtocolType.HTTP, ProtocolType.SOCKS5) && profile.uuid.isNotBlank() ->
            "Authenticated HTTP/SOCKS proxies are not supported by the embedded tunnel."
        else -> null
    }
    fun requireSupported(profile: VlessProfile) {
        unsupportedReason(profile)?.let { throw IllegalArgumentException(it) }
    }
}
