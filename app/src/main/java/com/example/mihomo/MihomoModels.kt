package com.example.mihomo

import com.example.data.model.DnsConfiguration
import com.example.data.model.ProxyGroup
import com.example.data.model.ProxyNode
import com.example.data.model.RoutingConfiguration

data class MihomoConfig(
    val mixedPort: Int = 10808,
    val socksPort: Int = 10809,
    val port: Int = 0,
    val redirPort: Int = 0,
    val tproxyPort: Int = 0,
    val allowLan: Boolean = false,
    val mode: String = "rule", // rule, global, direct
    val logLevel: String = "info",
    val ipv6: Boolean = true,
    val externalController: String = "127.0.0.1:9090",
    val secret: String = "",
    val proxies: List<ProxyNode> = emptyList(),
    val proxyGroups: List<ProxyGroup> = emptyList(),
    val proxyProviders: Map<String, Any> = emptyMap(),
    val ruleProviders: Map<String, Any> = emptyMap(),
    val rules: List<String> = emptyList(),
    val dns: DnsConfiguration = DnsConfiguration(),
    val tun: MihomoTunConfig = MihomoTunConfig(),
    val rawYaml: String = ""
)

data class MihomoTunConfig(
    val enable: Boolean = true,
    val stack: String = "system", // system, gvisor, mixed
    val dnsHijack: List<String> = listOf("any:53", "tcp://any:53"),
    val autoRoute: Boolean = true,
    val autoDetectInterface: Boolean = true,
    val mtu: Int = 1500,
    val strictRoute: Boolean = true
)
