package com.example.ui.top10

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ScoringProfile
import com.example.data.model.Top10Category
import com.example.data.model.VlessProfile
import com.example.data.repository.ServerRepository
import com.example.vpn.scoring.ScoringEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Top10UiState(
    val selectedCategory: Top10Category = Top10Category.OVERALL,
    val rankedServers: List<RankedNode> = emptyList(),
    val totalServerCount: Int = 0,
    val isLoading: Boolean = false
)

data class RankedNode(
    val rank: Int,
    val profile: VlessProfile,
    val primaryMetric: String,
    val secondaryMetric: String,
    val scoreBadge: String,
    val rankMedal: String
)

class Top10ViewModel(
    private val serverRepository: ServerRepository
) : ViewModel() {

    private val _selectedCategory = MutableStateFlow(Top10Category.OVERALL)
    val selectedCategory: StateFlow<Top10Category> = _selectedCategory.asStateFlow()

    val uiState: StateFlow<Top10UiState> = combine(
        serverRepository.allProfiles,
        _selectedCategory
    ) { allNodes, category ->
        val ranked = calculateRankings(allNodes, category)
        Top10UiState(
            selectedCategory = category,
            rankedServers = ranked,
            totalServerCount = allNodes.size,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = Top10UiState(isLoading = true)
    )

    fun selectCategory(category: Top10Category) {
        _selectedCategory.value = category
    }

    private fun calculateRankings(nodes: List<VlessProfile>, category: Top10Category): List<RankedNode> {
        if (nodes.isEmpty()) return emptyList()

        val sortedList = when (category) {
            Top10Category.OVERALL -> nodes.sortedWith(
                compareByDescending<VlessProfile> { it.overallScore }
                    .thenBy { it.lastLatencyMs ?: 9999L }
            )
            Top10Category.FASTEST -> nodes.sortedWith(
                compareByDescending<VlessProfile> { it.downloadMbps }
                    .thenByDescending { it.overallScore }
            )
            Top10Category.LOWEST_PING -> nodes.filter { it.lastLatencyMs != null && it.lastLatencyMs > 0 }
                .sortedBy { it.lastLatencyMs }
            Top10Category.MOST_STABLE -> nodes.sortedWith(
                compareByDescending<VlessProfile> { it.stability }
                    .thenBy { it.jitterMs }
            )
            Top10Category.LOWEST_PACKET_LOSS -> nodes.sortedWith(
                compareBy<VlessProfile> { it.packetLoss }
                    .thenByDescending { it.stability }
            )
            Top10Category.GAMING -> nodes.map {
                val score = ScoringEngine.calculateScores(
                    speedMbps = it.downloadMbps,
                    latencyMs = it.lastLatencyMs ?: 999L,
                    jitterMs = it.jitterMs,
                    packetLoss = it.packetLoss,
                    stability = it.stability,
                    successRate = it.successRate,
                    scoringProfile = ScoringProfile.GAMING
                )
                Pair(it, score.overallScore)
            }.sortedByDescending { it.second }.map { it.first }

            Top10Category.STREAMING -> nodes.map {
                val score = ScoringEngine.calculateScores(
                    speedMbps = it.downloadMbps,
                    latencyMs = it.lastLatencyMs ?: 999L,
                    jitterMs = it.jitterMs,
                    packetLoss = it.packetLoss,
                    stability = it.stability,
                    successRate = it.successRate,
                    scoringProfile = ScoringProfile.STREAMING
                )
                Pair(it, score.overallScore)
            }.sortedByDescending { it.second }.map { it.first }

            Top10Category.BALANCED -> nodes.map {
                val score = ScoringEngine.calculateScores(
                    speedMbps = it.downloadMbps,
                    latencyMs = it.lastLatencyMs ?: 999L,
                    jitterMs = it.jitterMs,
                    packetLoss = it.packetLoss,
                    stability = it.stability,
                    successRate = it.successRate,
                    scoringProfile = ScoringProfile.BALANCED
                )
                Pair(it, score.overallScore)
            }.sortedByDescending { it.second }.map { it.first }
        }

        return sortedList.take(10).mapIndexed { index, profile ->
            val rank = index + 1
            val medal = when (rank) {
                1 -> "🥇"
                2 -> "🥈"
                3 -> "🥉"
                else -> "#$rank"
            }

            val (primary, secondary) = when (category) {
                Top10Category.OVERALL -> Pair(
                    "Score: %.1f".format(profile.overallScore),
                    if (profile.lastLatencyMs != null && profile.lastLatencyMs > 0) "${profile.lastLatencyMs} ms • %.1f Mbps".format(profile.downloadMbps) else "Ready"
                )
                Top10Category.FASTEST -> Pair(
                    "%.1f Mbps".format(profile.downloadMbps),
                    if (profile.lastLatencyMs != null) "${profile.lastLatencyMs} ms ping" else "Score: %.1f".format(profile.overallScore)
                )
                Top10Category.LOWEST_PING -> Pair(
                    "${profile.lastLatencyMs ?: 0} ms",
                    "Jitter: ${profile.jitterMs} ms • Stability: %.0f%%".format(profile.stability)
                )
                Top10Category.MOST_STABLE -> Pair(
                    "Stability: %.0f%%".format(profile.stability),
                    "Jitter: ${profile.jitterMs} ms • Loss: %.1f%%".format(profile.packetLoss)
                )
                Top10Category.LOWEST_PACKET_LOSS -> Pair(
                    "Loss: %.1f%%".format(profile.packetLoss),
                    "Stability: %.0f%% • Score: %.1f".format(profile.stability, profile.overallScore)
                )
                Top10Category.GAMING -> Pair(
                    "${profile.lastLatencyMs ?: 0} ms Ping",
                    "0 Jitter (${profile.jitterMs}ms) • 0 Loss (${profile.packetLoss}%)"
                )
                Top10Category.STREAMING -> Pair(
                    "%.1f Mbps Throughput".format(profile.downloadMbps),
                    "Buffer Stability: %.0f%%".format(profile.stability)
                )
                Top10Category.BALANCED -> Pair(
                    "Score: %.1f".format(profile.overallScore),
                    "${profile.lastLatencyMs ?: 0} ms • %.1f Mbps".format(profile.downloadMbps)
                )
            }

            RankedNode(
                rank = rank,
                profile = profile,
                primaryMetric = primary,
                secondaryMetric = secondary,
                scoreBadge = "%.1f".format(profile.overallScore),
                rankMedal = medal
            )
        }
    }
}
