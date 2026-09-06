package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BenchmarkDao {

    @Query("SELECT * FROM benchmark_runs ORDER BY testedAt DESC")
    fun getAllRuns(): Flow<List<BenchmarkHistoryEntity>>

    @Query("SELECT * FROM benchmark_runs WHERE serverId = :serverId ORDER BY testedAt DESC LIMIT 20")
    fun getRunsForServer(serverId: String): Flow<List<BenchmarkHistoryEntity>>

    @Query("SELECT * FROM benchmark_runs WHERE serverId = :serverId ORDER BY testedAt DESC LIMIT 10")
    suspend fun getRecentRunsForServer(serverId: String): List<BenchmarkHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(entity: BenchmarkHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRuns(entities: List<BenchmarkHistoryEntity>)

    @Query("DELETE FROM benchmark_runs WHERE serverId = :serverId")
    suspend fun deleteForServer(serverId: String)

    @Query("DELETE FROM benchmark_runs")
    suspend fun deleteAll()
}
