package com.example

import com.example.data.model.VlessProfile
import com.example.vpn.protectTcpSocket
import com.example.vpn.smart.FailoverManager
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class SocketProtectionRegressionTest {
    private val active = VlessProfile(name = "active", address = "45.63.91.162", port = 38706,
        uuid = "00000000-0000-0000-0000-000000000001")

    @Test fun protectionReceivesBoundUnconnectedSocket() {
        Socket().use { socket ->
            assertTrue(protectTcpSocket(socket) { candidate ->
                assertSame(socket, candidate)
                assertTrue(candidate.isBound)
                assertFalse(candidate.isConnected)
                true
            })
            assertTrue(socket.isBound)
        }
    }

    @Test fun rejectionStaysARejection() {
        Socket().use { socket ->
            assertFalse(protectTcpSocket(socket) { false })
        }
    }

    @Test fun failoverExcludesDuplicateImportsAndSameEndpoint() {
        val choices = listOf(
            active,
            active.copy(id = "imported", name = "same config"),
            active.copy(id = "different-credentials", uuid = "00000000-0000-0000-0000-000000000002"),
            active.copy(id = "real-backup", address = "example.net")
        )
        assertEquals(listOf("real-backup"), FailoverManager.eligibleFallbacks(choices, active).map { it.id })
    }
}
