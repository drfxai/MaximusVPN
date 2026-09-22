package com.example

import com.example.data.model.EngineType
import com.example.data.model.ProtocolType
import com.example.mihomo.MihomoParser
import com.example.vpn.dns.DoHClient
import com.example.vpn.engine.ConfigurationAdapter
import com.example.vpn.engine.UniversalImportEngine
import com.example.xray.XrayConfigParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MihomoAndUniversalAdapterTest {

    private val sampleMihomoYaml = """
        port: 7890
        socks-port: 7891
        allow-lan: false
        mode: rule
        log-level: info
        dns:
          enable: true
          nameserver:
            - 1.1.1.1
            - 8.8.8.8
        proxies:
          - name: "Tokyo-VLESS-Reality"
            type: vless
            server: tokyo.example.com
            port: 443
            uuid: 12345678-1234-1234-1234-123456789abc
            network: ws
            tls: true
            servername: tokyo.example.com
            reality-opts:
              public-key: testPublicKey1234567890abcdef
              short-id: 1a2b3c
            ws-opts:
              path: /ray
          - name: "SG-Shadowsocks"
            type: ss
            server: sg.example.com
            port: 8388
            cipher: 2022-blake3-aes-128-gcm
            password: secretpassword123
        proxy-groups:
          - name: PROXY
            type: select
            proxies:
              - Tokyo-VLESS-Reality
              - SG-Shadowsocks
          - name: AUTO-TEST
            type: url-test
            url: http://www.gstatic.com/generate_204
            interval: 300
            proxies:
              - Tokyo-VLESS-Reality
              - SG-Shadowsocks
        rules:
          - DOMAIN-SUFFIX,google.com,PROXY
          - GEOIP,CN,DIRECT
          - MATCH,PROXY
    """.trimIndent()

    private val sampleXrayJson = """
        {
          "log": {
            "loglevel": "warning"
          },
          "inbounds": [
            {
              "port": 10808,
              "protocol": "socks",
              "settings": {
                "auth": "noauth",
                "udp": true
              }
            }
          ],
          "outbounds": [
            {
              "tag": "proxy",
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {
                    "address": "fast.server.org",
                    "port": 443,
                    "users": [
                      {
                        "id": "11111111-2222-3333-4444-555555555555",
                        "encryption": "none",
                        "flow": "xtls-rprx-vision"
                      }
                    ]
                  }
                ]
              },
              "streamSettings": {
                "network": "tcp",
                "security": "reality",
                "realitySettings": {
                  "serverName": "yahoo.com",
                  "publicKey": "pubkey1234567890",
                  "shortId": "abcdef"
                }
              }
            },
            {
              "tag": "direct",
              "protocol": "freedom"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun testMihomoYamlParsing() {
        val config = MihomoParser.parseYaml(sampleMihomoYaml)
        assertNotNull(config)
        assertEquals(7890, config.port)
        assertEquals(7891, config.socksPort)
        assertEquals(2, config.proxies.size)

        val vlessProxy = config.proxies.first { it.type == ProtocolType.VLESS }
        assertEquals("Tokyo-VLESS-Reality", vlessProxy.name)
        assertEquals("tokyo.example.com", vlessProxy.server)
        assertEquals(443, vlessProxy.port)
        assertEquals("testPublicKey1234567890abcdef", vlessProxy.publicKey)
        assertEquals("/ray", vlessProxy.path)

        val ssProxy = config.proxies.first { it.type == ProtocolType.SHADOWSOCKS }
        assertEquals("SG-Shadowsocks", ssProxy.name)
        assertEquals(8388, ssProxy.port)

        assertEquals(2, config.proxyGroups.size)
        val urlTestGroup = config.proxyGroups.first { it.type == "url-test" }
        assertEquals("AUTO-TEST", urlTestGroup.name)
        assertEquals(300, urlTestGroup.intervalSeconds)
        assertEquals(2, urlTestGroup.proxies.size)

        assertEquals(3, config.rules.size)
    }

    @Test
    fun testXrayJsonParsing() {
        val profiles = XrayConfigParser.parseJson(sampleXrayJson)
        assertTrue(profiles.isNotEmpty())
        val profile = profiles.first()
        assertNotNull(profile)
        assertEquals("fast.server.org", profile.address)
        assertEquals(443, profile.port)
        assertEquals("11111111-2222-3333-4444-555555555555", profile.uuid)
        assertEquals("tcp", profile.transport)
        assertEquals("reality", profile.security)
        assertEquals("yahoo.com", profile.sni)
        assertEquals("pubkey1234567890", profile.publicKey)
        assertEquals("abcdef", profile.shortId)
        assertEquals("xtls-rprx-vision", profile.flow)
    }

    @Test
    fun testUniversalConfigurationAdapter() {
        // 1. YAML Detection and Conversion
        val yamlProfiles = ConfigurationAdapter.importConfiguration(sampleMihomoYaml, "https://sub.url/mihomo.yaml")
        assertTrue(yamlProfiles.isNotEmpty())
        val firstYaml = yamlProfiles.first()
        assertEquals(EngineType.MIHOMO, firstYaml.engineType)
        assertEquals(2, firstYaml.nodeCount)

        // 2. JSON Detection and Conversion
        val jsonProfiles = ConfigurationAdapter.importConfiguration(sampleXrayJson)
        assertTrue(jsonProfiles.isNotEmpty())
        val firstJson = jsonProfiles.first()
        assertEquals(EngineType.XRAY, firstJson.engineType)
        assertEquals("fast.server.org", firstJson.address)

        // 3. Standard Links Detection
        val ssLink = "ss://YWVzLTI1Ni1nY206cGFzc3dvcmRAMTkyLjE2OC4xMDAuMTo4Mzg4#MySSNode"
        val ssProfiles = ConfigurationAdapter.importConfiguration(ssLink)
        assertEquals(1, ssProfiles.size)
        assertEquals(ProtocolType.SHADOWSOCKS, ssProfiles.first().protocolType)

        val trojanLink = "trojan://secret-password@trojan.vpn.com:443?sni=trojan.vpn.com#TrojanServer"
        val trojanProfiles = ConfigurationAdapter.importConfiguration(trojanLink)
        assertEquals(1, trojanProfiles.size)
        assertEquals(ProtocolType.TROJAN, trojanProfiles.first().protocolType)
        assertEquals("trojan.vpn.com", trojanProfiles.first().address)

        // 4. Robust parsing with emojis and special characters in fragments
        val trojanWithEmoji = "trojan://pass123@1.2.3.4:443?security=tls#🚀 Tokyo | Server #1"
        val parsedTrojanEmoji = ConfigurationAdapter.parseTrojanUri(trojanWithEmoji)
        assertNotNull(parsedTrojanEmoji)
        assertEquals("1.2.3.4", parsedTrojanEmoji!!.address)
        assertEquals(443, parsedTrojanEmoji.port)
        assertEquals("pass123", parsedTrojanEmoji.uuid)
        assertEquals("🚀 Tokyo | Server #1", parsedTrojanEmoji.name)

        val hy2WithEmoji = "hy2://mypassword@5.6.7.8:8443?sni=fast.net&alpn=h3#⚡ Hysteria Node"
        val parsedHy2Emoji = ConfigurationAdapter.parseHysteria2Uri(hy2WithEmoji)
        assertNotNull(parsedHy2Emoji)
        assertEquals("5.6.7.8", parsedHy2Emoji!!.address)
        assertEquals(8443, parsedHy2Emoji.port)
        assertEquals("mypassword", parsedHy2Emoji.uuid)
        assertEquals("⚡ Hysteria Node", parsedHy2Emoji.name)
        assertEquals(EngineType.MIHOMO, parsedHy2Emoji.engineType)

        // 5. UniversalImportEngine with SOCKS5, HTTP, and TUIC
        val socksItem = UniversalImportEngine.parseSocks5Uri("socks5://user:pass@127.0.0.1:1080#Local Socks5")
        assertTrue(socksItem is UniversalImportEngine.ParsedItem.Success)

        val httpItem = UniversalImportEngine.parseHttpProxyUri("http://proxy.corp.com:8080#Corporate Proxy")
        assertTrue(httpItem is UniversalImportEngine.ParsedItem.Success)
    }

    @Test
    fun testVlessHeaderIPv6BracketHandling() {
        val uuid = java.util.UUID.randomUUID().toString()
        val uuidBytes = com.example.vless.VlessHeader.uuidToBytes(uuid)
        // With brackets
        val headerWithBrackets = com.example.vless.VlessHeader.encodeRequest(
            uuidBytes = uuidBytes,
            command = com.example.vless.VlessHeader.COMMAND_TCP,
            destPort = 443,
            destAddress = "[2001:db8::1]"
        )
        assertNotNull(headerWithBrackets)
        assertTrue(headerWithBrackets.isNotEmpty())
        // ATYPE_IPV6 is at index: 1 (ver) + 16 (uuid) + 1 (addonLen) + 1 (command) + 2 (port) = 21
        assertEquals(com.example.vless.VlessHeader.ATYPE_IPV6.toInt(), headerWithBrackets[21].toInt() and 0xFF)
    }

    @Test
    fun testWebSocketCodecEncodingAndDecoding() {
        val originalData = "Hello WebSocket VLESS Tunnel Secure Stream Payload".toByteArray(Charsets.UTF_8)
        val encodedFrame = com.example.vpn.tunnel.WebSocketCodec.encodeFrame(originalData)
        assertTrue(encodedFrame.isNotEmpty())
        assertTrue(encodedFrame.size > originalData.size) // Has header + 4 byte mask

        val inputStream = java.io.ByteArrayInputStream(encodedFrame)
        val decodedPayload = com.example.vpn.tunnel.WebSocketCodec.readFrame(inputStream)
        assertNotNull(decodedPayload)
        assertEquals(String(originalData, Charsets.UTF_8), String(decodedPayload!!, Charsets.UTF_8))
    }
}
