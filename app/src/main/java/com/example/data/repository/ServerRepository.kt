package com.example.data.repository

import com.example.data.database.ServerProfileDao
import com.example.data.database.ServerProfileEntity
import com.example.data.model.BenchmarkStageResult
import com.example.data.model.CanonicalFingerprint
import com.example.data.model.ServerCategory
import com.example.data.model.Top10Category
import com.example.data.model.VlessProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ServerRepository(private val dao: ServerProfileDao) {

    val allProfiles: Flow<List<VlessProfile>> = dao.getAllProfiles().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun getAllProfilesOnce(): List<VlessProfile> {
        return dao.getAllProfilesOnce().map { it.toDomain() }
    }

    val favoriteProfiles: Flow<List<VlessProfile>> = dao.getFavoriteProfiles().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun getProfileById(id: String): VlessProfile? {
        return dao.getProfileById(id)?.toDomain()
    }

    suspend fun getProfileByFingerprint(fingerprint: String): VlessProfile? {
        return dao.getProfileByFingerprint(fingerprint)?.toDomain()
    }

    suspend fun getCount(): Int {
        return dao.getCount()
    }

    fun getTop10(category: Top10Category): Flow<List<VlessProfile>> {
        val flow = when (category) {
            Top10Category.OVERALL, Top10Category.STREAMING, Top10Category.BALANCED -> dao.getTop10Overall()
            Top10Category.FASTEST -> dao.getTop10Fastest()
            Top10Category.LOWEST_PING, Top10Category.GAMING -> dao.getTop10LowestPing()
            Top10Category.MOST_STABLE -> dao.getTop10MostStable()
            Top10Category.LOWEST_PACKET_LOSS -> dao.getTop10LowestPacketLoss()
        }
        return flow.map { list -> list.map { it.toDomain() } }
    }

    fun getProfilesByCategory(category: ServerCategory): Flow<List<VlessProfile>> {
        return dao.getProfilesByCategory(category.name).map { list -> list.map { it.toDomain() } }
    }

    fun getProfilesBySubscription(url: String): Flow<List<VlessProfile>> {
        return dao.getProfilesBySubscription(url).map { list -> list.map { it.toDomain() } }
    }

    suspend fun insert(profile: VlessProfile) {
        val fp = if (profile.canonicalFingerprint.isNotBlank()) profile.canonicalFingerprint else CanonicalFingerprint.computeFromProfile(profile)
        val updated = profile.copy(canonicalFingerprint = fp)
        dao.insert(ServerProfileEntity.fromDomain(updated))
    }

    /**
     * Batch inserts profiles while performing automatic deterministic deduplication.
     * Returns a pair of (insertedCount, duplicateCount).
     */
    suspend fun insertAllWithDeduplication(profiles: List<VlessProfile>): Pair<List<VlessProfile>, List<VlessProfile>> {
        if (profiles.isEmpty()) return Pair(emptyList(), emptyList())

        val fingerprints = profiles.map {
            if (it.canonicalFingerprint.isNotBlank()) it.canonicalFingerprint else CanonicalFingerprint.computeFromProfile(it)
        }

        val existingEntities = dao.getProfilesByFingerprints(fingerprints)
        val existingFpSet = existingEntities.map { it.canonicalFingerprint }.toMutableSet()

        val uniqueToInsert = mutableListOf<VlessProfile>()
        val duplicates = mutableListOf<VlessProfile>()

        for (profile in profiles) {
            val fp = if (profile.canonicalFingerprint.isNotBlank()) profile.canonicalFingerprint else CanonicalFingerprint.computeFromProfile(profile)
            if (existingFpSet.contains(fp)) {
                duplicates.add(profile)
            } else {
                existingFpSet.add(fp)
                uniqueToInsert.add(profile.copy(canonicalFingerprint = fp))
            }
        }

        if (uniqueToInsert.isNotEmpty()) {
            dao.insertAll(uniqueToInsert.map { ServerProfileEntity.fromDomain(it) })
        }

        return Pair(uniqueToInsert, duplicates)
    }

    suspend fun update(profile: VlessProfile) {
        dao.update(ServerProfileEntity.fromDomain(profile))
    }

    suspend fun updateBenchmarkResult(result: BenchmarkStageResult) {
        dao.updateBenchmarkMetrics(
            id = result.serverId,
            latencyMs = if (result.isSuccess) result.pingMs else null,
            downloadMbps = result.downloadMbps,
            uploadMbps = result.uploadMbps,
            jitterMs = result.jitterMs,
            packetLoss = result.packetLossPercent,
            handshakeMs = result.proxyHandshakeMs.coerceAtLeast(result.tcpHandshakeMs),
            stability = result.stabilityPercent,
            successRate = result.successRatePercent,
            speedScore = result.speedScore,
            latencyScore = result.latencyScore,
            stabilityScore = result.stabilityScore,
            reliabilityScore = result.reliabilityScore,
            overallScore = result.overallScore,
            category = result.category.name,
            timestamp = result.testedAt
        )
    }

    suspend fun updateLatency(id: String, latencyMs: Long?) {
        dao.updateLatency(id, latencyMs, System.currentTimeMillis())
    }

    suspend fun toggleFavorite(id: String, isFavorite: Boolean) {
        dao.updateFavorite(id, isFavorite)
    }

    suspend fun delete(id: String) {
        dao.deleteById(id)
    }

    suspend fun deleteBySubscription(url: String) {
        dao.deleteBySubscription(url)
    }

    suspend fun deleteAll() {
        dao.deleteAll()
    }
}
