package com.example

import com.example.data.model.*
import com.example.vpn.ServerTester
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ServerTransportProbeTest {
    @Test fun websocket404IsNotReportedAsAvailable() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0).use { server ->
                server.soTimeout = 3000
                val peer = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        socket.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    }
                }
                val result = ServerTester.testServer(VlessProfile(name="local", address="127.0.0.1", port=server.localPort,
                    uuid="00000000-0000-0000-0000-000000000001", transport="ws"), timeoutMs=2000)
                assertTrue(result.status is ServerTestStatus.Unavailable)
                assertTrue((result.status as ServerTestStatus.Unavailable).reason.contains("Host and path"))
                peer.get(4, TimeUnit.SECONDS)
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun failedSocketProtectionStopsTheProbe() = runBlocking {
        val result = ServerTester.testServer(VlessProfile(name="local", address="127.0.0.1", port=9,
            uuid="00000000-0000-0000-0000-000000000001"), protectSocket={ false })
        assertEquals("VPN socket protection failed", (result.status as ServerTestStatus.Unavailable).reason)
    }
}
