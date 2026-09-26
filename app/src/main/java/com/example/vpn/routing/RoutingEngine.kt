package com.example.vpn.routing

import com.example.data.model.AppSettings
import com.example.data.model.RoutingMode
import com.example.vpn.packet.IpProtocol
import java.net.InetAddress

enum class RoutingDecision {
    PROXY,
    DIRECT,
    BLOCK
}

object RoutingEngine {

    /**
     * Evaluates the routing decision for a packet given the destination IP, port,
     * protocol, application settings, and current tunnel connection state.
     */
    fun evaluate(
        dstIp: ByteArray,
        dstPort: Int,
        protocol: IpProtocol,
        settings: AppSettings,
        isTunnelConnected: Boolean
    ): RoutingDecision {
        // If Kill Switch is enabled and the tunnel is not connected, block all non-essential traffic
        if (settings.killSwitchEnabled && !isTunnelConnected) {
            return RoutingDecision.BLOCK
        }

        // Never let classic DNS escape directly, even when a LAN or user bypass rule matches.
        // If the proxy cannot carry it, fail closed instead of exposing the device's source IP.
        if ((protocol == IpProtocol.UDP || protocol == IpProtocol.TCP) && dstPort == 53) {
            return if (isTunnelConnected) RoutingDecision.PROXY else RoutingDecision.BLOCK
        }

        val isLan = isLanAddress(dstIp)

        // Block private/loopback/multicast traffic if kill switch is active and not on LAN bypass
        if (isLoopbackOrBroadcast(dstIp)) {
            return if (settings.routingMode == RoutingMode.RULE_BYPASS_LAN) {
                RoutingDecision.DIRECT
            } else {
                RoutingDecision.BLOCK
            }
        }

        return when (settings.routingMode) {
            RoutingMode.GLOBAL -> {
                // In Global mode, all routable traffic is sent through the proxy tunnel
                if (isLan) {
                    // Local addresses cannot be routed across a remote proxy; bypass if local
                    RoutingDecision.DIRECT
                } else {
                    RoutingDecision.PROXY
                }
            }

            RoutingMode.RULE_BYPASS_LAN -> {
                if (isLan) {
                    RoutingDecision.DIRECT
                } else {
                    RoutingDecision.PROXY
                }
            }

            RoutingMode.BYPASS_SELECTED -> {
                if (isLan) {
                    RoutingDecision.DIRECT
                } else {
                    val dstIpStr = formatIp(dstIp)
                    val bypassList = settings.customBypassRules.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                    if (isBypassedDestination(dstIpStr, dstPort, bypassList)) {
                        RoutingDecision.DIRECT
                    } else {
                        RoutingDecision.PROXY
                    }
                }
            }
        }
    }

    /**
     * Evaluates routing by IP string (supports both IPv4 and IPv6 string literals).
     */
    fun evaluate(
        dstIpStr: String,
        dstPort: Int,
        protocol: IpProtocol,
        settings: AppSettings,
        isTunnelConnected: Boolean
    ): RoutingDecision {
        val parsed = parseIp(dstIpStr) ?: return RoutingDecision.BLOCK
        return evaluate(parsed, dstPort, protocol, settings, isTunnelConnected)
    }

    /**
     * Checks if an IPv4 or IPv6 address belongs to standard private/LAN ranges (RFC 1918, RFC 3927, RFC 4193).
     */
    fun isLanAddress(ip: ByteArray): Boolean {
        if (ip.size == 4) {
            val b0 = ip[0].toInt() and 0xFF
            val b1 = ip[1].toInt() and 0xFF

            return when {
                // 10.0.0.0/8
                b0 == 10 -> true
                // 172.16.0.0/12 (172.16.0.0 - 172.31.255.255)
                b0 == 172 && (b1 in 16..31) -> true
                // 192.168.0.0/16
                b0 == 192 && b1 == 168 -> true
                // 127.0.0.0/8 (Loopback)
                b0 == 127 -> true
                // 169.254.0.0/16 (Link-local)
                b0 == 169 && b1 == 254 -> true
                // 100.64.0.0/10 (Carrier-Grade NAT)
                b0 == 100 && (b1 in 64..127) -> true
                else -> false
            }
        } else if (ip.size == 16) {
            val b0 = ip[0].toInt() and 0xFF
            // ULA fc00::/7
            if ((b0 and 0xFE) == 0xFC) return true
            // Link-local fe80::/10
            if (b0 == 0xFE && ((ip[1].toInt() and 0xC0) == 0x80)) return true
            // Loopback ::1
            if (ip.sliceArray(0..14).all { it == 0.toByte() } && ip[15] == 1.toByte()) return true
        }
        return false
    }

    fun isLoopbackOrBroadcast(ip: ByteArray): Boolean {
        if (ip.size == 4) {
            val b0 = ip[0].toInt() and 0xFF
            val b1 = ip[1].toInt() and 0xFF
            val b2 = ip[2].toInt() and 0xFF
            val b3 = ip[3].toInt() and 0xFF

            return (b0 == 127) || // 127.0.0.0/8
                    (b0 in 224..239) || // Multicast 224.0.0.0/4
                    (b0 == 255 && b1 == 255 && b2 == 255 && b3 == 255) || // Broadcast
                    (b0 == 0 && b1 == 0 && b2 == 0 && b3 == 0) // 0.0.0.0
        } else if (ip.size == 16) {
            val b0 = ip[0].toInt() and 0xFF
            // Multicast ff00::/8
            if (b0 == 0xFF) return true
            // Unspecified ::
            if (ip.all { it == 0.toByte() }) return true
            // Loopback ::1
            if (ip.sliceArray(0..14).all { it == 0.toByte() } && ip[15] == 1.toByte()) return true
        }
        return false
    }

    /**
     * Checks if a destination IP or domain is present in the bypass rules.
     */
    fun isBypassedDestination(dstIpStr: String, dstPort: Int, bypassList: List<String>): Boolean {
        if (bypassList.isEmpty()) return false

        for (rule in bypassList) {
            val trimmed = rule.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.contains("/")) {
                // CIDR match
                if (matchesCidr(dstIpStr, trimmed)) return true
            } else if (trimmed.contains(":")) {
                // IP:Port or Host:Port
                val parts = trimmed.split(":")
                if (parts.size == 2) {
                    val host = parts[0].trim()
                    val port = parts[1].trim().toIntOrNull()
                    if ((host == dstIpStr || host == "*") && (port == null || port == dstPort)) {
                        return true
                    }
                }
            } else {
                if (trimmed.equals(dstIpStr, ignoreCase = true)) return true
            }
        }
        return false
    }

    /**
     * Strict CIDR matcher for IPv4 addresses without string-prefix matching.
     */
    fun matchesCidr(ipStr: String, cidr: String): Boolean {
        return try {
            val parts = cidr.split("/")
            if (parts.size != 2) return false
            val networkIp = parseIpv4ToInt(parts[0].trim()) ?: return false
            val prefixLen = parts[1].trim().toIntOrNull() ?: return false
            if (prefixLen !in 0..32) return false

            val targetIp = parseIpv4ToInt(ipStr) ?: return false

            if (prefixLen == 0) return true
            val mask = (-1 shl (32 - prefixLen))
            (targetIp and mask) == (networkIp and mask)
        } catch (_: Exception) {
            false
        }
    }

    fun parseIp(ipStr: String): ByteArray? {
        val trimmed = ipStr.trim()
        if (trimmed.isEmpty()) return null
        val v4 = parseIpv4(trimmed)
        if (v4 != null) return v4
        return try {
            if (trimmed.contains(":")) {
                val addr = InetAddress.getByName(trimmed)
                if (addr.address.size == 16 || addr.address.size == 4) addr.address else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun parseIpv4(ipStr: String): ByteArray? {
        val parts = ipStr.split(".")
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val num = parts[i].toIntOrNull() ?: return null
            if (num !in 0..255) return null
            bytes[i] = num.toByte()
        }
        return bytes
    }

    fun parseIpv4ToInt(ipStr: String): Int? {
        val bytes = parseIpv4(ipStr) ?: return null
        return ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
    }

    fun formatIp(ip: ByteArray): String {
        if (ip.size == 4) {
            return "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}.${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
        } else if (ip.size == 16) {
            return try {
                InetAddress.getByAddress(ip).hostAddress ?: "::"
            } catch (_: Exception) {
                "::"
            }
        }
        return "0.0.0.0"
    }
}
