package com.example.data.repository

import com.example.data.database.SubscriptionDao
import com.example.data.database.SubscriptionEntity
import com.example.data.model.SubscriptionInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SubscriptionRepository(private val dao: SubscriptionDao) {

    val allSubscriptions: Flow<List<SubscriptionInfo>> = dao.getAllSubscriptions().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun getSubscriptionById(id: String): SubscriptionInfo? {
        return dao.getSubscriptionById(id)?.toDomain()
    }

    suspend fun getSubscriptionByUrl(url: String): SubscriptionInfo? {
        return dao.getAllSubscriptionsOnce().asSequence().map { it.toDomain() }.firstOrNull { it.url == url }
    }

    /** Rewrites legacy plaintext subscription URLs using the current encrypted representation. */
    suspend fun migrateSensitiveUrls() {
        dao.getAllSubscriptionsOnce()
            .filter { it.url.startsWith("https://", ignoreCase = true) }
            .forEach { entity ->
                val domain = entity.toDomain()
                if (domain.url.isNotBlank()) {
                    dao.insert(SubscriptionEntity.fromDomain(domain, entity.etag, entity.lastModified))
                }
            }
    }

    suspend fun insertOrUpdate(subscription: SubscriptionInfo, etag: String? = null, lastModified: String? = null) {
        dao.insert(SubscriptionEntity.fromDomain(subscription, etag, lastModified))
    }

    suspend fun updateSyncStatus(
        id: String,
        nodeCount: Int,
        error: String? = null,
        etag: String? = null,
        lastModified: String? = null
    ) {
        dao.updateSyncStatus(
            id = id,
            timestamp = System.currentTimeMillis(),
            count = nodeCount,
            error = error,
            etag = etag,
            lastModified = lastModified
        )
    }

    suspend fun delete(id: String) {
        dao.deleteById(id)
    }

    suspend fun deleteAll() {
        dao.deleteAll()
    }
}
