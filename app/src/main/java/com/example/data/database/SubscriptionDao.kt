package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {

    @Query("SELECT * FROM subscriptions ORDER BY lastUpdated DESC")
    fun getAllSubscriptions(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY lastUpdated DESC")
    suspend fun getAllSubscriptionsOnce(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id LIMIT 1")
    suspend fun getSubscriptionById(id: String): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SubscriptionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<SubscriptionEntity>)

    @Update
    suspend fun update(entity: SubscriptionEntity)

    @Query("UPDATE subscriptions SET lastUpdated = :timestamp, nodeCount = :count, lastError = :error, etag = :etag, lastModified = :lastModified WHERE id = :id")
    suspend fun updateSyncStatus(id: String, timestamp: Long, count: Int, error: String?, etag: String?, lastModified: String?)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM subscriptions")
    suspend fun deleteAll()
}
