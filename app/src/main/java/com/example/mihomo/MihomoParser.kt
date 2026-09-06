package com.example.mihomo

import com.example.data.model.DnsConfiguration
import com.example.data.model.EngineType
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.ProxyGroup
import com.example.data.model.ProxyNode
import com.example.data.model.VlessProfile
import com.example.xray.XrayLogManager
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.UUID

object MihomoParser {

    /**
     * Safely parses a Mihomo/Clash Meta YAML string into a structured MihomoConfig.
     */
    fun parseYaml(yamlContent: String): MihomoConfig {
        if (yamlContent.isBlank()) return MihomoConfig()

        val options = LoaderOptions().apply {
            maxAliasesForCollections = 200
        }
        val yaml = Yaml(SafeConstructor(options))
        val rootMap = try {
            yaml.load<Any>(yamlContent) as? Map<*, *> ?: emptyMap<Any, Any>()
        } catch (e: Exception) {
            XrayLogManager.appendLog("Mihomo YAML parsing error: ${e.message}", "MIHOMO")
            return MihomoConfig(rawYaml = yamlContent)
        }

        val mixedPort = (rootMap["mixed-port"] as? Number)?.toInt() ?: 10808
        val socksPort = (rootMap["socks-port"] as? Number)?.toInt() ?: 10809
        val port = (rootMap["port"] as? Number)?.toInt() ?: 0
        val redirPort = (rootMap["redir-port"] as? Number)?.toInt() ?: 0
        val tproxyPort = (rootMap["tproxy-port"] as? Number)?.toInt() ?: 0
        val allowLan = rootMap["allow-lan"] as? Boolean ?: false
        val mode = rootMap["mode"]?.toString() ?: "rule"
        val logLevel = rootMap["log-level"]?.toString() ?: "info"
        val ipv6 = rootMap["ipv6"] as? Boolean ?: true
        val externalController = rootMap["external-controller"]?.toString() ?: "127.0.0.1:9090"
        val secret = rootMap["secret"]?.toString() ?: ""

        // Parse Proxies
        val proxiesList = (rootMap["proxies"] as? List<*>)?.mapNotNull { item ->
            (item as? Map<*, *>)?.let { parseProxyNode(it) }
        } ?: emptyList()

        // Parse Proxy Groups
        val proxyGroupsList = (rootMap["proxy-groups"] as? List<*>)?.mapNotNull { item ->
            (item as? Map<*, *>)?.let { parseProxyGroup(it) }
        } ?: emptyList()

        // Parse Rules
        val rulesList = (rootMap["rules"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

        // Parse DNS
        val dnsMap = rootMap["dns"] as? Map<*, *>
        val dnsConfig = if (dnsMap != null) parseDns(dnsMap) else DnsConfiguration()

        // Parse TUN
        val tunMap = rootMap["tun"] as? Map<*, *>
        val tunConfig = if (tunMap != null) parseTun(tunMap) else MihomoTunConfig()

        return MihomoConfig(
            mixedPort = mixedPort,
            socksPort = socksPort,
            port = port,
            redirPort = redirPort,
            tproxyPort = tproxyPort,
            allowLan = allowLan,
            mode = mode,
            logLevel = logLevel,
            ipv6 = ipv6,
            externalController = externalController,
            secret = secret,
            proxies = proxiesList,
            proxyGroups = proxyGroupsList,
            rules = rulesList,
            dns = dnsConfig,
            tun = tunConfig,
            rawYaml = yamlContent
        )
    }

    private fun parseProxyNode(map: Map<*, *>): ProxyNode? {
        val name = map["name"]?.toString()?.trim() ?: return null
        val typeStr = map["type"]?.toString()?.lowercase()?.trim() ?: return null
        val server = map["server"]?.toString()?.trim() ?: return null
        val port = (map["port"] as? Number)?.toInt() ?: return null

        val protocolType = when (typeStr) {
            "vless" -> ProtocolType.VLESS
            "hysteria2", "hy2" -> ProtocolType.HYSTERIA2
            "ss", "shadowsocks" -> ProtocolType.SHADOWSOCKS
            "trojan" -> ProtocolType.TROJAN
            "vmess" -> ProtocolType.VMESS
            "tuic" -> ProtocolType.TUIC
            "http" -> ProtocolType.HTTP
            "socks5", "socks" -> ProtocolType.SOCKS5
            else -> ProtocolType.VLESS
        }

        val uuid = map["uuid"]?.toString() ?: map["password"]?.toString() ?: map["auth"]?.toString() ?: ""
        val password = map["password"]?.toString() ?: map["auth"]?.toString() ?: uuid
        val cipher = map["cipher"]?.toString() ?: "none"

        val network = map["network"]?.toString()?.lowercase() ?: "tcp"
        val tls = (map["tls"] as? Boolean) ?: (map["reality-opts"] != null)
        val sni = map["servername"]?.toString() ?: map["sni"]?.toString() ?: map["host"]?.toString() ?: ""

        val flow = map["flow"]?.toString() ?: ""
        val fingerprint = map["client-fingerprint"]?.toString() ?: map["fingerprint"]?.toString() ?: "chrome"

        // REALITY options
        val realityMap = map["reality-opts"] as? Map<*, *>
        val reality = realityMap != null || map["public-key"] != null || map["publicKey"] != null
        val publicKey = realityMap?.get("public-key")?.toString() ?: realityMap?.get("publicKey")?.toString() ?: map["public-key"]?.toString() ?: ""
        val shortId = realityMap?.get("short-id")?.toString() ?: realityMap?.get("shortId")?.toString() ?: map["short-id"]?.toString() ?: ""
        val spiderX = realityMap?.get("spider-x")?.toString() ?: realityMap?.get("spiderX")?.toString() ?: map["spider-x"]?.toString() ?: ""

        // WS options
        val wsMap = map["ws-opts"] as? Map<*, *>
        val wsHeaders = wsMap?.get("headers") as? Map<*, *>
        val host = wsHeaders?.get("Host")?.toString() ?: wsHeaders?.get("host")?.toString() ?: map["host"]?.toString() ?: sni
        val path = wsMap?.get("path")?.toString() ?: map["path"]?.toString() ?: ""

        // gRPC options
        val grpcMap = map["grpc-opts"] as? Map<*, *>
        val serviceName = grpcMap?.get("grpc-service-name")?.toString() ?: map["service-name"]?.toString() ?: ""

        // ALPN
        val alpnList = (map["alpn"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

        val udp = map["udp"] as? Boolean ?: true

        return ProxyNode(
            id = UUID.randomUUID().toString(),
            name = name,
            type = protocolType,
            server = server,
            port = port,
            uuid = uuid,
            password = password,
            cipher = cipher,
            network = network,
            tls = tls,
            reality = reality,
            sni = sni,
            host = host,
            path = path,
            serviceName = serviceName,
            flow = flow,
            fingerprint = fingerprint,
            publicKey = publicKey,
            shortId = shortId,
            spiderX = spiderX,
            alpn = alpnList,
            udp = udp
        )
    }

    private fun parseProxyGroup(map: Map<*, *>): ProxyGroup? {
        val name = map["name"]?.toString()?.trim() ?: return null
        val type = map["type"]?.toString()?.lowercase()?.trim() ?: "select"
        val proxies = (map["proxies"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val url = map["url"]?.toString() ?: "http://www.gstatic.com/generate_204"
        val interval = (map["interval"] as? Number)?.toInt() ?: 300
        val tolerance = (map["tolerance"] as? Number)?.toInt() ?: 150
        val lazy = map["lazy"] as? Boolean ?: true

        return ProxyGroup(
            name = name,
            type = type,
            proxies = proxies,
            url = url,
            intervalSeconds = interval,
            toleranceMs = tolerance,
            selectedProxy = proxies.firstOrNull(),
            lazy = lazy
        )
    }

    private fun parseDns(map: Map<*, *>): DnsConfiguration {
        val enable = map["enable"] as? Boolean ?: true
        val defaultNs = (map["default-nameserver"] as? List<*>)?.firstOrNull()?.toString()
            ?: map["default-nameserver"]?.toString()
            ?: "8.8.8.8"

        val nameservers = (map["nameserver"] as? List<*>)?.mapNotNull { it?.toString() }
            ?: listOf("https://8.8.8.8/dns-query", "https://1.1.1.1/dns-query", "8.8.8.8", "1.1.1.1")

        val enhancedMode = map["enhanced-mode"]?.toString() ?: "redir-host"
        val fakeIp = enhancedMode.equals("fake-ip", ignoreCase = true)
        val domainStrategy = if (map["use-hosts"] as? Boolean == false) "UseIP" else "IPIfNonMatch"

        return DnsConfiguration(
            servers = nameservers,
            dohEnabled = nameservers.any { it.startsWith("https://") },
            defaultNameserver = defaultNs,
            domainStrategy = domainStrategy,
            fakeIpEnabled = fakeIp,
            hijackDns = true
        )
    }

    private fun parseTun(map: Map<*, *>): MihomoTunConfig {
        val enable = map["enable"] as? Boolean ?: true
        val stack = map["stack"]?.toString() ?: "system"
        val dnsHijack = (map["dns-hijack"] as? List<*>)?.mapNotNull { it?.toString() } ?: listOf("any:53", "tcp://any:53")
        val autoRoute = map["auto-route"] as? Boolean ?: true
        val autoDetectInterface = map["auto-detect-interface"] as? Boolean ?: true
        val mtu = (map["mtu"] as? Number)?.toInt() ?: 1500
        val strictRoute = map["strict-route"] as? Boolean ?: true

        return MihomoTunConfig(
            enable = enable,
            stack = stack,
            dnsHijack = dnsHijack,
            autoRoute = autoRoute,
            autoDetectInterface = autoDetectInterface,
            mtu = mtu,
            strictRoute = strictRoute
        )
    }

    /**
     * Converts a Mihomo/Clash YAML content into a list of individual VlessProfile objects
     * and/or a bundle profile for direct execution.
     */
    fun toVlessProfiles(yamlContent: String, sourceUrl: String? = null): List<VlessProfile> {
        val config = parseYaml(yamlContent)
        val result = mutableListOf<VlessProfile>()

        // 1. Bundle Profile representing the whole configuration
        if (config.proxies.isNotEmpty() || config.proxyGroups.isNotEmpty()) {
            val primaryNode = config.proxies.firstOrNull()
            val bundleProfile = VlessProfile(
                id = UUID.randomUUID().toString(),
                name = if (sourceUrl != null) "Mihomo Bundle (${config.proxies.size} Nodes)" else "Mihomo Config (${config.proxies.size} Nodes)",
                address = primaryNode?.server ?: "127.0.0.1",
                port = primaryNode?.port ?: 443,
                uuid = primaryNode?.uuid ?: "",
                encryption = primaryNode?.cipher ?: "none",
                transport = primaryNode?.network ?: "tcp",
                security = if (primaryNode?.reality == true) "reality" else if (primaryNode?.tls == true) "tls" else "none",
                sni = primaryNode?.sni ?: "",
                host = primaryNode?.host ?: "",
                path = primaryNode?.path ?: "",
                serviceName = primaryNode?.serviceName ?: "",
                flow = primaryNode?.flow ?: "",
                fingerprint = primaryNode?.fingerprint ?: "",
                publicKey = primaryNode?.publicKey ?: "",
                shortId = primaryNode?.shortId ?: "",
                spiderX = primaryNode?.spiderX ?: "",
                alpn = primaryNode?.alpn?.joinToString(",") ?: "",
                profileType = ProfileType.MIHOMO_YAML,
                protocolType = primaryNode?.type ?: ProtocolType.VLESS,
                engineType = EngineType.MIHOMO,
                rawConfig = yamlContent,
                subscriptionUrl = sourceUrl,
                proxyGroupName = config.proxyGroups.firstOrNull()?.name,
                nodeCount = config.proxies.size
            )
            result.add(bundleProfile)
        }

        // 2. Individual profiles for each proxy node
        for (node in config.proxies) {
            result.add(proxyNodeToVlessProfile(node, sourceUrl))
        }

        return result
    }

    fun proxyNodeToVlessProfile(node: ProxyNode, sourceUrl: String? = null): VlessProfile {
        return VlessProfile(
            id = node.id,
            name = node.name,
            address = node.server,
            port = node.port,
            uuid = node.uuid,
            encryption = node.cipher,
            transport = node.network,
            security = if (node.reality) "reality" else if (node.tls) "tls" else "none",
            sni = node.sni,
            host = node.host,
            path = node.path,
            serviceName = node.serviceName,
            flow = node.flow,
            fingerprint = node.fingerprint,
            publicKey = node.publicKey,
            shortId = node.shortId,
            spiderX = node.spiderX,
            alpn = node.alpn.joinToString(","),
            headerType = "",
            profileType = ProfileType.VLESS,
            protocolType = node.type,
            engineType = EngineType.MIHOMO,
            rawConfig = "",
            subscriptionUrl = sourceUrl,
            nodeCount = 1
        )
    }

    /**
     * Builds a clean, standard Mihomo Meta YAML configuration from a profile.
     */
    fun buildMihomoYaml(profile: VlessProfile, dnsServers: List<String> = listOf("https://8.8.8.8/dns-query", "8.8.8.8")): String {
        val proxyType = when (profile.protocolType) {
            ProtocolType.HYSTERIA2 -> "hysteria2"
            ProtocolType.SHADOWSOCKS -> "ss"
            ProtocolType.TROJAN -> "trojan"
            ProtocolType.VMESS -> "vmess"
            ProtocolType.TUIC -> "tuic"
            ProtocolType.HTTP -> "http"
            ProtocolType.SOCKS5 -> "socks5"
            else -> "vless"
        }

        val sb = StringBuilder()
        sb.appendLine("mixed-port: 10808")
        sb.appendLine("socks-port: 10809")
        sb.appendLine("allow-lan: false")
        sb.appendLine("mode: rule")
        sb.appendLine("log-level: info")
        sb.appendLine("ipv6: true")
        sb.appendLine("external-controller: 127.0.0.1:9090")
        sb.appendLine()

        // DNS
        sb.appendLine("dns:")
        sb.appendLine("  enable: true")
        sb.appendLine("  ipv6: true")
        sb.appendLine("  default-nameserver:")
        sb.appendLine("    - 8.8.8.8")
        sb.appendLine("    - 1.1.1.1")
        sb.appendLine("  enhanced-mode: fake-ip")
        sb.appendLine("  fake-ip-range: 198.18.0.1/16")
        sb.appendLine("  nameserver:")
        for (dns in dnsServers) {
            sb.appendLine("    - $dns")
        }
        sb.appendLine()

        // Proxies
        sb.appendLine("proxies:")
        sb.appendLine("  - name: \"${profile.name.replace("\"", "\\\"")}\"")
        sb.appendLine("    type: $proxyType")
        sb.appendLine("    server: ${profile.address}")
        sb.appendLine("    port: ${profile.port}")
        sb.appendLine("    uuid: ${profile.uuid}")
        if (profile.uuid.isNotBlank() && (proxyType == "hysteria2" || proxyType == "trojan")) {
            sb.appendLine("    password: ${profile.uuid}")
        }
        if (profile.encryption.isNotBlank() && profile.encryption != "none") {
            sb.appendLine("    cipher: ${profile.encryption}")
        }
        sb.appendLine("    network: ${profile.transport}")
        sb.appendLine("    udp: true")

        val hasTls = profile.security.equals("tls", ignoreCase = true) || profile.security.equals("reality", ignoreCase = true)
        sb.appendLine("    tls: $hasTls")

        if (profile.sni.isNotBlank()) {
            sb.appendLine("    servername: ${profile.sni}")
        }
        if (profile.fingerprint.isNotBlank()) {
            sb.appendLine("    client-fingerprint: ${profile.fingerprint}")
        }
        if (profile.flow.isNotBlank()) {
            sb.appendLine("    flow: ${profile.flow}")
        }

        // REALITY
        if (profile.security.equals("reality", ignoreCase = true) && profile.publicKey.isNotBlank()) {
            sb.appendLine("    reality-opts:")
            sb.appendLine("      public-key: ${profile.publicKey}")
            if (profile.shortId.isNotBlank()) {
                sb.appendLine("      short-id: ${profile.shortId}")
            }
            if (profile.spiderX.isNotBlank()) {
                sb.appendLine("      spider-x: ${profile.spiderX}")
            }
        }

        // Transport opts
        when (profile.transport.lowercase()) {
            "ws", "websocket" -> {
                sb.appendLine("    ws-opts:")
                if (profile.path.isNotBlank()) {
                    sb.appendLine("      path: \"${profile.path}\"")
                }
                if (profile.host.isNotBlank() || profile.sni.isNotBlank()) {
                    val h = if (profile.host.isNotBlank()) profile.host else profile.sni
                    sb.appendLine("      headers:")
                    sb.appendLine("        Host: \"$h\"")
                }
            }
            "grpc" -> {
                if (profile.serviceName.isNotBlank()) {
                    sb.appendLine("    grpc-opts:")
                    sb.appendLine("      grpc-service-name: \"${profile.serviceName}\"")
                }
            }
        }

        if (profile.alpn.isNotBlank()) {
            sb.appendLine("    alpn:")
            for (item in profile.alpn.split(",")) {
                if (item.isNotBlank()) {
                    sb.appendLine("      - ${item.trim()}")
                }
            }
        }

        sb.appendLine()

        // Proxy Groups
        sb.appendLine("proxy-groups:")
        sb.appendLine("  - name: PROXY")
        sb.appendLine("    type: select")
        sb.appendLine("    proxies:")
        sb.appendLine("      - \"${profile.name.replace("\"", "\\\"")}\"")
        sb.appendLine("      - DIRECT")
        sb.appendLine()

        // Rules
        sb.appendLine("rules:")
        sb.appendLine("  - GEOIP,LAN,DIRECT")
        sb.appendLine("  - GEOIP,CN,DIRECT")
        sb.appendLine("  - MATCH,PROXY")

        return sb.toString()
    }
}
