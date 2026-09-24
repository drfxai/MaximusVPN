package com.example.vpn.benchmark

import com.example.data.model.BenchmarkMode
import com.example.data.model.BenchmarkProgress
import com.example.data.model.BenchmarkStageResult
import com.example.data.model.ScoringProfile
import com.example.data.model.ServerCategory
import com.example.data.model.VlessProfile
import com.example.data.repository.BenchmarkRepository
import com.example.data.repository.ServerRepository
import com.example.vpn.scoring.ScoringEngine
import com.example.xray.XrayLogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BenchmarkEngine(
    private val serverRepository: ServerRepository,
    private val benchmarkRepository: BenchmarkRepository
) {
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var benchmarkJob: Job? = null

    private val _progressFlow = MutableStateFlow(BenchmarkProgress())
    val progressFlow: StateFlow<BenchmarkProgress> = _progressFlow.asStateFlow()

    private val _lastResults = MutableStateFlow<List<BenchmarkStageResult>>(emptyList())
    val lastResults: StateFlow<List<BenchmarkStageResult>> = _lastResults.asStateFlow()

    @Volatile
    private var isPaused = false

    /**
     * Starts benchmarking a list of profiles with controlled concurrency and real network execution.
     */
    fun startBenchmark(
        profiles: List<VlessProfile>,
        mode: BenchmarkMode = BenchmarkMode.BALANCED,
        scoringProfile: ScoringProfile = ScoringProfile.BALANCED,
        onProgressUpdate: ((BenchmarkProgress) -> Unit)? = null
    ) {
        cancelBenchmark()
        isPaused = false

        if (profiles.isEmpty()) return

        val total = profiles.size
        _progressFlow.value = BenchmarkProgress(
            total = total,
            completed = 0,
            failed = 0,
            isRunning = true,
            isPaused = false,
            estimatedBytes = 0, // Throughput is not measured by transport probes
            mode = mode
        )

        benchmarkJob = engineScope.launch {
            val queueChannel = Channel<VlessProfile>(Channel.UNLIMITED)
            for (p in profiles) {
                queueChannel.send(p)
            }
            queueChannel.close()

            val concurrency = mode.concurrency.coerceIn(2, 8)
            var completedCount = 0
            var failedCount = 0
            val lock = Any()

            val workers = (1..concurrency).map { workerId ->
                launch {
                    for (profile in queueChannel) {
                        while (isPaused && isActive) {
                            delay(300)
                        }
                        if (!isActive) break

                        synchronized(lock) {
                            _progressFlow.value = _progressFlow.value.copy(
                                currentServerName = profile.name,
                                activeWorkers = concurrency
                            )
                        }

                        val result = executeSingleBenchmark(profile, mode, scoringProfile)

                        synchronized(lock) {
                            completedCount++
                            if (!result.isSuccess) {
                                failedCount++
                            }
                            _lastResults.value = listOf(result) + _lastResults.value.take(49)

                            val updatedProgress = _progressFlow.value.copy(
                                completed = completedCount,
                                failed = failedCount,
                                isRunning = completedCount < total
                            )
                            _progressFlow.value = updatedProgress
                            onProgressUpdate?.invoke(updatedProgress)
                        }

                        // Persist to database
                        try {
                            serverRepository.updateBenchmarkResult(result)
                            benchmarkRepository.recordRun(result)
                        } catch (e: Exception) {
                            XrayLogManager.w("BENCHMARK", "Failed to update node test result: ${e.message}")
                        }
                    }
                }
            }

            workers.forEach { it.join() }

            _progressFlow.value = _progressFlow.value.copy(
                isRunning = false,
                isPaused = false,
                currentServerName = "Benchmark Finished"
            )
            XrayLogManager.i("BENCHMARK", "Benchmark completed: $completedCount tested, $failedCount failed.")
        }
    }

    fun pauseBenchmark() {
        isPaused = true
        _progressFlow.value = _progressFlow.value.copy(isPaused = true)
    }

    fun resumeBenchmark() {
        isPaused = false
        _progressFlow.value = _progressFlow.value.copy(isPaused = false)
    }

    fun cancelBenchmark() {
        benchmarkJob?.cancel()
        benchmarkJob = null
        isPaused = false
        _progressFlow.value = _progressFlow.value.copy(isRunning = false, isPaused = false)
    }

    /**
     * Executes real multi-stage testing against a single node.
     */
    suspend fun executeSingleBenchmark(
        profile: VlessProfile,
        mode: BenchmarkMode,
        scoringProfile: ScoringProfile
    ): BenchmarkStageResult = withContext(Dispatchers.IO) {
        measureTransportBenchmark(profile, mode, scoringProfile)
    }
}

/** Endpoint transport measurements only: no direct downloads or invented throughput. */
internal suspend fun measureTransportBenchmark(
    profile: VlessProfile,
    mode: BenchmarkMode,
    scoringProfile: ScoringProfile,
    probe: suspend (VlessProfile) -> com.example.data.model.ServerTestResult = { com.example.vpn.ServerTester.testServer(it) }
): BenchmarkStageResult {
    val attempts = when (mode) {
        BenchmarkMode.QUICK -> 3
        BenchmarkMode.BALANCED -> 5
        BenchmarkMode.DEEP -> 8
    }
    val samples = mutableListOf<Long>()
    var lastError = "No successful transport probes"
    repeat(attempts) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val result = try {
            probe(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            com.example.data.model.ServerTestResult(profile.id,
                com.example.data.model.ServerTestStatus.Unavailable(e.message ?: "Transport probe failed"))
        }
        when (val status = result.status) {
            is com.example.data.model.ServerTestStatus.Available -> samples.add(status.latencyMs)
            is com.example.data.model.ServerTestStatus.Slow -> samples.add(status.latencyMs)
            is com.example.data.model.ServerTestStatus.Unavailable -> lastError = status.reason
            is com.example.data.model.ServerTestStatus.InvalidConfig -> lastError = status.error
            else -> lastError = "Transport probe did not complete"
        }
        if (it < attempts - 1) delay(40)
    }
    if (samples.isEmpty()) return BenchmarkStageResult(
        serverId = profile.id, isSuccess = false, errorMessage = lastError,
        packetLossPercent = 100.0, successRatePercent = 0.0, stabilityPercent = 0.0,
        category = ServerCategory.OFFLINE
    )
    val ping = samples.average().toLong().coerceAtLeast(1)
    val jitter = samples.zipWithNext { a, b -> kotlin.math.abs(a - b) }.takeIf { it.isNotEmpty() }?.average()?.toLong() ?: 0L
    val successRate = samples.size * 100.0 / attempts
    val loss = 100.0 - successRate
    val stability = (100.0 - loss * 1.5 - jitter * 0.25).coerceIn(0.0, 100.0)
    val scores = ScoringEngine.calculateScores(0.0, ping, jitter, loss, stability, successRate, scoringProfile)
    return BenchmarkStageResult(
        serverId = profile.id, pingMs = ping, jitterMs = jitter,
        packetLossPercent = loss, successRatePercent = successRate, stabilityPercent = stability,
        speedScore = 0.0, latencyScore = scores.latencyScore, stabilityScore = scores.stabilityScore,
        reliabilityScore = scores.reliabilityScore, overallScore = scores.overallScore,
        category = ServerCategory.fromScoreAndHealth(scores.overallScore, ping, loss, stability),
        isSuccess = true
    )
}
