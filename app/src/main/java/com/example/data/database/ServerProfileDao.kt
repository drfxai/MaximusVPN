package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerProfileDao {

    @Query("SELECT * FROM server_profiles ORDER BY overallScore DESC, lastLatencyMs ASC")
    fun getAllProfiles(): Flow<List<ServerProfileEntity>>

    @Query("SELECT * FROM server_profiles ORDER BY overallScore DESC, lastLatencyMs ASC")
    suspend fun getAllProfilesOnce(): List<ServerProfileEntity>

    @Query("SELECT * FROM server_profiles WHERE isFavorite = 1 ORDER BY overallScore DESC")
    fun getFavoriteProfiles(): Flow<List<ServerProfileEntity>>

    @Query("SELECT * FROM server_profiles WHERE id = :id LIMIT 1")
    suspend fun getProfileById(id: String): ServerProfileEntity?

    @Query("SELECT * FROM server_profiles WHERE canonicalFingerprint = :fingerprint LIMIT 1")
    suspend fun getProfileByFingerprint(fingerprint: String): ServerProfileEntity?

    @Query("SELECT * FROM server_profiles WHERE canonicalFingerprint IN (:fingerprints)")
    suspend fun getProfilesByFingerprints(fingerprints: List<String>): List<ServerProfileEntity>

    @Query("SELECT COUNT(*) FROM server_profiles")
    suspend fun getCount(): Int

    @Query("SELECT * FROM server_profiles WHERE overallScore > 0 ORDER BY overallScore DESC LIMIT 10")
    fun getTop10Overall(): Flow<List<ServerProfileEntity>>

    @Query("SELECT * FROM server_profiles WHERE downloadMbps > 0 ORDER BY downloadMbps DESC LIMIT 10")
    fun getTop10Fastest(): Flow<List<ServerProfileEntity>>

    @Query("SELECT * FROM server_profiles WHERE lastLatencyMs IS NOT NULL AND lastLatencyMs > 0 ORDER BY lastLatencyMs ASC LIMIT 10")
    fun getTop10LowestPing(): Flow<List<ServerProfileEntity>>

    @Query("SELECT * FROM server_profiles WHERE stability > 0 ORDER BY stability DESC, jitterMs ASC LIMIT 10")
    fun getTop10MostStable(): Flow<List<ServerProfileEntity>>

    @Query("SELECT * FROM server_profiles WHERE lastLatencyMs IS NOT NULL ORDER BY packetLoss ASC, overallScore DESC LIMIT 10")
    fun getTop10LowestPacketLoss(): Flow<List<ServerProfileEntity>>

    @Query("SELECT * FROM server_profiles WHERE category = :category ORDER BY overallScore DESC")
    fun getProfilesByCategory(category: String): Flow<List<ServerProfileEntity>>

    @Query("SELECT * FROM server_profiles WHERE subscriptionUrl = :url OR sourceSubscription = :url ORDER BY overallScore DESC")
    fun getProfilesBySubscription(url: String): Flow<List<ServerProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ServerProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ServerProfileEntity>)

    @Update
    suspend fun update(entity: ServerProfileEntity)

    @Query("""
        UPDATE server_profiles SET 
            lastLatencyMs = :latencyMs, 
            downloadMbps = :downloadMbps,
            uploadMbps = :uploadMbps,
            jitterMs = :jitterMs,
            packetLoss = :packetLoss,
            handshakeMs = :handshakeMs,
            stability = :stability,
            successRate = :successRate,
            speedScore = :speedScore,
            latencyScore = :latencyScore,
            stabilityScore = :stabilityScore,
            reliabilityScore = :reliabilityScore,
            overallScore = :overallScore,
            category = :category,
            lastTestedTimestamp = :timestamp 
        WHERE id = :id
    """)
    suspend fun updateBenchmarkMetrics(
        id: String,
        latencyMs: Long?,
        downloadMbps: Double,
        uploadMbps: Double,
        jitterMs: Long,
        packetLoss: Double,
        handshakeMs: Long,
        stability: Double,
        successRate: Double,
        speedScore: Double,
        latencyScore: Double,
        stabilityScore: Double,
        reliabilityScore: Double,
        overallScore: Double,
        category: String,
        timestamp: Long
    )

    @Query("UPDATE server_profiles SET lastLatencyMs = :latencyMs, lastTestedTimestamp = :timestamp WHERE id = :id")
    suspend fun updateLatency(id: String, latencyMs: Long?, timestamp: Long)

    @Query("UPDATE server_profiles SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavorite(id: String, isFavorite: Boolean)

    @Query("DELETE FROM server_profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM server_profiles WHERE subscriptionUrl = :url OR sourceSubscription = :url")
    suspend fun deleteBySubscription(url: String)

    @Query("DELETE FROM server_profiles")
    suspend fun deleteAll()
}
