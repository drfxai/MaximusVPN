package com.example.vpn.engine

import android.util.Base64
import com.example.core.AppResult
import com.example.data.model.CanonicalFingerprint
import com.example.data.model.EngineType
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.UniversalImportResult
import com.example.data.model.VlessProfile
import com.example.mihomo.MihomoParser
import com.example.vless.VlessParser
import com.example.xray.XrayConfigParser
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

object UniversalImportEngine {

    /**
     * Imports a single string or multi-line configuration text (or raw file content).
     */
    fun importText(rawText: String, sourceFileName: String? = null, sourceSubscriptionUrl: String? = null): UniversalImportResult {
        return importMultipleSources(listOf(ImportSource(rawText, sourceFileName, sourceSubscriptionUrl)))
    }

    data class ImportSource(
        val content: String,
        val fileName: String? = null,
        val subscriptionUrl: String? = null
    )

    /**
     * Imports multiple files, URLs, or strings simultaneously, performing parsing,
     * normalization, protocol breakdown, and canonical fingerprint deduplication.
     */
    fun importMultipleSources(sources: List<ImportSource>): UniversalImportResult {
        var totalConfigsFound = 0
        var vlessCount = 0
        var vmessCount = 0
        var trojanCount = 0
        var hysteria2Count = 0
        var tuicCount = 0
        var shadowsocksCount = 0
        var socks5Count = 0
        var httpCount = 0
        var invalidCount = 0
        var unsupportedCount = 0

        val seenFingerprints = mutableSetOf<String>()
        val validProfiles = mutableListOf<VlessProfile>()
        val duplicateProfiles = mutableListOf<VlessProfile>()
        val invalidEntries = mutableListOf<String>()

        for (source in sources) {
            val content = source.content.trim()
            if (content.isBlank()) continue

            val rawProfiles = parseRawSource(content, source.fileName, source.subscriptionUrl)
            totalConfigsFound += rawProfiles.size

            for (parsedItem in rawProfiles) {
                when (parsedItem) {
                    is ParsedItem.Success -> {
                        val profile = parsedItem.profile
                        when (profile.protocolType) {
                            ProtocolType.VLESS -> vlessCount++
                            ProtocolType.VMESS -> vmessCount++
                            ProtocolType.TROJAN -> trojanCount++
                            ProtocolType.HYSTERIA2 -> hysteria2Count++
                            ProtocolType.TUIC -> tuicCount++
                            ProtocolType.SHADOWSOCKS -> shadowsocksCount++
                            ProtocolType.SOCKS5 -> socks5Count++
                            ProtocolType.HTTP -> httpCount++
                            ProtocolType.MIXED -> {}
                        }

                        val fp = if (profile.canonicalFingerprint.isNotBlank()) {
                            profile.canonicalFingerprint
                        } else {
                            CanonicalFingerprint.computeFromProfile(profile)
                        }

                        val normalized = profile.copy(
                            canonicalFingerprint = fp,
                            sourceFile = source.fileName,
                            sourceSubscription = source.subscriptionUrl
                        )

                        if (seenFingerprints.contains(fp)) {
                            duplicateProfiles.add(normalized)
                        } else {
                            seenFingerprints.add(fp)
                            validProfiles.add(normalized)
                        }
                    }
                    is ParsedItem.Invalid -> {
                        invalidCount++
                        invalidEntries.add("${parsedItem.reason}: ${parsedItem.rawSnippet}")
                    }
                    is ParsedItem.Unsupported -> {
                        unsupportedCount++
                        invalidEntries.add("Unsupported protocol/format: ${parsedItem.rawSnippet}")
                    }
                }
            }
        }

        return UniversalImportResult(
            filesProcessed = sources.size,
            configurationsFound = totalConfigsFound,
            vlessCount = vlessCount,
            vmessCount = vmessCount,
            trojanCount = trojanCount,
            hysteria2Count = hysteria2Count,
            tuicCount = tuicCount,
            shadowsocksCount = shadowsocksCount,
            socks5Count = socks5Count,
            httpCount = httpCount,
            validCount = validProfiles.size,
            duplicateCount = duplicateProfiles.size,
            invalidCount = invalidCount,
            unsupportedCount = unsupportedCount,
            validProfiles = validProfiles,
            duplicateProfiles = duplicateProfiles,
            invalidEntries = invalidEntries
        )
    }

    sealed class ParsedItem {
        data class Success(val profile: VlessProfile) : ParsedItem()
        data class Invalid(val rawSnippet: String, val reason: String) : ParsedItem()
        data class Unsupported(val rawSnippet: String) : ParsedItem()
    }

    private fun parseRawSource(rawContent: String, fileName: String?, subUrl: String?): List<ParsedItem> {
        val trimmed = rawContent.trim()

        // 1. Check if Base64 encoded subscription/bundle
        if (!trimmed.contains(" ") && !trimmed.contains("\n") && isLikelyBase64(trimmed)) {
            val decoded = decodeBase64Safe(trimmed)
            if (decoded != null && decoded.isNotBlank() && decoded != trimmed) {
                return parseRawSource(decoded, fileName, subUrl)
            }
        }

        // 2. Check if JSON (Xray or Sing-box)
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return parseJsonConfig(trimmed, fileName, subUrl)
        }

        // 3. Check if YAML (Mihomo / Clash)
        if (trimmed.contains("proxies:") || trimmed.contains("proxy-groups:") || trimmed.contains("mixed-port:")) {
            return parseYamlConfig(trimmed, subUrl)
        }

        // 4. Line-by-line URI parsing (VLESS, VMess, Trojan, SS, HY2, TUIC, SOCKS5, HTTP)
        val lines = trimmed.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("//") && !it.startsWith("#") }

        val items = mutableListOf<ParsedItem>()
        for (line in lines) {
            items.add(parseSingleUri(line, fileName, subUrl))
        }
        return items
    }

    private fun parseSingleUri(uriString: String, fileName: String?, subUrl: String?): ParsedItem {
        val lower = uriString.lowercase()
        return when {
            lower.startsWith("vless://") -> {
                when (val res = VlessParser.parse(uriString)) {
                    is AppResult.Success -> ParsedItem.Success(res.data.copy(sourceFile = fileName, sourceSubscription = subUrl))
                    is AppResult.Error -> ParsedItem.Invalid(uriString.take(60), res.userFriendlyMessage)
                }
            }
            lower.startsWith("vmess://") -> {
                parseVmessUri(uriString, fileName, subUrl)
            }
            lower.startsWith("trojan://") -> {
                parseTrojanUri(uriString, fileName, subUrl)
            }
            lower.startsWith("ss://") -> {
                parseShadowsocksUri(uriString, fileName, subUrl)
            }
            lower.startsWith("hysteria2://") || lower.startsWith("hy2://") -> {
                parseHysteria2Uri(uriString, fileName, subUrl)
            }
            lower.startsWith("tuic://") -> {
                parseTuicUri(uriString, fileName, subUrl)
            }
            lower.startsWith("socks5://") || lower.startsWith("socks://") -> {
                parseSocks5Uri(uriString, fileName, subUrl)
            }
            lower.startsWith("http://") || lower.startsWith("https://") -> {
                // If it's a proxy link
                if (uriString.contains("@") && !uriString.contains(" ")) {
                    parseHttpProxyUri(uriString, fileName, subUrl)
                } else {
                    ParsedItem.Invalid(uriString.take(60), "HTTP URL is a subscription endpoint or web link")
                }
            }
            else -> {
                ParsedItem.Unsupported(uriString.take(60))
            }
        }
    }

    fun parseVmessUri(uriString: String, fileName: String? = null, subUrl: String? = null): ParsedItem {
        return try {
            val base64Data = uriString.substringAfter("vmess://").trim()
            val decodedJson = decodeBase64Safe(base64Data)
                ?: return ParsedItem.Invalid(uriString.take(60), "Invalid VMess Base64 encoding")

            val json = JSONObject(decodedJson)
            val add = json.optString("add")
            val port = json.optInt("port", 443)
            val id = json.optString("id")
            val aid = json.optInt("aid", 0)
            val net = json.optString("net", "tcp")
            val type = json.optString("type", "none")
            val host = json.optString("host", "")
            val path = json.optString("path", "")
            val tls = json.optString("tls", "")
            val sni = json.optString("sni", host)
            val ps = json.optString("ps", "VMess-$add")

            if (add.isBlank() || id.isBlank() || port <= 0) {
                return ParsedItem.Invalid(uriString.take(60), "VMess missing server or UUID")
            }

            val profile = VlessProfile(
                id = UUID.randomUUID().toString(),
                name = ps,
                address = add,
                port = port,
                uuid = id,
                encryption = "auto",
                transport = net.lowercase(),
                security = if (tls.equals("tls", ignoreCase = true)) "tls" else "none",
                sni = sni,
                host = host,
                path = path,
                headerType = type,
                profileType = ProfileType.VLESS,
                protocolType = ProtocolType.VMESS,
                engineType = EngineType.XRAY,
                sourceFile = fileName,
                sourceSubscription = subUrl,
                nodeCount = 1
            )
            ParsedItem.Success(profile)
        } catch (e: Exception) {
            ParsedItem.Invalid(uriString.take(60), "Failed to parse VMess JSON: ${e.message}")
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

    private data class ParsedUriComponents(
        val scheme: String,
        val userInfo: String,
        val host: String,
        val port: Int,
        val query: String,
        val fragment: String
    )

    private fun extractUriComponents(uriString: String, vararg validSchemes: String): ParsedUriComponents? {
        val trimmed = uriString.trim()
        val schemeSep = trimmed.indexOf("://")
        if (schemeSep == -1) return null
        val scheme = trimmed.substring(0, schemeSep).lowercase()
        if (validSchemes.isNotEmpty() && !validSchemes.any { it.equals(scheme, ignoreCase = true) }) {
            return null
        }
        val withoutScheme = trimmed.substring(schemeSep + 3)

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
            port = if (colon != -1) after.substring(colon + 1).toIntOrNull() ?: -1 else -1
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon != -1) {
                host = hostPort.substring(0, colon)
                port = hostPort.substring(colon + 1).toIntOrNull() ?: -1
            } else {
                host = hostPort
                port = -1
            }
        }
        return ParsedUriComponents(scheme, userInfo, host, port, queryString, fragment)
    }

    fun parseTrojanUri(uriString: String, fileName: String? = null, subUrl: String? = null): ParsedItem {
        return try {
            val comp = extractUriComponents(uriString, "trojan")
                ?: return ParsedItem.Invalid(uriString.take(60), "Malformed Trojan URI structure")
            val password = comp.userInfo
            if (password.isBlank() || comp.host.isBlank()) {
                return ParsedItem.Invalid(uriString.take(60), "Trojan link missing password or host")
            }
            val name = if (comp.fragment.isNotBlank()) {
                safeDecodeUrl(comp.fragment)
            } else "Trojan-${comp.host}"

            val params = parseQueryParams(comp.query)
            val sni = params["sni"] ?: params["peer"] ?: comp.host
            val transport = params["type"] ?: "tcp"
            val security = params["security"] ?: "tls"
            val path = params["path"] ?: ""
            val alpn = params["alpn"] ?: ""

            val profile = VlessProfile(
                id = UUID.randomUUID().toString(),
                name = name,
                address = comp.host,
                port = if (comp.port in 1..65535) comp.port else 443,
                uuid = password,
                encryption = "none",
                transport = transport,
                security = security,
                sni = sni,
                path = path,
                host = params["host"] ?: sni,
                alpn = alpn,
                profileType = ProfileType.VLESS,
                protocolType = ProtocolType.TROJAN,
                engineType = EngineType.XRAY,
                sourceFile = fileName,
                sourceSubscription = subUrl,
                nodeCount = 1
            )
            ParsedItem.Success(profile)
        } catch (e: Exception) {
            ParsedItem.Invalid(uriString.take(60), "Malformed Trojan link: ${e.message}")
        }
    }

    private fun parseHostPort(rawHostPort: String, defaultPort: Int = 8388): Pair<String, Int> {
        val trimmed = rawHostPort.trim()
        if (trimmed.startsWith("[")) {
            val closing = trimmed.indexOf(']')
            if (closing != -1) {
                val host = trimmed.substring(1, closing)
                val after = trimmed.substring(closing + 1)
                val colon = after.indexOf(':')
                val port = if (colon != -1) after.substring(colon + 1).toIntOrNull() ?: defaultPort else defaultPort
                return Pair(host, port)
            }
        }
        val colon = trimmed.lastIndexOf(':')
        return if (colon != -1) {
            val host = trimmed.substring(0, colon)
            val port = trimmed.substring(colon + 1).toIntOrNull() ?: defaultPort
            Pair(host, port)
        } else {
            Pair(trimmed, defaultPort)
        }
    }

    fun parseShadowsocksUri(uriString: String, fileName: String? = null, subUrl: String? = null): ParsedItem {
        return try {
            val withoutScheme = uriString.substringAfter("ss://").trim()
            val fragmentIdx = withoutScheme.indexOf('#')
            val name = if (fragmentIdx != -1) {
                safeDecodeUrl(withoutScheme.substring(fragmentIdx + 1))
            } else "Shadowsocks"

            val mainPart = if (fragmentIdx != -1) withoutScheme.substring(0, fragmentIdx) else withoutScheme

            var method = "aes-128-gcm"
            var password = ""
            var host = ""
            var port = 8388

            if (mainPart.contains("@")) {
                val userAndServer = mainPart.split("@", limit = 2)
                var userInfo = userAndServer[0]
                val decodedUser = decodeBase64Safe(userInfo)
                if (decodedUser != null && decodedUser.contains(":")) {
                    userInfo = decodedUser
                }
                val uParts = userInfo.split(":", limit = 2)
                method = uParts[0]
                password = if (uParts.size > 1) uParts[1] else ""

                val hostPort = userAndServer[1].substringBefore("?")
                val (parsedHost, parsedPort) = parseHostPort(hostPort, 8388)
                host = parsedHost
                port = parsedPort
            } else {
                val decoded = decodeBase64Safe(mainPart)
                    ?: return ParsedItem.Invalid(uriString.take(60), "Invalid Shadowsocks Base64 encoding")
                val atIdx = decoded.indexOf("@")
                if (atIdx != -1) {
                    val uParts = decoded.substring(0, atIdx).split(":", limit = 2)
                    method = uParts[0]
                    password = if (uParts.size > 1) uParts[1] else ""
                    val (parsedHost, parsedPort) = parseHostPort(decoded.substring(atIdx + 1), 8388)
                    host = parsedHost
                    port = parsedPort
                } else {
                    return ParsedItem.Invalid(uriString.take(60), "Malformed Shadowsocks payload")
                }
            }

            if (host.isBlank() || password.isBlank()) {
                return ParsedItem.Invalid(uriString.take(60), "Shadowsocks missing host or password")
            }

            val profile = VlessProfile(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { "SS-$host" },
                address = host,
                port = port,
                uuid = password,
                encryption = method,
                transport = "tcp",
                security = "none",
                profileType = ProfileType.VLESS,
                protocolType = ProtocolType.SHADOWSOCKS,
                engineType = EngineType.XRAY,
                sourceFile = fileName,
                sourceSubscription = subUrl,
                nodeCount = 1
            )
            ParsedItem.Success(profile)
        } catch (e: Exception) {
            ParsedItem.Invalid(uriString.take(60), "Failed to parse Shadowsocks link: ${e.message}")
        }
    }

    fun parseHysteria2Uri(uriString: String, fileName: String? = null, subUrl: String? = null): ParsedItem {
        return try {
            val comp = extractUriComponents(uriString, "hysteria2", "hy2")
                ?: return ParsedItem.Invalid(uriString.take(60), "Malformed Hysteria2 URI structure")
            val auth = comp.userInfo
            if (comp.host.isBlank()) {
                return ParsedItem.Invalid(uriString.take(60), "Hysteria2 link missing server host")
            }
            val name = if (comp.fragment.isNotBlank()) {
                safeDecodeUrl(comp.fragment)
            } else "Hysteria2-${comp.host}"

            val params = parseQueryParams(comp.query)
            val sni = params["sni"] ?: comp.host
            val alpn = params["alpn"] ?: "h3"

            val profile = VlessProfile(
                id = UUID.randomUUID().toString(),
                name = name,
                address = comp.host,
                port = if (comp.port in 1..65535) comp.port else 443,
                uuid = auth,
                encryption = "none",
                transport = "udp",
                security = "tls",
                sni = sni,
                alpn = alpn,
                profileType = ProfileType.VLESS,
                protocolType = ProtocolType.HYSTERIA2,
                engineType = EngineType.MIHOMO,
                sourceFile = fileName,
                sourceSubscription = subUrl,
                nodeCount = 1
            )
            ParsedItem.Success(profile)
        } catch (e: Exception) {
            ParsedItem.Invalid(uriString.take(60), "Failed to parse Hysteria2 link: ${e.message}")
        }
    }

    fun parseTuicUri(uriString: String, fileName: String? = null, subUrl: String? = null): ParsedItem {
        return try {
            val comp = extractUriComponents(uriString, "tuic")
                ?: return ParsedItem.Invalid(uriString.take(60), "Malformed TUIC URI structure")
            val auth = comp.userInfo
            if (comp.host.isBlank()) {
                return ParsedItem.Invalid(uriString.take(60), "TUIC link missing host")
            }
            val name = if (comp.fragment.isNotBlank()) {
                safeDecodeUrl(comp.fragment)
            } else "TUIC-${comp.host}"

            val params = parseQueryParams(comp.query)
            val sni = params["sni"] ?: comp.host
            val alpn = params["alpn"] ?: "h3"

            val profile = VlessProfile(
                id = UUID.randomUUID().toString(),
                name = name,
                address = comp.host,
                port = if (comp.port in 1..65535) comp.port else 443,
                uuid = auth,
                encryption = "none",
                transport = "quic",
                security = "tls",
                sni = sni,
                alpn = alpn,
                profileType = ProfileType.VLESS,
                protocolType = ProtocolType.TUIC,
                engineType = EngineType.MIHOMO,
                sourceFile = fileName,
                sourceSubscription = subUrl,
                nodeCount = 1
            )
            ParsedItem.Success(profile)
        } catch (e: Exception) {
            ParsedItem.Invalid(uriString.take(60), "Failed to parse TUIC link: ${e.message}")
        }
    }

    fun parseSocks5Uri(uriString: String, fileName: String? = null, subUrl: String? = null): ParsedItem {
        return try {
            val comp = extractUriComponents(uriString, "socks5", "socks")
                ?: return ParsedItem.Invalid(uriString.take(60), "Malformed SOCKS5 URI structure")
            val name = if (comp.fragment.isNotBlank()) {
                safeDecodeUrl(comp.fragment)
            } else "SOCKS5-${comp.host}"

            val profile = VlessProfile(
                id = UUID.randomUUID().toString(),
                name = name,
                address = comp.host.ifBlank { "127.0.0.1" },
                port = if (comp.port in 1..65535) comp.port else 1080,
                uuid = comp.userInfo,
                encryption = "none",
                transport = "tcp",
                security = "none",
                profileType = ProfileType.VLESS,
                protocolType = ProtocolType.SOCKS5,
                engineType = EngineType.XRAY,
                sourceFile = fileName,
                sourceSubscription = subUrl,
                nodeCount = 1
            )
            ParsedItem.Success(profile)
        } catch (e: Exception) {
            ParsedItem.Invalid(uriString.take(60), "Failed to parse SOCKS5 link: ${e.message}")
        }
    }

    fun parseHttpProxyUri(uriString: String, fileName: String? = null, subUrl: String? = null): ParsedItem {
        return try {
            val comp = extractUriComponents(uriString, "http", "https")
                ?: return ParsedItem.Invalid(uriString.take(60), "Malformed HTTP proxy URI structure")
            val name = if (comp.fragment.isNotBlank()) {
                safeDecodeUrl(comp.fragment)
            } else "HTTP-${comp.host}"

            val profile = VlessProfile(
                id = UUID.randomUUID().toString(),
                name = name,
                address = comp.host.ifBlank { "127.0.0.1" },
                port = if (comp.port in 1..65535) comp.port else 8080,
                uuid = comp.userInfo,
                encryption = "none",
                transport = "tcp",
                security = if (comp.scheme.equals("https", ignoreCase = true)) "tls" else "none",
                profileType = ProfileType.VLESS,
                protocolType = ProtocolType.HTTP,
                engineType = EngineType.XRAY,
                sourceFile = fileName,
                sourceSubscription = subUrl,
                nodeCount = 1
            )
            ParsedItem.Success(profile)
        } catch (e: Exception) {
            ParsedItem.Invalid(uriString.take(60), "Failed to parse HTTP proxy link: ${e.message}")
        }
    }

    private fun parseJsonConfig(jsonStr: String, fileName: String?, subUrl: String?): List<ParsedItem> {
        val parsedProfiles = XrayConfigParser.parseJson(jsonStr)
        if (parsedProfiles.isEmpty()) {
            // Check if sing-box JSON format
            return try {
                val json = JSONObject(jsonStr)
                val outbounds = json.optJSONArray("outbounds")
                val items = mutableListOf<ParsedItem>()
                if (outbounds != null) {
                    for (i in 0 until outbounds.length()) {
                        val ob = outbounds.getJSONObject(i)
                        val type = ob.optString("type")
                        val tag = ob.optString("tag", "SingBox Node $i")
                        val server = ob.optString("server")
                        val port = ob.optInt("server_port", 443)
                        val uuid = ob.optString("uuid", ob.optString("password", ""))

                        if (server.isNotBlank() && port > 0) {
                            val protocol = when (type.lowercase()) {
                                "vless" -> ProtocolType.VLESS
                                "vmess" -> ProtocolType.VMESS
                                "trojan" -> ProtocolType.TROJAN
                                "shadowsocks" -> ProtocolType.SHADOWSOCKS
                                "hysteria2" -> ProtocolType.HYSTERIA2
                                "tuic" -> ProtocolType.TUIC
                                else -> ProtocolType.VLESS
                            }
                            items.add(
                                ParsedItem.Success(
                                    VlessProfile(
                                        name = tag,
                                        address = server,
                                        port = port,
                                        uuid = uuid,
                                        profileType = ProfileType.XRAY_JSON,
                                        protocolType = protocol,
                                        engineType = EngineType.XRAY,
                                        rawConfig = ob.toString(),
                                        sourceFile = fileName,
                                        sourceSubscription = subUrl
                                    )
                                )
                            )
                        }
                    }
                }
                if (items.isNotEmpty()) items else listOf(ParsedItem.Invalid(jsonStr.take(60), "No valid outbounds found in JSON"))
            } catch (e: Exception) {
                listOf(ParsedItem.Invalid(jsonStr.take(60), "Invalid Xray/Sing-box JSON format: ${e.message}"))
            }
        }
        return parsedProfiles.map { ParsedItem.Success(it.copy(sourceFile = fileName, sourceSubscription = subUrl)) }
    }

    private fun parseYamlConfig(yamlStr: String, subUrl: String?): List<ParsedItem> {
        val profiles = MihomoParser.toVlessProfiles(yamlStr, subUrl)
        if (profiles.isEmpty()) {
            return listOf(ParsedItem.Invalid(yamlStr.take(60), "No valid proxies found in YAML"))
        }
        return profiles.map { ParsedItem.Success(it) }
    }

    private fun isLikelyBase64(str: String): Boolean {
        if (str.length < 8) return false
        val clean = str.trim()
        val base64Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=\n\r-_"
        return clean.all { it in base64Chars }
    }

    private fun decodeBase64Safe(input: String): String? {
        return try {
            val flags = Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_PADDING
            val decodedBytes = Base64.decode(input.trim(), flags)
            String(decodedBytes, StandardCharsets.UTF_8)
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
