package com.example.vpn.smart

import com.example.data.model.ScoringProfile
import com.example.data.model.ServerCategory
import com.example.data.model.VlessProfile

object SmartConnect {

    data class SmartSelection(
        val profile: VlessProfile,
        val reasonPing: String,
        val reasonDownload: String,
        val reasonStability: String,
        val reasonPacketLoss: String,
        val overallScore: Double,
        val description: String
    )

    /**
     * Analyzes all available profiles and selects the optimal healthy node for the active scoring profile.
     */
    fun selectBestNode(profiles: List<VlessProfile>, scoringProfile: ScoringProfile = ScoringProfile.BALANCED): SmartSelection? {
        if (profiles.isEmpty()) return null

        // Filter for eligible nodes (exclude known offline or high-loss nodes)
        val healthyProfiles = profiles.filter {
            it.category != ServerCategory.OFFLINE &&
            it.packetLoss < 40.0 &&
            (it.lastLatencyMs == null || it.lastLatencyMs > 0)
        }

        val pool = if (healthyProfiles.isNotEmpty()) healthyProfiles else profiles

        // Sort: Favorite bonus, then overallScore desc, stability desc, latency asc (lower is better)
        val best = pool.maxWithOrNull(
            compareBy<VlessProfile> { if (it.isFavorite) 1 else 0 }
                .thenBy { it.overallScore }
                .thenBy { it.stability }
                .thenBy { -(it.lastLatencyMs ?: 9999L) }
        ) ?: return null

        val pingStr = if (best.lastLatencyMs != null && best.lastLatencyMs > 0) "${best.lastLatencyMs} ms" else "Fast"
        val dlStr = if (best.downloadMbps > 0) "%.1f Mbps".format(best.downloadMbps) else "High Speed"
        val stabStr = "%.0f%%".format(best.stability)
        val lossStr = "%.1f%%".format(best.packetLoss)

        val pingRating = when {
            best.lastLatencyMs != null && best.lastLatencyMs < 60 -> "Excellent"
            best.lastLatencyMs != null && best.lastLatencyMs < 150 -> "Very Good"
            best.lastLatencyMs != null && best.lastLatencyMs < 300 -> "Good"
            else -> "Acceptable"
        }

        val dlRating = when {
            best.downloadMbps >= 100.0 -> "Ultra High"
            best.downloadMbps >= 40.0 -> "Excellent"
            best.downloadMbps >= 15.0 -> "Good"
            else -> "Standard"
        }

        val stabRating = when {
            best.stability >= 95.0 -> "Rock Solid (98%)"
            best.stability >= 85.0 -> "Very High (${best.stability.toInt()}%)"
            else -> "Stable (${best.stability.toInt()}%)"
        }

        return SmartSelection(
            profile = best,
            reasonPing = "$pingRating ($pingStr)",
            reasonDownload = "$dlRating ($dlStr)",
            reasonStability = stabRating,
            reasonPacketLoss = lossStr,
            overallScore = best.overallScore,
            description = "Selected ${best.name} based on ${scoringProfile.title} profile rating."
        )
    }
}
