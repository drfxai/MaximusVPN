package com.example.vpn.engine

import com.example.data.model.EngineType
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.mihomo.MihomoParser
import com.example.vless.VlessParser
import com.example.xray.XrayConfigParser
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

object ConfigurationAdapter {

    enum class DetectedFormat {
        VLESS_LINK,
        SHADOWSOCKS_LINK,
        TROJAN_LINK,
        HYSTERIA2_LINK,
        XRAY_JSON,
        MIHOMO_YAML,
        SUBSCRIPTION_URL,
        UNKNOWN
    }

    fun detectFormat(rawInput: String): DetectedFormat {
        val trimmed = rawInput.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return DetectedFormat.SUBSCRIPTION_URL
        }
        if (trimmed.startsWith("vless://", ignoreCase = true)) {
            return DetectedFormat.VLESS_LINK
        }
        if (trimmed.startsWith("ss://", ignoreCase = true)) {
            return DetectedFormat.SHADOWSOCKS_LINK
        }
        if (trimmed.startsWith("trojan://", ignoreCase = true)) {
            return DetectedFormat.TROJAN_LINK
        }
        if (trimmed.startsWith("hysteria2://", ignoreCase = true) || trimmed.startsWith("hy2://", ignoreCase = true)) {
            return DetectedFormat.HYSTERIA2_LINK
        }
        if (trimmed.startsWith("{") && (trimmed.contains("\"outbounds\"") || trimmed.contains("\"inbounds\"") || trimmed.contains("\"log\""))) {
            return DetectedFormat.XRAY_JSON
        }
        if (trimmed.contains("proxies:") || trimmed.contains("proxy-groups:") || trimmed.contains("mixed-port:") || trimmed.contains("port:")) {
            return DetectedFormat.MIHOMO_YAML
        }
        return DetectedFormat.UNKNOWN
    }

    /**
     * Parses any supported input format into a list of usable VlessProfile models.
     */
    fun importConfiguration(rawInput: String, sourceUrl: String? = null): List<VlessProfile> {
        val trimmed = rawInput.trim()
        return when (detectFormat(trimmed)) {
            DetectedFormat.VLESS_LINK -> {
                val res = VlessParser.parse(trimmed)
                if (res is com.example.core.AppResult.Success) listOf(res.data) else emptyList()
            }
            DetectedFormat.SHADOWSOCKS_LINK -> {
                parseShadowsocksUri(trimmed)?.let { listOf(it) } ?: emptyList()
            }
            DetectedFormat.TROJAN_LINK -> {
                parseTrojanUri(trimmed)?.let { listOf(it) } ?: emptyList()
            }
            DetectedFormat.HYSTERIA2_LINK -> {
                parseHysteria2Uri(trimmed)?.let { listOf(it) } ?: emptyList()
            }
            DetectedFormat.XRAY_JSON -> {
                XrayConfigParser.parseJson(trimmed)
            }
            DetectedFormat.MIHOMO_YAML -> {
                MihomoParser.toVlessProfiles(trimmed, sourceUrl)
            }
            else -> {
                if (trimmed.startsWith("{")) {
                    XrayConfigParser.parseJson(trimmed)
                } else if (trimmed.contains("proxies:") || trimmed.contains("port:")) {
                    MihomoParser.toVlessProfiles(trimmed, sourceUrl)
                } else {
                    val res = VlessParser.parse(trimmed)
                    if (res is com.example.core.AppResult.Success) listOf(res.data) else emptyList()
                }
            }
        }
    }

    private fun safeDecodeUrl(str: String?): String {
        if (str.isNullOrBlank()) return ""
        return try {
            URLDecoder.decode(str, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            str
        }
    }

    fun parseShadowsocksUri(uriString: String): VlessProfile? {
        return try {
            val mainPart = uriString.substringAfter("ss://")
            val fragment = if (mainPart.contains("#")) mainPart.substringAfter("#") else null
            val rawConfig = if (mainPart.contains("#")) mainPart.substringBefore("#") else mainPart

            var name = if (!fragment.isNullOrBlank()) {
                safeDecodeUrl(fragment)
            } else "Shadowsocks"

            var method = "aes-128-gcm"
            var password = ""
            var host = ""
            var port = 8388

            if (rawConfig.contains("@")) {
                // SIP002 style: ss://BASE64(method:password)@host:port or ss://method:password@host:port
                val userInfoPart = rawConfig.substringBefore("@")
                val hostPortPart = rawConfig.substringAfter("@")

                if (userInfoPart.contains(":")) {
                    val parts = userInfoPart.split(":", limit = 2)
                    method = parts[0]
                    password = parts[1]
                } else {
                    val decodedUserInfo = decodeBase64Safe(userInfoPart)
                    if (decodedUserInfo.contains(":")) {
                        val parts = decodedUserInfo.split(":", limit = 2)
                        method = parts[0]
                        password = parts[1]
                    }
                }

                if (hostPortPart.contains(":")) {
                    host = hostPortPart.substringBefore(":")
                    port = hostPortPart.substringAfter(":").toIntOrNull() ?: 8388
                } else {
                    host = hostPortPart
                }
            } else {
                // Legacy style: ss://BASE64(method:password@host:port)
                val decoded = decodeBase64Safe(rawConfig)
                if (decoded.contains("@")) {
                    val userInfoPart = decoded.substringBefore("@")
                    val hostPortPart = decoded.substringAfter("@")

                    if (userInfoPart.contains(":")) {
                        val parts = userInfoPart.split(":", limit = 2)
                        method = parts[0]
                        password = parts[1]
                    }

                    if (hostPortPart.contains(":")) {
                        host = hostPortPart.substringBefore(":")
                        port = hostPortPart.substringAfter(":").toIntOrNull() ?: 8388
                    } else {
                        host = hostPortPart
                    }
                } else {
                    return null
                }
            }

            if (host.isBlank()) return null
            if (name == "Shadowsocks") name = "Shadowsocks-$host"

            VlessProfile(
                id = UUID.randomUUID().toString(),
                name = name,
                address = host,
                port = port,
                uuid = password,
                encryption = method,
                transport = "tcp",
                security = "none",
                profileType = ProfileType.VLESS,
                protocolType = ProtocolType.SHADOWSOCKS,
                engineType = EngineType.XRAY,
                nodeCount = 1
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBase64Safe(input: String): String {
        return try {
            val clean = input.replace("-", "+").replace("_", "/")
            val padLen = (4 - (clean.length % 4)) % 4
            val padded = clean + "=".repeat(padLen)
            String(java.util.Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private data class ParsedUriComponents(
        val userInfo: String,
        val host: String,
        val port: Int,
        val query: String,
        val fragment: String
    )

    private fun extractUriComponents(uriString: String, scheme: String): ParsedUriComponents? {
        val trimmed = uriString.trim()
        val prefix = "$scheme://"
        if (!trimmed.startsWith(prefix, ignoreCase = true)) return null
        val withoutScheme = trimmed.substring(prefix.length)

        val fragmentIdx = withoutScheme.indexOf('#')
        val beforeFragment = if (fragmentIdx != -1) withoutScheme.substring(0, fragmentIdx) else withoutScheme
        val fragment = if (fragmentIdx != -1) withoutScheme.substring(fragmentIdx + 1) else ""

        val queryIdx = beforeFragment.indexOf('?')
        val mainPart = if (queryIdx != -1) beforeFragment.substring(0, queryIdx) else beforeFragment
        val queryString = if (queryIdx != -1) beforeFragment.substring(queryIdx + 1) else ""

        val atIdx = mainPart.indexOf('@')
        val userInfo = if (atIdx != -1) mainPart.substring(0, atIdx) else ""
        val hostPort = if (atIdx != -1) mainPart.substring(atIdx + 1) else mainPart

        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            val closing = hostPort.indexOf(']')
            if (closing == -1) return null
            host = hostPort.substring(1, closing)
            val after = hostPort.substring(closing + 1)
            val colon = after.indexOf(':')
            port = if (colon != -1) after.substring(colon + 1).toIntOrNull() ?: 443 else 443
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon != -1) {
                host = hostPort.substring(0, colon)
                port = hostPort.substring(colon + 1).toIntOrNull() ?: 443
            } else {
                host = hostPort
                port = 443
            }
        }
        if (host.isBlank()) return null
        return ParsedUriComponents(userInfo, host, port, queryString, fragment)
    }

    fun parseTrojanUri(uriString: String): VlessProfile? {
        return try {
            val comp = extractUriComponents(uriString, "trojan") ?: return null
            val host = comp.host
            val port = if (comp.port in 1..65535) comp.port else 443
            val password = comp.userInfo
            if (password.isBlank()) return null

            val name = if (comp.fragment.isNotBlank()) {
                safeDecodeUrl(comp.fragment)
            } else "Trojan-$host"

            val params = parseQueryParams(comp.query)
            val sni = params["sni"] ?: params["peer"] ?: host
            val transport = params["type"] ?: "tcp"
            val security = if (params["security"] != null) params["security"]!! else "tls"

            VlessProfile(
                id = UUID.randomUUID().toString(),
                name = name,
                address = host,
                port = port,
                uuid = password,
                encryption = "none",
                transport = transport,
                security = security,
                sni = sni,
                path = params["path"] ?: "",
                host = params["host"] ?: sni,
                serviceName = params["serviceName"] ?: "",
                alpn = params["alpn"] ?: "",
                profileType = ProfileType.VLESS,
                protocolType = ProtocolType.TROJAN,
                engineType = EngineType.XRAY,
                nodeCount = 1
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseHysteria2Uri(uriString: String): VlessProfile? {
        return try {
            val comp = extractUriComponents(uriString, "hysteria2")
                ?: extractUriComponents(uriString, "hy2")
                ?: return null
            val host = comp.host
            val port = if (comp.port in 1..65535) comp.port else 443
            val password = comp.userInfo
            if (password.isBlank()) return null

            val name = if (comp.fragment.isNotBlank()) {
                safeDecodeUrl(comp.fragment)
            } else "Hysteria2-$host"

            val params = parseQueryParams(comp.query)
            val sni = params["sni"] ?: host

            VlessProfile(
                id = UUID.randomUUID().toString(),
                name = name,
                address = host,
                port = port,
                uuid = password,
                encryption = "none",
                transport = "udp",
                security = "tls",
                sni = sni,
                alpn = params["alpn"] ?: "h3",
                profileType = ProfileType.VLESS,
                protocolType = ProtocolType.HYSTERIA2,
                engineType = EngineType.MIHOMO,
                nodeCount = 1
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseQueryParams(query: String?): Map<String, String> {
        if (query.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = safeDecodeUrl(pair.substring(0, idx))
                val value = safeDecodeUrl(pair.substring(idx + 1))
                result[key] = value
            }
        }
        return result
    }
}
