package com.example.vpn.benchmark

import com.example.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TransportBenchmarkTest {
    private val profile = VlessProfile(name = "test", address = "example.invalid", port = 443, uuid = "00000000-0000-0000-0000-000000000001")

    @Test fun rejectedWebSocketIsAFailedBenchmark() = runBlocking {
        val result = measureTransportBenchmark(profile, BenchmarkMode.QUICK, ScoringProfile.BALANCED) {
            ServerTestResult(it.id, ServerTestStatus.Unavailable("WebSocket HTTP 404"))
        }
        assertFalse(result.isSuccess)
        assertEquals(ServerCategory.OFFLINE, result.category)
        assertEquals(100.0, result.packetLossPercent, 0.0)
        assertEquals("WebSocket HTTP 404", result.errorMessage)
        assertEquals(0.0, result.downloadMbps, 0.0)
    }

    @Test fun slowSuccessDoesNotInventBandwidth() = runBlocking {
        val result = measureTransportBenchmark(profile, BenchmarkMode.QUICK, ScoringProfile.BALANCED) {
            ServerTestResult(it.id, ServerTestStatus.Slow(1500))
        }
        assertTrue(result.isSuccess)
        assertEquals(1500L, result.pingMs)
        assertEquals(100.0, result.successRatePercent, 0.0)
        assertEquals(0.0, result.downloadMbps, 0.0)
        assertEquals(0.0, result.uploadMbps, 0.0)
    }

    @Test fun countsFailedSamples() = runBlocking {
        var calls = 0
        val result = measureTransportBenchmark(profile, BenchmarkMode.QUICK, ScoringProfile.BALANCED) {
            ServerTestResult(it.id, if (calls++ == 0) ServerTestStatus.Unavailable("timeout") else ServerTestStatus.Available(100))
        }
        assertEquals(3, calls)
        assertEquals(100.0 / 3, result.packetLossPercent, 0.01)
    }

    @Test fun cancellationPropagates() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                measureTransportBenchmark(profile, BenchmarkMode.QUICK, ScoringProfile.BALANCED) { throw CancellationException("cancelled") }
            }
        }
    }
}
