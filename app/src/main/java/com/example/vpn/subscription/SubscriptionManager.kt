package com.example.vpn.subscription

import com.example.core.SecretRedactor
import com.example.data.model.SubscriptionInfo
import com.example.data.repository.ServerRepository
import com.example.data.repository.SubscriptionRepository
import com.example.vpn.engine.UniversalImportEngine
import com.example.vpn.routing.RoutingEngine
import com.example.xray.XrayLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit

class SubscriptionManager(
    private val subscriptionRepository: SubscriptionRepository,
    private val serverRepository: ServerRepository
) {
    companion object {
        fun isValidSubscriptionUrl(url: String): Boolean {
            val trimmed = url.trim()
            return try {
                val uri = URI(trimmed)
                val scheme = uri.scheme?.lowercase() ?: return false
                // Strict HTTPS only
                if (scheme != "https") return false
                val host = uri.host ?: return false
                if (isBlockedHost(host)) return false
                // Pre-resolve host to mitigate DNS rebinding prior to request
                try {
                    val addresses = java.net.InetAddress.getAllByName(host)
                    if (addresses.isEmpty()) return false
                    for (addr in addresses) {
                        if (isRestrictedIp(addr)) return false
                    }
                } catch (_: Exception) {
                    return false
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        fun isRestrictedIp(inetAddress: java.net.InetAddress): Boolean {
            if (inetAddress.isLoopbackAddress) return true
            if (inetAddress.isAnyLocalAddress) return true
            if (inetAddress.isLinkLocalAddress) return true
            if (inetAddress.isSiteLocalAddress) return true
            if (inetAddress.isMulticastAddress) return true

            val raw = inetAddress.address
            if (raw.size == 4) {
                val b0 = raw[0].toInt() and 0xFF
                val b1 = raw[1].toInt() and 0xFF
                // 100.64.0.0/10 Carrier Grade NAT
                if (b0 == 100 && (b1 in 64..127)) return true
                // 169.254.0.0/16 Link-local / Cloud metadata (169.254.169.254)
                if (b0 == 169 && b1 == 254) return true
                // 198.18.0.0/15
                if (b0 == 198 && (b1 in 18..19)) return true
                // 0.0.0.0/8
                if (b0 == 0) return true
            } else if (raw.size == 16) {
                val b0 = raw[0].toInt() and 0xFF
                // Unique Local Address fc00::/7 (fc00... or fd00...)
                if ((b0 and 0xFE) == 0xFC) return true
                // Link-local fe80::/10
                if (b0 == 0xFE && ((raw[1].toInt() and 0xC0) == 0x80)) return true
                // IPv4-mapped IPv6 ::ffff:a.b.c.d
                if (raw.sliceArray(0..9).all { it == 0.toByte() } && raw[10] == 0xFF.toByte() && raw[11] == 0xFF.toByte()) {
                    val v4 = raw.sliceArray(12..15)
                    val v4Addr = java.net.InetAddress.getByAddress(v4)
                    return isRestrictedIp(v4Addr)
                }
            }
            return false
        }

        fun isBlockedHost(host: String): Boolean {
            val lower = host.trim().lowercase()
            if (lower == "localhost" || lower == "127.0.0.1" || lower == "::1" || lower == "0.0.0.0" || lower == "::") return true
            if (lower == "169.254.169.254" || lower.startsWith("169.254.")) return true // AWS/Cloud metadata & link-local
            if (lower == "100.100.100.200") return true // Alibaba cloud metadata
            if (lower == "metadata.google.internal") return true // GCP metadata

            val parsedIp = RoutingEngine.parseIpv4(lower)
            if (parsedIp != null && (RoutingEngine.isLanAddress(parsedIp) || RoutingEngine.isLoopbackOrBroadcast(parsedIp))) {
                return true
            }
            return false
        }
    }

    private val safeDns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            if (isBlockedHost(hostname)) {
                throw SecurityException("SSRF blocked: Hostname '$hostname' is in restricted host list.")
            }
            val addresses = okhttp3.Dns.SYSTEM.lookup(hostname)
            for (addr in addresses) {
                if (isRestrictedIp(addr)) {
                    throw SecurityException("SSRF DNS Rebinding blocked: Hostname '$hostname' resolved to restricted IP: $addr")
                }
            }
            return addresses
        }
    }

    private val redirectValidationInterceptor = Interceptor { chain ->
        val request = chain.request()
        val uri = request.url.toUri()
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https") {
            throw SecurityException("SSRF blocked: Subscriptions must strictly use HTTPS.")
        }
        val host = uri.host ?: throw IllegalArgumentException("Missing host in subscription URL")
        if (isBlockedHost(host)) {
            throw SecurityException("SSRF blocked: Subscription target is a restricted local or metadata host: $host")
        }
        val response = chain.proceed(request)
        if (response.isRedirect) {
            val location = response.header("Location")
            if (location != null && !isValidSubscriptionUrl(location)) {
                throw SecurityException("SSRF blocked: Redirected to disallowed destination: $location")
            }
        }
        response
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .dns(safeDns)
        .addInterceptor(redirectValidationInterceptor)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class SyncResult(
        val subscriptionId: String,
        val totalFound: Int,
        val addedCount: Int,
        val duplicateCount: Int,
        val isSuccess: Boolean,
        val errorMessage: String? = null
    )

    /**
     * Synchronizes a subscription URL securely with size limits and ETag support.
     */
    suspend fun syncSubscription(subscription: SubscriptionInfo): SyncResult = withContext(Dispatchers.IO) {
        val sanitizedUrl = SecretRedactor.redact(subscription.url)
        XrayLogManager.i("SUBSCRIPTION", "Syncing subscription '${subscription.name}' from $sanitizedUrl...")

        if (!isValidSubscriptionUrl(subscription.url)) {
            val errMsg = "Invalid or restricted subscription URL scheme/host."
            XrayLogManager.e("SUBSCRIPTION", "Sync failed for '${subscription.name}': $errMsg")
            subscriptionRepository.updateSyncStatus(subscription.id, subscription.nodeCount, errMsg)
            return@withContext SyncResult(subscription.id, 0, 0, 0, false, errMsg)
        }

        val requestBuilder = Request.Builder()
            .url(subscription.url)
            .header("User-Agent", "Maximus-VPN/1.0 (Android; Linux)")
            .header("Accept", "*/*")

        try {
            val response = client.newCall(requestBuilder.build()).execute()
            response.use { res ->
                if (!res.isSuccessful) {
                    val errMsg = "HTTP ${res.code}: ${res.message}"
                    XrayLogManager.e("SUBSCRIPTION", "Sync failed for '${subscription.name}': $errMsg")
                    subscriptionRepository.updateSyncStatus(subscription.id, subscription.nodeCount, errMsg)
                    return@withContext SyncResult(
                        subscriptionId = subscription.id,
                        totalFound = 0,
                        addedCount = 0,
                        duplicateCount = 0,
                        isSuccess = false,
                        errorMessage = errMsg
                    )
                }

                val etag = res.header("ETag")
                val lastModified = res.header("Last-Modified")

                val body = res.body ?: run {
                    val errMsg = "Empty response body received from subscription server."
                    subscriptionRepository.updateSyncStatus(subscription.id, subscription.nodeCount, errMsg)
                    return@withContext SyncResult(subscription.id, 0, 0, 0, false, errMsg)
                }

                // Protect mobile users with strict 5MB response limit
                val maxBytes = 5 * 1024 * 1024 // 5 MB
                val inputStream = body.byteStream()
                val buffer = ByteArray(8192)
                val out = ByteArrayOutputStream()
                var totalBytesRead = 0

                while (true) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    totalBytesRead += read
                    if (totalBytesRead > maxBytes) {
                        val errMsg = "Subscription payload exceeded safety limit (5MB)."
                        subscriptionRepository.updateSyncStatus(subscription.id, subscription.nodeCount, errMsg)
                        return@withContext SyncResult(subscription.id, 0, 0, 0, false, errMsg)
                    }
                    out.write(buffer, 0, read)
                }

                val rawPayload = out.toString(Charsets.UTF_8.name())
                val importResult = UniversalImportEngine.importText(
                    rawText = rawPayload,
                    sourceFileName = subscription.name,
                    sourceSubscriptionUrl = subscription.url
                )

                // Deduplicate and insert into database
                val (inserted, duplicates) = serverRepository.insertAllWithDeduplication(importResult.validProfiles)

                val newNodeCount = inserted.size
                subscriptionRepository.updateSyncStatus(
                    id = subscription.id,
                    nodeCount = newNodeCount,
                    error = null,
                    etag = etag,
                    lastModified = lastModified
                )

                XrayLogManager.i(
                    "SUBSCRIPTION",
                    "Subscription '${subscription.name}' sync complete: ${importResult.configurationsFound} found, ${inserted.size} added, ${duplicates.size} duplicates."
                )

                SyncResult(
                    subscriptionId = subscription.id,
                    totalFound = importResult.configurationsFound,
                    addedCount = inserted.size,
                    duplicateCount = duplicates.size,
                    isSuccess = true,
                    errorMessage = null
                )
            }
        } catch (e: Exception) {
            val errMsg = e.localizedMessage ?: "Network connection failure"
            XrayLogManager.e("SUBSCRIPTION", "Error syncing '${subscription.name}': $errMsg")
            subscriptionRepository.updateSyncStatus(subscription.id, subscription.nodeCount, errMsg)
            SyncResult(subscription.id, 0, 0, 0, false, errMsg)
        }
    }

    /**
     * Adds a new subscription and immediately triggers initial sync.
     */
    suspend fun addAndSyncSubscription(name: String, url: String): SyncResult {
        val trimmedUrl = url.trim()
        if (!isValidSubscriptionUrl(trimmedUrl)) {
            return SyncResult("", 0, 0, 0, false, "Invalid subscription URL")
        }
        val sub = SubscriptionInfo(
            name = name.ifBlank { "Subscription" },
            url = trimmedUrl,
            autoRefresh = true,
            lastUpdated = 0L,
            nodeCount = 0
        )
        subscriptionRepository.insertOrUpdate(sub)
        return syncSubscription(sub)
    }

    suspend fun deleteSubscriptionAndNodes(subscription: SubscriptionInfo) {
        serverRepository.deleteBySubscription(subscription.url)
        subscriptionRepository.delete(subscription.id)
        XrayLogManager.i("SUBSCRIPTION", "Deleted subscription '${subscription.name}' and its associated nodes.")
    }
}
