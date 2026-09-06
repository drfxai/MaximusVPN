package com.example

import com.example.core.SecretRedactor
import com.example.data.model.AppSettings
import com.example.data.model.ProtocolType
import com.example.data.model.RoutingMode
import com.example.data.security.SecureStorage
import com.example.vpn.dns.DoHClient
import com.example.vpn.engine.ConfigurationAdapter
import com.example.vpn.packet.IPv4Header
import com.example.vpn.packet.IpProtocol
import com.example.vpn.routing.RoutingDecision
import com.example.vpn.routing.RoutingEngine
import com.example.vpn.subscription.SubscriptionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class SecurityAndNetworkingRemediationTest {

    @Test
    fun testRoutingEngineModesAndKillSwitch() {
        val baseSettings = AppSettings(
            killSwitchEnabled = true,
            routingMode = RoutingMode.RULE_BYPASS_LAN,
            customBypassRules = "10.0.0.0/8, 192.168.1.100, customdomain.com:8080"
        )

        // 1. Kill Switch Active and Tunnel Disconnected -> BLOCK
        val dstPublicIp = byteArrayOf(8, 8, 8, 8)
        val killSwitchDecision = RoutingEngine.evaluate(
            dstIp = dstPublicIp,
            dstPort = 443,
            protocol = IpProtocol.TCP,
            settings = baseSettings,
            isTunnelConnected = false
        )
        assertEquals(RoutingDecision.BLOCK, killSwitchDecision)

        // 2. Kill Switch Active and Tunnel Connected -> PROXY
        val connectedDecision = RoutingEngine.evaluate(
            dstIp = dstPublicIp,
            dstPort = 443,
            protocol = IpProtocol.TCP,
            settings = baseSettings,
            isTunnelConnected = true
        )
        assertEquals(RoutingDecision.PROXY, connectedDecision)

        // 3. LAN Address -> DIRECT
        val dstLanIp = byteArrayOf(192.toInt().toByte(), 168.toInt().toByte(), 1, 1)
        val lanDecision = RoutingEngine.evaluate(
            dstIp = dstLanIp,
            dstPort = 80,
            protocol = IpProtocol.TCP,
            settings = baseSettings.copy(killSwitchEnabled = false),
            isTunnelConnected = true
        )
        assertEquals(RoutingDecision.DIRECT, lanDecision)
    }

    @Test
    fun testCidrAndBypassRules() {
        assertTrue(RoutingEngine.matchesCidr("192.168.1.50", "192.168.1.0/24"))
        assertFalse(RoutingEngine.matchesCidr("192.168.2.50", "192.168.1.0/24"))

        val bypassList = listOf("10.0.0.0/8", "192.168.1.100", "example.com:443")
        assertTrue(RoutingEngine.isBypassedDestination("10.50.1.1", 80, bypassList))
        assertTrue(RoutingEngine.isBypassedDestination("192.168.1.100", 80, bypassList))
        assertFalse(RoutingEngine.isBypassedDestination("8.8.8.8", 53, bypassList))
    }

    @Test
    fun testSubscriptionSsrfProtection() {
        // Localhost/Metadata hosts must be blocked
        assertTrue(SubscriptionManager.isBlockedHost("localhost"))
        assertTrue(SubscriptionManager.isBlockedHost("127.0.0.1"))
        assertTrue(SubscriptionManager.isBlockedHost("169.254.169.254"))
        assertTrue(SubscriptionManager.isBlockedHost("10.0.0.1"))

        // Public hosts allowed
        assertFalse(SubscriptionManager.isBlockedHost("raw.githubusercontent.com"))
        assertFalse(SubscriptionManager.isBlockedHost("example.com"))

        // URL validation
        assertTrue(SubscriptionManager.isValidSubscriptionUrl("https://example.com/sub.txt"))
        assertFalse(SubscriptionManager.isValidSubscriptionUrl("http://127.0.0.1/secret"))
        assertFalse(SubscriptionManager.isValidSubscriptionUrl("file:///etc/passwd"))
    }

    @Test
    fun testConfigurationAdapterParsers() {
        // Shadowsocks URI
        val ssUri = "ss://YWVzLTEyOC1nY206cGFzc3dvcmQxMjM=@1.2.3.4:8388#TestSS"
        val ssProfile = ConfigurationAdapter.parseShadowsocksUri(ssUri)
        assertNotNull(ssProfile)
        assertEquals("1.2.3.4", ssProfile?.address)
        assertEquals(8388, ssProfile?.port)
        assertEquals(ProtocolType.SHADOWSOCKS, ssProfile?.protocolType)

        // Trojan URI
        val trojanUri = "trojan://secretpass@5.6.7.8:443?sni=trojan.example.com#TestTrojan"
        val trojanProfile = ConfigurationAdapter.parseTrojanUri(trojanUri)
        assertNotNull(trojanProfile)
        assertEquals("5.6.7.8", trojanProfile?.address)
        assertEquals("secretpass", trojanProfile?.uuid)
        assertEquals("trojan.example.com", trojanProfile?.sni)
        assertEquals(ProtocolType.TROJAN, trojanProfile?.protocolType)

        // Hysteria2 URI
        val hy2Uri = "hysteria2://myhy2pass@9.10.11.12:8443?sni=hy2.example.com#TestHy2"
        val hy2Profile = ConfigurationAdapter.parseHysteria2Uri(hy2Uri)
        assertNotNull(hy2Profile)
        assertEquals("9.10.11.12", hy2Profile?.address)
        assertEquals(8443, hy2Profile?.port)
        assertEquals(ProtocolType.HYSTERIA2, hy2Profile?.protocolType)
    }

    @Test
    fun testSecretRedactor() {
        val sensitive = "Connecting to vless://e7b99c42-88f1-4b19-9182-3d84a7e93f12@1.2.3.4:443?password=secret123"
        val redacted = SecretRedactor.redact(sensitive)
        assertFalse(redacted.contains("e7b99c42-88f1-4b19-9182-3d84a7e93f12"))
        assertFalse(redacted.contains("secret123"))
        assertTrue(redacted.contains("[REDACTED_CREDENTIALS]") || redacted.contains("[REDACTED"))
    }

    @Test
    fun testMalformedPacketSafety() {
        // Truncated packet
        val truncated = byteArrayOf(0x45, 0x00, 0x00)
        val header = IPv4Header.parse(truncated)
        assertNull(header)

        // Fuzzed garbage bytes
        val fuzzed = ByteArray(100) { (it * 37).toByte() }
        val fuzzedHeader = IPv4Header.parse(fuzzed)
        // Parse shouldn't throw exception
    }
}
