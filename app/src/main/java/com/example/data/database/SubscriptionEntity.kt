package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.SubscriptionInfo
import com.example.data.security.SecureStorage

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val lastUpdated: Long = 0L,
    val autoRefresh: Boolean = true,
    val refreshIntervalMinutes: Int = 1440,
    val nodeCount: Int = 0,
    val lastError: String? = null,
    val etag: String? = null,
    val lastModified: String? = null
) {
    fun toDomain(): SubscriptionInfo = SubscriptionInfo(
        id = id,
        name = name,
        url = SecureStorage.decrypt(url).ifBlank {
            // Backward compatibility for databases written before URLs were encrypted.
            if (url.startsWith("https://", ignoreCase = true)) url else ""
        },
        lastUpdated = lastUpdated,
        autoRefresh = autoRefresh,
        refreshIntervalMinutes = refreshIntervalMinutes,
        nodeCount = nodeCount,
        lastError = lastError
    )

    companion object {
        fun fromDomain(sub: SubscriptionInfo, etag: String? = null, lastModified: String? = null): SubscriptionEntity =
            SubscriptionEntity(
                id = sub.id,
                name = sub.name,
                url = if (sub.url.isNotEmpty()) SecureStorage.encrypt(sub.url) else "",
                lastUpdated = sub.lastUpdated,
                autoRefresh = sub.autoRefresh,
                refreshIntervalMinutes = sub.refreshIntervalMinutes,
                nodeCount = sub.nodeCount,
                lastError = sub.lastError,
                etag = etag,
                lastModified = lastModified
            )
    }
}
