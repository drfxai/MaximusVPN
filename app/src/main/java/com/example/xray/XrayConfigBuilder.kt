package com.example.xray

import com.example.data.model.AppSettings
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.RoutingMode
import com.example.data.model.VlessProfile
import org.json.JSONArray
import org.json.JSONObject

object XrayConfigBuilder {

    const val DEFAULT_SOCKS_PORT = 10808
    const val DEFAULT_DOKODEMO_PORT = 10809
    const val SO_MARK_VPN = 255

    /**
     * Builds a complete, valid Xray-core JSON configuration object from a VlessProfile and AppSettings.
     * If the profile has a raw JSON config, it sanitizes and preserves it directly.
     */
    fun buildJson(profile: VlessProfile, settings: AppSettings): String {
        if (profile.profileType == ProfileType.XRAY_JSON && profile.rawConfig.isNotBlank()) {
            return XrayConfigParser.sanitizeForExecution(profile.rawConfig, settings)
        }

        val root = JSONObject()

        // 1. Logging
        val logObj = JSONObject().apply {
            put("loglevel", settings.logLevel.lowercase())
            put("access", "")
            put("error", "")
        }
        root.put("log", logObj)

        // 2. DNS
        val dnsObj = JSONObject()
        val dnsServers = JSONArray().apply {
            put(settings.dnsServer)
            if (settings.customDns.isNotBlank() && settings.customDns != settings.dnsServer) {
                put(settings.customDns)
            }
            put("8.8.8.8")
            put("localhost")
        }
        dnsObj.put("servers", dnsServers)
        dnsObj.put("domainStrategy", "IPIfNonMatch")
        root.put("dns", dnsObj)

        // 3. Inbounds (Local SOCKS & Dokodemo-Door for TUN forwarder)
        val inbounds = JSONArray()

        val socksInbound = JSONObject().apply {
            put("tag", "socks-in")
            put("port", DEFAULT_SOCKS_PORT)
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

        val dokodemoInbound = JSONObject().apply {
            put("tag", "dokodemo-in")
            put("port", DEFAULT_DOKODEMO_PORT)
            put("listen", "127.0.0.1")
            put("protocol", "dokodemo-door")
            put("settings", JSONObject().apply {
                put("network", "tcp,udp")
                put("followRedirect", true)
            })
        }
        inbounds.put(dokodemoInbound)
        root.put("inbounds", inbounds)

        // 4. Outbounds (Proxy Outbound based on Protocol, Direct Freedom, Block Blackhole)
        val outbounds = JSONArray()

        val proxyOutbound = buildOutboundForProfile(profile)
        outbounds.put(proxyOutbound)

        // Freedom (Direct) Outbound with socket protection mark
        val directOutbound = JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject().apply {
                put("domainStrategy", "UseIP")
            })
            put("streamSettings", JSONObject().apply {
                put("sockopt", JSONObject().apply {
                    put("mark", SO_MARK_VPN)
                })
            })
        }
        outbounds.put(directOutbound)

        // Blackhole (Block) Outbound
        val blockOutbound = JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject().apply {
                put("response", JSONObject().apply { put("type", "none") })
            })
        }
        outbounds.put(blockOutbound)

        root.put("outbounds", outbounds)

        // 5. Routing
        val routingObj = JSONObject()
        routingObj.put("domainStrategy", "IPIfNonMatch")
        val rulesArray = JSONArray()

        when (settings.routingMode) {
            RoutingMode.GLOBAL -> {
                rulesArray.put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "proxy")
                    put("network", "tcp,udp")
                })
            }
            RoutingMode.RULE_BYPASS_LAN -> {
                rulesArray.put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply {
                        put("10.0.0.0/8")
                        put("100.64.0.0/10")
                        put("127.0.0.0/8")
                        put("169.254.0.0/16")
                        put("172.16.0.0/12")
                        put("192.168.0.0/16")
                        put("198.18.0.0/15")
                        put("fc00::/7")
                        put("fe80::/10")
                        put("::1/128")
                    })
                })
                rulesArray.put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("domain", JSONArray().apply {
                        put("local")
                        put("localhost")
                        put("lan")
                    })
                })
                rulesArray.put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "proxy")
                    put("network", "tcp,udp")
                })
            }
            RoutingMode.BYPASS_SELECTED -> {
                if (settings.customBypassRules.isNotBlank()) {
                    val customDomains = JSONArray()
                    settings.customBypassRules.split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .forEach { customDomains.put(it) }

                    if (customDomains.length() > 0) {
                        rulesArray.put(JSONObject().apply {
                            put("type", "field")
                            put("outboundTag", "direct")
                            put("domain", customDomains)
                        })
                    }
                }
                rulesArray.put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply {
                        put("10.0.0.0/8")
                        put("100.64.0.0/10")
                        put("127.0.0.0/8")
                        put("169.254.0.0/16")
                        put("172.16.0.0/12")
                        put("192.168.0.0/16")
                        put("198.18.0.0/15")
                    })
                })
                rulesArray.put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "proxy")
                    put("network", "tcp,udp")
                })
            }
        }

        routingObj.put("rules", rulesArray)
        root.put("routing", routingObj)

        // 6. Policy & Stats
        root.put("stats", JSONObject())
        root.put("policy", JSONObject().apply {
            put("system", JSONObject().apply {
                put("statsInboundUplink", true)
                put("statsInboundDownlink", true)
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            })
        })

        return root.toString(2)
    }

    private fun buildOutboundForProfile(profile: VlessProfile): JSONObject {
        val proxyOutbound = JSONObject()
        proxyOutbound.put("tag", "proxy")

        val isTlsOrReality = profile.security.equals("tls", ignoreCase = true) ||
                profile.security.equals("reality", ignoreCase = true)
        val isTcpTransport = profile.transport.isBlank() || profile.transport.equals("tcp", ignoreCase = true)

        when (profile.protocolType) {
            ProtocolType.SHADOWSOCKS -> {
                proxyOutbound.put("protocol", "shadowsocks")
                val serversArr = JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", profile.address)
                        put("port", profile.port)
                        put("method", if (profile.encryption.isNotBlank() && profile.encryption != "none") profile.encryption else "aes-128-gcm")
                        put("password", profile.uuid)
                        put("ota", false)
                    })
                }
                proxyOutbound.put("settings", JSONObject().apply { put("servers", serversArr) })
            }
            ProtocolType.TROJAN -> {
                proxyOutbound.put("protocol", "trojan")
                val serversArr = JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", profile.address)
                        put("port", profile.port)
                        put("password", profile.uuid)
                    })
                }
                proxyOutbound.put("settings", JSONObject().apply { put("servers", serversArr) })
            }
            ProtocolType.VMESS -> {
                proxyOutbound.put("protocol", "vmess")
                val userObj = JSONObject().apply {
                    put("id", profile.uuid)
                    put("alterId", 0)
                    put("security", if (profile.encryption.isNotBlank()) profile.encryption else "auto")
                }
                val vnextArr = JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", profile.address)
                        put("port", profile.port)
                        put("users", JSONArray().apply { put(userObj) })
                    })
                }
                proxyOutbound.put("settings", JSONObject().apply { put("vnext", vnextArr) })
            }
            else -> {
                // Default: VLESS
                proxyOutbound.put("protocol", "vless")
                val userObj = JSONObject().apply {
                    put("id", profile.uuid)
                    put("encryption", if (profile.encryption.isNotBlank()) profile.encryption else "none")
                    if (profile.flow.isNotBlank() && isTlsOrReality && isTcpTransport) {
                        put("flow", profile.flow)
                    }
                    put("level", 0)
                }
                val vnextArr = JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", profile.address)
                        put("port", profile.port)
                        put("users", JSONArray().apply { put(userObj) })
                    })
                }
                proxyOutbound.put("settings", JSONObject().apply { put("vnext", vnextArr) })
            }
        }

        // Stream Settings (Transport & Security)
        val streamSettings = JSONObject().apply {
            put("network", if (profile.transport.isNotBlank()) profile.transport else "tcp")

            val sec = profile.security.lowercase()
            put("security", if (sec.isNotBlank()) sec else "none")

            // TLS / REALITY Settings
            if (sec == "tls") {
                val tlsObj = JSONObject().apply {
                    if (profile.sni.isNotBlank()) put("serverName", profile.sni)
                    if (profile.fingerprint.isNotBlank()) {
                        if (profile.fingerprint.equals("unsafe", ignoreCase = true)) {
                            put("allowInsecure", true)
                            put("fingerprint", "chrome")
                        } else {
                            put("fingerprint", profile.fingerprint)
                        }
                    }
                    if (profile.cipherSuites.isNotBlank()) {
                        put("cipherSuites", profile.cipherSuites)
                    }
                    if (profile.alpn.isNotBlank()) {
                        val alpnArray = JSONArray()
                        profile.alpn.split(",").forEach { alpnArray.put(it.trim()) }
                        put("alpn", alpnArray)
                    }
                }
                put("tlsSettings", tlsObj)
            } else if (sec == "reality") {
                val realityObj = JSONObject().apply {
                    put("show", false)
                    if (profile.fingerprint.isNotBlank()) {
                        if (profile.fingerprint.equals("unsafe", ignoreCase = true)) {
                            put("allowInsecure", true)
                            put("fingerprint", "chrome")
                        } else {
                            put("fingerprint", profile.fingerprint)
                        }
                    }
                    if (profile.cipherSuites.isNotBlank()) {
                        put("cipherSuites", profile.cipherSuites)
                    }
                    if (profile.sni.isNotBlank()) put("serverName", profile.sni)
                    if (profile.publicKey.isNotBlank()) put("publicKey", profile.publicKey)
                    if (profile.shortId.isNotBlank()) put("shortId", profile.shortId)
                    if (profile.spiderX.isNotBlank()) put("spiderX", profile.spiderX)
                }
                put("realitySettings", realityObj)
            }

            // Transport Specific Settings
            when (profile.transport.lowercase()) {
                "ws" -> {
                    val wsObj = JSONObject().apply {
                        put("path", if (profile.path.isNotBlank()) profile.path else "/")
                        val headers = JSONObject()
                        if (profile.host.isNotBlank()) headers.put("Host", profile.host)
                        put("headers", headers)
                    }
                    put("wsSettings", wsObj)
                }
                "grpc" -> {
                    val grpcObj = JSONObject().apply {
                        put("serviceName", if (profile.serviceName.isNotBlank()) profile.serviceName else "")
                        put("multiMode", true)
                    }
                    put("grpcSettings", grpcObj)
                }
                "http", "h2" -> {
                    val httpObj = JSONObject().apply {
                        put("path", if (profile.path.isNotBlank()) profile.path else "/")
                        if (profile.host.isNotBlank()) {
                            put("host", JSONArray().apply { put(profile.host) })
                        }
                    }
                    put("httpSettings", httpObj)
                }
                "tcp" -> {
                    if (profile.headerType.equals("http", ignoreCase = true)) {
                        val tcpObj = JSONObject().apply {
                            put("header", JSONObject().apply {
                                put("type", "http")
                                put("request", JSONObject().apply {
                                    put("path", JSONArray().apply { put(if (profile.path.isNotBlank()) profile.path else "/") })
                                    if (profile.host.isNotBlank()) {
                                        put("headers", JSONObject().apply {
                                            put("Host", JSONArray().apply { put(profile.host) })
                                        })
                                    }
                                })
                            })
                        }
                        put("tcpSettings", tcpObj)
                    }
                }
            }

            put("sockopt", JSONObject().apply {
                put("mark", SO_MARK_VPN)
            })
        }

        proxyOutbound.put("streamSettings", streamSettings)
        return proxyOutbound
    }
}

