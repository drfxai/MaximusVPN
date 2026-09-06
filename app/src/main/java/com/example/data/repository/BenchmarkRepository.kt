package com.example.data.repository

import com.example.data.database.BenchmarkDao
import com.example.data.database.BenchmarkHistoryEntity
import com.example.data.model.BenchmarkStageResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BenchmarkRepository(private val dao: BenchmarkDao) {

    val allRuns: Flow<List<BenchmarkStageResult>> = dao.getAllRuns().map { list ->
        list.map { it.toDomain() }
    }

    fun getRunsForServer(serverId: String): Flow<List<BenchmarkStageResult>> {
        return dao.getRunsForServer(serverId).map { list -> list.map { it.toDomain() } }
    }

    suspend fun getRecentRunsForServer(serverId: String): List<BenchmarkStageResult> {
        return dao.getRecentRunsForServer(serverId).map { it.toDomain() }
    }

    suspend fun recordRun(result: BenchmarkStageResult) {
        dao.insertRun(BenchmarkHistoryEntity.fromDomain(result))
    }

    suspend fun deleteForServer(serverId: String) {
        dao.deleteForServer(serverId)
    }

    suspend fun deleteAll() {
        dao.deleteAll()
    }
}
