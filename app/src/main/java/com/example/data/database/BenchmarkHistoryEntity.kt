package com.example.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.BenchmarkStageResult
import com.example.data.model.ServerCategory

@Entity(
    tableName = "benchmark_runs",
    indices = [
        Index(value = ["serverId"]),
        Index(value = ["testedAt"])
    ]
)
data class BenchmarkHistoryEntity(
    @PrimaryKey(autoGenerate = true) val runId: Long = 0L,
    val serverId: String,
    val dnsLatencyMs: Long,
    val tcpHandshakeMs: Long,
    val tlsHandshakeMs: Long,
    val proxyHandshakeMs: Long,
    val ttfbMs: Long,
    val pingMs: Long,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val jitterMs: Long,
    val packetLossPercent: Double,
    val stabilityPercent: Double,
    val overallScore: Double,
    val category: String,
    val isSuccess: Boolean,
    val errorMessage: String?,
    val testedAt: Long
) {
    fun toDomain(): BenchmarkStageResult = BenchmarkStageResult(
        serverId = serverId,
        dnsLatencyMs = dnsLatencyMs,
        tcpHandshakeMs = tcpHandshakeMs,
        tlsHandshakeMs = tlsHandshakeMs,
        proxyHandshakeMs = proxyHandshakeMs,
        ttfbMs = ttfbMs,
        pingMs = pingMs,
        downloadMbps = downloadMbps,
        uploadMbps = uploadMbps,
        jitterMs = jitterMs,
        packetLossPercent = packetLossPercent,
        stabilityPercent = stabilityPercent,
        overallScore = overallScore,
        category = try { ServerCategory.valueOf(category) } catch (_: Exception) { ServerCategory.BALANCED },
        isSuccess = isSuccess,
        errorMessage = errorMessage,
        testedAt = testedAt
    )

    companion object {
        fun fromDomain(res: BenchmarkStageResult): BenchmarkHistoryEntity = BenchmarkHistoryEntity(
            serverId = res.serverId,
            dnsLatencyMs = res.dnsLatencyMs,
            tcpHandshakeMs = res.tcpHandshakeMs,
            tlsHandshakeMs = res.tlsHandshakeMs,
            proxyHandshakeMs = res.proxyHandshakeMs,
            ttfbMs = res.ttfbMs,
            pingMs = res.pingMs,
            downloadMbps = res.downloadMbps,
            uploadMbps = res.uploadMbps,
            jitterMs = res.jitterMs,
            packetLossPercent = res.packetLossPercent,
            stabilityPercent = res.stabilityPercent,
            overallScore = res.overallScore,
            category = res.category.name,
            isSuccess = res.isSuccess,
            errorMessage = res.errorMessage,
            testedAt = res.testedAt
        )
    }
}
