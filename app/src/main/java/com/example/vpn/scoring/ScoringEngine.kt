package com.example.vpn.scoring

import com.example.data.model.ScoringProfile
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

object ScoringEngine {

    data class ScoreBreakdown(
        val speedScore: Double,
        val latencyScore: Double,
        val stabilityScore: Double,
        val reliabilityScore: Double,
        val overallScore: Double
    )

    /**
     * Calculates normalized scores (0.0 to 100.0) based on real network measurements
     * and the selected user priority profile.
     */
    fun calculateScores(
        speedMbps: Double,
        latencyMs: Long,
        jitterMs: Long,
        packetLoss: Double,
        stability: Double,
        successRate: Double,
        scoringProfile: ScoringProfile = ScoringProfile.BALANCED
    ): ScoreBreakdown {
        if (latencyMs <= 0 || successRate <= 0.0 || packetLoss >= 90.0) {
            return ScoreBreakdown(0.0, 0.0, 0.0, 0.0, 0.0)
        }

        // 1. Normalize Speed Score (0-100) using logarithmic curve (e.g. 100 Mbps -> ~90, 200+ Mbps -> 98+)
        val clampedSpeed = max(0.1, speedMbps)
        val speedScore = ((ln(clampedSpeed + 1.0) / ln(250.0)) * 100.0).coerceIn(0.0, 100.0)

        // 2. Normalize Latency Score (0-100) (e.g. 20ms -> 98, 80ms -> 80, 250ms -> 40, 600ms+ -> 0)
        val latencyScore = when {
            latencyMs <= 20 -> 100.0
            latencyMs <= 50 -> 95.0 - ((latencyMs - 20) * 0.5)
            latencyMs <= 150 -> 80.0 - ((latencyMs - 50) * 0.35)
            latencyMs <= 350 -> 45.0 - ((latencyMs - 150) * 0.15)
            latencyMs <= 700 -> 15.0 - ((latencyMs - 350) * 0.035)
            else -> 0.0
        }.coerceIn(0.0, 100.0)

        // 3. Normalize Stability Score (0-100) based on stability %, jitter, and packet loss
        val jitterPenalty = (jitterMs * 1.2).coerceAtMost(40.0)
        val lossPenalty = (packetLoss * 2.0).coerceAtMost(50.0)
        val stabilityScore = (stability - jitterPenalty - lossPenalty).coerceIn(0.0, 100.0)

        // 4. Normalize Reliability Score (0-100) based on success rate and low loss
        val reliabilityScore = (successRate * 0.7 + (100.0 - packetLoss) * 0.3).coerceIn(0.0, 100.0)

        // 5. Compute Weighted Overall Score based on Active Profile
        val overallScore = when (scoringProfile) {
            ScoringProfile.BALANCED -> {
                // 45% Speed, 30% Ping, 15% Stability, 10% Packet Loss / Reliability
                (speedScore * 0.45) + (latencyScore * 0.30) + (stabilityScore * 0.15) + (reliabilityScore * 0.10)
            }
            ScoringProfile.GAMING -> {
                // 45% Latency, 25% Stability / Jitter, 20% Packet Loss / Reliability, 10% Speed
                (latencyScore * 0.45) + (stabilityScore * 0.25) + (reliabilityScore * 0.20) + (speedScore * 0.10)
            }
            ScoringProfile.STREAMING -> {
                // 50% Speed, 25% Stability, 15% Latency, 10% Reliability
                (speedScore * 0.50) + (stabilityScore * 0.25) + (latencyScore * 0.15) + (reliabilityScore * 0.10)
            }
            ScoringProfile.DOWNLOADING -> {
                // 65% Speed, 20% Stability, 10% Reliability, 5% Latency
                (speedScore * 0.65) + (stabilityScore * 0.20) + (reliabilityScore * 0.10) + (latencyScore * 0.05)
            }
            ScoringProfile.LOW_LATENCY -> {
                // 60% Latency, 20% Stability, 10% Reliability, 10% Speed
                (latencyScore * 0.60) + (stabilityScore * 0.20) + (reliabilityScore * 0.10) + (speedScore * 0.10)
            }
            ScoringProfile.MAX_STABILITY -> {
                // 45% Stability, 30% Reliability, 15% Latency, 10% Speed
                (stabilityScore * 0.45) + (reliabilityScore * 0.30) + (latencyScore * 0.15) + (speedScore * 0.10)
            }
        }.coerceIn(0.0, 100.0)

        return ScoreBreakdown(
            speedScore = Math.round(speedScore * 10.0) / 10.0,
            latencyScore = Math.round(latencyScore * 10.0) / 10.0,
            stabilityScore = Math.round(stabilityScore * 10.0) / 10.0,
            reliabilityScore = Math.round(reliabilityScore * 10.0) / 10.0,
            overallScore = Math.round(overallScore * 10.0) / 10.0
        )
    }
}
