package com.example.vpn

import com.example.data.model.ServerTestResult
import com.example.data.model.ServerTestStatus
import com.example.data.model.VlessProfile
import com.example.vless.VlessValidator
import com.example.xray.XrayLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket

object ServerTester {

    /**
     * Performs a real multi-stage connectivity and latency test against the remote VLESS endpoint.
     * Tests configuration, DNS, TCP, TLS and WebSocket transport. Does not authenticate a proxy request or prove tunneled internet access.
     */
    suspend fun testServer(
        profile: VlessProfile,
        timeoutMs: Int = 4000,
        protectSocket: ((Socket) -> Boolean)? = null
    ): ServerTestResult = withContext(Dispatchers.IO) {
        try {
            VlessValidator.validate(profile)
            com.example.vpn.engine.RuntimeCapabilities.requireSupported(profile)
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Invalid configuration"
            XrayLogManager.w("SERVER", "Validation check failed for '${profile.name}': $msg")
            return@withContext ServerTestResult(
                serverId = profile.id,
                status = ServerTestStatus.InvalidConfig(msg)
            )
        }

        XrayLogManager.d("SERVER", "Initiating health check for '${profile.name}' (${profile.address}:${profile.port}, transport=${profile.transport}, sec=${profile.security})...")

        val startTime = System.nanoTime()
        var socket: Socket? = null
        var sslSocket: SSLSocket? = null

        try {
            // Stage 1: DNS Resolution
            val inetAddress = InetAddress.getByName(profile.address)

            // Stage 2: TCP Handshake
            socket = Socket()
            check(protectSocket?.invoke(socket) != false) { "VPN socket protection failed" }
            socket.soTimeout = timeoutMs
            val socketAddress = InetSocketAddress(inetAddress, profile.port)
            socket.connect(socketAddress, timeoutMs)

            val tcpLatency = ((System.nanoTime() - startTime) / 1_000_000).coerceAtLeast(1)

            // Stage 3: TLS / Handshake Test if configured
            val sslLatency = if (profile.security.equals("tls", ignoreCase = true) || profile.security.equals("reality", ignoreCase = true)) {
                val isReality = profile.security.equals("reality", ignoreCase = true)
                val sslContext = if (isReality) {
                    val realityTrustManager = object : javax.net.ssl.X509TrustManager {
                        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
                            com.example.vpn.tunnel.RealityVerifier.verifyRealityPeer(chain, profile.publicKey)
                        }
                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                    }
                    try {
                        SSLContext.getInstance("TLSv1.3").apply {
                            init(null, arrayOf<javax.net.ssl.TrustManager>(realityTrustManager), java.security.SecureRandom())
                        }
                    } catch (_: Exception) {
                        SSLContext.getInstance("TLS").apply {
                            init(null, arrayOf<javax.net.ssl.TrustManager>(realityTrustManager), java.security.SecureRandom())
                        }
                    }
                } else {
                    try {
                        SSLContext.getDefault()
                    } catch (_: Exception) {
                        SSLContext.getInstance("TLS").apply { init(null, null, java.security.SecureRandom()) }
                    }
                }
                val sslFactory = sslContext.socketFactory
                val sniHost = profile.sni.ifBlank { profile.host.ifBlank { profile.address } }
                sslSocket = sslFactory.createSocket(socket, sniHost, profile.port, true) as SSLSocket
                sslSocket.soTimeout = timeoutMs

                val sslParams = SSLParameters().apply {
                    if (!isReality) endpointIdentificationAlgorithm = "HTTPS"
                    if (sniHost.isNotBlank() && !sniHost.contains(':') && !sniHost.matches(Regex("[0-9.]+"))) {
                        serverNames = listOf(SNIHostName(sniHost))
                    }
                }
                sslSocket.sslParameters = sslParams
                sslSocket.startHandshake()
                ((System.nanoTime() - startTime) / 1_000_000).coerceAtLeast(1)
            } else {
                tcpLatency
            }

            // Stage 4: WebSocket Handshake Validation if transport is WS
            val finalLatency = if (profile.transport.equals("ws", ignoreCase = true)) {
                com.example.vpn.tunnel.WebSocketHandshake.perform(sslSocket ?: socket, profile, timeoutMs)
                ((System.nanoTime() - startTime) / 1_000_000).coerceAtLeast(1)
            } else {
                sslLatency
            }

            val status = if (finalLatency < 350) {
                ServerTestStatus.Available(finalLatency)
            } else {
                ServerTestStatus.Slow(finalLatency)
            }

            XrayLogManager.i("SERVER", "Server '${profile.name}' responded in ${finalLatency}ms (Status: ${if (finalLatency < 350) "EXCELLENT" else "SLOW"})")

            ServerTestResult(serverId = profile.id, status = status)
        } catch (e: java.net.SocketTimeoutException) {
            val err = "Connection timed out (${timeoutMs}ms)"
            XrayLogManager.w("SERVER", "Health check timeout for '${profile.name}' (${profile.address}:${profile.port}) after ${timeoutMs}ms")
            ServerTestResult(
                serverId = profile.id,
                status = ServerTestStatus.Unavailable(err)
            )
        } catch (e: java.net.UnknownHostException) {
            val err = "DNS resolution failed for '${profile.address}'"
            XrayLogManager.w("SERVER", "Health check DNS resolution failure for '${profile.name}': $err")
            ServerTestResult(
                serverId = profile.id,
                status = ServerTestStatus.Unavailable(err)
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val err = e.localizedMessage ?: "Connection refused"
            XrayLogManager.w("SERVER", "Health check connection failed for '${profile.name}' (${profile.address}:${profile.port}): $err", e)
            ServerTestResult(
                serverId = profile.id,
                status = ServerTestStatus.Unavailable(err)
            )
        } finally {
            try { sslSocket?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
        }
    }

}
