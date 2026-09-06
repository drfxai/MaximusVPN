package com.example.ui.benchmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.BenchmarkMode
import com.example.data.model.BenchmarkProgress
import com.example.data.model.BenchmarkStageResult
import com.example.data.model.ScoringProfile
import com.example.data.model.VlessProfile
import com.example.data.repository.BenchmarkRepository
import com.example.data.repository.ServerRepository
import com.example.vpn.benchmark.BenchmarkEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BenchmarkUiState(
    val selectedMode: BenchmarkMode = BenchmarkMode.BALANCED,
    val testOnlyUntested: Boolean = false,
    val progress: BenchmarkProgress = BenchmarkProgress(),
    val totalNodesAvailable: Int = 0,
    val recentResults: List<BenchmarkStageResult> = emptyList()
)

class BenchmarkViewModel(
    private val serverRepository: ServerRepository,
    private val benchmarkRepository: BenchmarkRepository,
    private val benchmarkEngine: BenchmarkEngine
) : ViewModel() {

    private val _selectedMode = MutableStateFlow(BenchmarkMode.BALANCED)
    val selectedMode: StateFlow<BenchmarkMode> = _selectedMode.asStateFlow()

    private val _testOnlyUntested = MutableStateFlow(false)
    val testOnlyUntested: StateFlow<Boolean> = _testOnlyUntested.asStateFlow()

    val progress: StateFlow<BenchmarkProgress> = benchmarkEngine.progressFlow

    val uiState: StateFlow<BenchmarkUiState> = combine(
        _selectedMode,
        _testOnlyUntested,
        benchmarkEngine.progressFlow,
        serverRepository.allProfiles,
        benchmarkEngine.lastResults
    ) { mode, onlyUntested, prog, profiles, results ->
        BenchmarkUiState(
            selectedMode = mode,
            testOnlyUntested = onlyUntested,
            progress = prog,
            totalNodesAvailable = profiles.size,
            recentResults = results
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BenchmarkUiState()
    )

    fun selectMode(mode: BenchmarkMode) {
        _selectedMode.value = mode
    }

    fun setTestOnlyUntested(value: Boolean) {
        _testOnlyUntested.value = value
    }

    fun startBenchmark(scoringProfile: ScoringProfile = ScoringProfile.BALANCED) {
        viewModelScope.launch {
            val allNodes = try {
                serverRepository.allProfiles.first()
            } catch (_: Exception) {
                emptyList()
            }
            val targetNodes = if (_testOnlyUntested.value) {
                allNodes.filter { it.lastTestedTimestamp == null || it.lastLatencyMs == null }
            } else {
                allNodes
            }

            benchmarkEngine.startBenchmark(
                profiles = targetNodes,
                mode = _selectedMode.value,
                scoringProfile = scoringProfile
            )
        }
    }

    fun pauseBenchmark() {
        benchmarkEngine.pauseBenchmark()
    }

    fun resumeBenchmark() {
        benchmarkEngine.resumeBenchmark()
    }

    fun cancelBenchmark() {
        benchmarkEngine.cancelBenchmark()
    }

    fun retryFailed(scoringProfile: ScoringProfile = ScoringProfile.BALANCED) {
        viewModelScope.launch {
            val allNodes = try {
                serverRepository.allProfiles.first()
            } catch (_: Exception) {
                emptyList()
            }
            val failedNodes = allNodes.filter { it.lastLatencyMs == null || it.lastLatencyMs == 0L || it.packetLoss >= 50.0 }
            benchmarkEngine.startBenchmark(
                profiles = failedNodes,
                mode = _selectedMode.value,
                scoringProfile = scoringProfile
            )
        }
    }
}
