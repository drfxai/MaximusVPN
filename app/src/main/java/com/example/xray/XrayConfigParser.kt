package com.example.xray

import com.example.data.model.EngineType
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.xray.XrayLogManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object XrayConfigParser {

    /**
     * Parses an arbitrary Xray JSON configuration into a list of VlessProfile / Proxy configurations.
     */
    fun parseJson(jsonString: String): List<VlessProfile> {
        val profiles = mutableListOf<VlessProfile>()
        try {
            val root = JSONObject(jsonString)
            val outbounds = root.optJSONArray("outbounds") ?: JSONArray()

            var primaryProfile: VlessProfile? = null

            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(i) ?: continue
                val protocol = outbound.optString("protocol", "").lowercase()
                val tag = outbound.optString("tag", "outbound-$i")

                if (protocol == "freedom" || protocol == "blackhole" || protocol == "loopback") {
                    continue
                }

                val settings = outbound.optJSONObject("settings")
                val streamSettings = outbound.optJSONObject("streamSettings") ?: JSONObject()

                val transport = streamSettings.optString("network", "tcp")
                val security = streamSettings.optString("security", "none")

                // Extract address and port based on protocol
                var address = "127.0.0.1"
                var port = 443
                var uuid = ""
                var encryption = "none"
                var flow = ""

                val protocolType = when (protocol) {
                    "vless" -> ProtocolType.VLESS
                    "vmess" -> ProtocolType.VMESS
                    "trojan" -> ProtocolType.TROJAN
                    "shadowsocks" -> ProtocolType.SHADOWSOCKS
                    "hysteria2" -> ProtocolType.HYSTERIA2
                    "socks" -> ProtocolType.SOCKS5
                    "http" -> ProtocolType.HTTP
                    else -> ProtocolType.VLESS
                }

                if (settings != null) {
                    val vnext = settings.optJSONArray("vnext")
                    if (vnext != null && vnext.length() > 0) {
                        val serverObj = vnext.getJSONObject(0)
                        address = serverObj.optString("address", address)
                        port = serverObj.optInt("port", port)
                        val users = serverObj.optJSONArray("users")
                        if (users != null && users.length() > 0) {
                            val userObj = users.getJSONObject(0)
                            uuid = userObj.optString("id", "")
                            encryption = userObj.optString("encryption", "none")
                            flow = userObj.optString("flow", "")
                        }
                    }

                    val servers = settings.optJSONArray("servers")
                    if (servers != null && servers.length() > 0) {
                        val serverObj = servers.getJSONObject(0)
                        address = serverObj.optString("address", address)
                        port = serverObj.optInt("port", port)
                        uuid = serverObj.optString("password", "")
                        encryption = serverObj.optString("method", "none")
                    }
                }

                // Stream settings TLS / REALITY
                var sni = ""
                var fingerprint = ""
                var alpn = ""
                var publicKey = ""
                var shortId = ""
                var spiderX = ""

                if (security == "tls") {
                    val tls = streamSettings.optJSONObject("tlsSettings")
                    if (tls != null) {
                        sni = tls.optString("serverName", "")
                        fingerprint = tls.optString("fingerprint", "")
                        val alpnArr = tls.optJSONArray("alpn")
                        if (alpnArr != null) {
                            alpn = (0 until alpnArr.length()).map { alpnArr.getString(it) }.joinToString(",")
                        }
                    }
                } else if (security == "reality") {
                    val reality = streamSettings.optJSONObject("realitySettings")
                    if (reality != null) {
                        sni = reality.optString("serverName", "")
                        fingerprint = reality.optString("fingerprint", "")
                        publicKey = reality.optString("publicKey", "")
                        shortId = reality.optString("shortId", "")
                        spiderX = reality.optString("spiderX", "")
                    }
                }

                // Transport settings
                var path = ""
                var host = ""
                var serviceName = ""
                var headerType = ""

                when (transport.lowercase()) {
                    "ws" -> {
                        val ws = streamSettings.optJSONObject("wsSettings")
                        if (ws != null) {
                            path = ws.optString("path", "")
                            val headers = ws.optJSONObject("headers")
                            host = headers?.optString("Host", "") ?: headers?.optString("host", "") ?: ""
                        }
                    }
                    "grpc" -> {
                        val grpc = streamSettings.optJSONObject("grpcSettings")
                        if (grpc != null) {
                            serviceName = grpc.optString("serviceName", "")
                        }
                    }
                    "http", "h2" -> {
                        val http = streamSettings.optJSONObject("httpSettings")
                        if (http != null) {
                            path = http.optString("path", "")
                            val hostArr = http.optJSONArray("host")
                            if (hostArr != null && hostArr.length() > 0) {
                                host = hostArr.getString(0)
                            }
                        }
                    }
                    "tcp" -> {
                        val tcp = streamSettings.optJSONObject("tcpSettings")
                        val header = tcp?.optJSONObject("header")
                        if (header != null) {
                            headerType = header.optString("type", "")
                        }
                    }
                }

                val effectiveSni = if (sni.isNotBlank()) sni else host
                val effectiveHost = if (host.isNotBlank()) host else effectiveSni

                val profile = VlessProfile(
                    id = UUID.randomUUID().toString(),
                    name = if (tag.isNotBlank() && tag != "proxy") tag else "$protocol-$address",
                    address = address,
                    port = port,
                    uuid = uuid,
                    encryption = encryption,
                    transport = transport,
                    security = security,
                    sni = effectiveSni,
                    host = effectiveHost,
                    path = path,
                    serviceName = serviceName,
                    flow = flow,
                    fingerprint = fingerprint,
                    publicKey = publicKey,
                    shortId = shortId,
                    spiderX = spiderX,
                    alpn = alpn,
                    headerType = headerType,
                    profileType = ProfileType.XRAY_JSON,
                    protocolType = protocolType,
                    engineType = EngineType.XRAY,
                    rawConfig = jsonString,
                    nodeCount = 1
                )

                if (primaryProfile == null) primaryProfile = profile
                profiles.add(profile)
            }

            // Also create a master Xray JSON Profile representing the whole config
            if (profiles.isNotEmpty()) {
                val masterProfile = VlessProfile(
                    id = UUID.randomUUID().toString(),
                    name = "Xray Full Config (${profiles.size} Outbounds)",
                    address = primaryProfile?.address ?: "127.0.0.1",
                    port = primaryProfile?.port ?: 443,
                    uuid = primaryProfile?.uuid ?: "",
                    encryption = primaryProfile?.encryption ?: "none",
                    transport = primaryProfile?.transport ?: "tcp",
                    security = primaryProfile?.security ?: "none",
                    sni = primaryProfile?.sni ?: "",
                    host = primaryProfile?.host ?: "",
                    path = primaryProfile?.path ?: "",
                    serviceName = primaryProfile?.serviceName ?: "",
                    flow = primaryProfile?.flow ?: "",
                    fingerprint = primaryProfile?.fingerprint ?: "",
                    publicKey = primaryProfile?.publicKey ?: "",
                    shortId = primaryProfile?.shortId ?: "",
                    spiderX = primaryProfile?.spiderX ?: "",
                    alpn = primaryProfile?.alpn ?: "",
                    profileType = ProfileType.XRAY_JSON,
                    protocolType = primaryProfile?.protocolType ?: ProtocolType.VLESS,
                    engineType = EngineType.XRAY,
                    rawConfig = jsonString,
                    nodeCount = profiles.size
                )
                profiles.add(0, masterProfile)
            }
        } catch (e: Exception) {
            XrayLogManager.appendLog("Failed to parse Xray JSON: ${e.message}", "ERROR")
        }
        return profiles
    }

    /**
     * Sanitizes and prepares a user-provided raw Xray JSON config for execution by:
     * - Ensuring SOCKS5 (10808) and Dokodemo-door (10809) inbounds exist for the TUN forwarder.
     * - Preserving all user outbounds, routing rules, DNS, policy, stats.
     */
    fun sanitizeForExecution(rawJson: String, settings: com.example.data.model.AppSettings): String {
        return try {
            val root = JSONObject(rawJson)

            // Ensure inbounds include local socks-in and dokodemo-in
            val inbounds = root.optJSONArray("inbounds") ?: JSONArray()
            var hasSocks = false
            var hasDokodemo = false

            for (i in 0 until inbounds.length()) {
                val inb = inbounds.optJSONObject(i) ?: continue
                val tag = inb.optString("tag", "")
                val port = inb.optInt("port", 0)
                if (tag == "socks-in" || port == XrayConfigBuilder.DEFAULT_SOCKS_PORT) hasSocks = true
                if (tag == "dokodemo-in" || port == XrayConfigBuilder.DEFAULT_DOKODEMO_PORT) hasDokodemo = true
            }

            if (!hasSocks) {
                val socksInbound = JSONObject().apply {
                    put("tag", "socks-in")
                    put("port", XrayConfigBuilder.DEFAULT_SOCKS_PORT)
                    put("listen", "127.0.0.1")
                    put("protocol", "socks")
                    put("settings", JSONObject().apply {
                        put("auth", "noauth")
                        put("udp", true)
                    })
                    put("sniffing", JSONObject().apply {
                        put("enabled", true)
                        put("destOverride", JSONArray().apply {
                            put("http")
                            put("tls")
                            put("quic")
                        })
                    })
                }
                inbounds.put(socksInbound)
            }

            if (!hasDokodemo) {
                val dokodemoInbound = JSONObject().apply {
                    put("tag", "dokodemo-in")
                    put("port", XrayConfigBuilder.DEFAULT_DOKODEMO_PORT)
                    put("listen", "127.0.0.1")
                    put("protocol", "dokodemo-door")
                    put("settings", JSONObject().apply {
                        put("network", "tcp,udp")
                        put("followRedirect", true)
                    })
                }
                inbounds.put(dokodemoInbound)
            }
            root.put("inbounds", inbounds)

            // Ensure log exists
            if (!root.has("log")) {
                root.put("log", JSONObject().apply {
                    put("loglevel", settings.logLevel.lowercase())
                })
            }

            // Ensure stats and policy exist
            if (!root.has("stats")) {
                root.put("stats", JSONObject())
            }
            if (!root.has("policy")) {
                root.put("policy", JSONObject().apply {
                    put("system", JSONObject().apply {
                        put("statsInboundUplink", true)
                        put("statsInboundDownlink", true)
                        put("statsOutboundUplink", true)
                        put("statsOutboundDownlink", true)
                    })
                })
            }

            root.toString(2)
        } catch (e: Exception) {
            rawJson
        }
    }
}
