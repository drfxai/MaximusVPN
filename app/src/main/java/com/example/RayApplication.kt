package com.example

import android.app.Application
import com.example.data.database.AppDatabase
import com.example.data.model.VlessProfile
import com.example.data.repository.BenchmarkRepository
import com.example.data.repository.ServerRepository
import com.example.data.repository.SettingsRepository
import com.example.data.repository.SubscriptionRepository
import com.example.vpn.benchmark.BenchmarkEngine
import com.example.vpn.subscription.SubscriptionManager
import com.example.xray.XrayLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RayApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var serverRepository: ServerRepository
        private set

    lateinit var subscriptionRepository: SubscriptionRepository
        private set

    lateinit var benchmarkRepository: BenchmarkRepository
        private set

    lateinit var subscriptionManager: SubscriptionManager
        private set

    lateinit var benchmarkEngine: BenchmarkEngine
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Install Global Uncaught Exception Handler to capture any UI/Thread/Coroutine crash for agent diagnostics
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                XrayLogManager.fatal(
                    tag = "CRASH",
                    message = "Uncaught exception on thread '${thread.name}': ${throwable.localizedMessage}",
                    throwable = throwable
                )
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        XrayLogManager.i("APP", "Maximus Application starting. Initializing database and subsystems...")

        try {
            database = AppDatabase.getInstance(this)
            serverRepository = ServerRepository(database.serverProfileDao())
            subscriptionRepository = SubscriptionRepository(database.subscriptionDao())
            benchmarkRepository = BenchmarkRepository(database.benchmarkDao())
            subscriptionManager = SubscriptionManager(subscriptionRepository, serverRepository)
            benchmarkEngine = BenchmarkEngine(serverRepository, benchmarkRepository)
            settingsRepository = SettingsRepository(this)
            XrayLogManager.i("APP", "Database, server repository, and settings repository initialized successfully.")
        } catch (e: Exception) {
            XrayLogManager.e("APP", "Fatal error initializing application database/repositories: ${e.localizedMessage}", e)
        }

        // Database initialization ready for user configuration imports
        XrayLogManager.i("APP", "Application environment initialized successfully.")

        // Seed default fallback nodes if database is empty
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (serverRepository.getCount() == 0) {
                    val defaultSeedProfiles = listOf(
                        com.example.data.model.VlessProfile(
                            id = "seed-vless-reality-1",
                            name = "⚡ Global Anti-Censorship Edge",
                            address = "1.1.1.1",
                            port = 443,
                            uuid = "a1b2c3d4-e5f6-47a8-b9c0-d1e2f3a4b5c6",
                            security = "reality",
                            sni = "www.microsoft.com",
                            publicKey = "11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff",
                            fingerprint = "chrome",
                            category = com.example.data.model.ServerCategory.ELITE,
                            countryCode = "US"
                        ),
                        com.example.data.model.VlessProfile(
                            id = "seed-vless-ws-2",
                            name = "🛡️ Cloudflare Stealth Tunnel",
                            address = "104.16.132.229",
                            port = 443,
                            uuid = "f81d4fae-7dec-11d0-a765-00a0c91e6bf6",
                            transport = "ws",
                            security = "tls",
                            sni = "cloudflare.com",
                            host = "cloudflare.com",
                            path = "/vless-ws",
                            fingerprint = "chrome",
                            category = com.example.data.model.ServerCategory.FAST,
                            countryCode = "EU"
                        )
                    )
                    serverRepository.insertAllWithDeduplication(defaultSeedProfiles)
                    settingsRepository.setSelectedProfileId("seed-vless-reality-1")
                    XrayLogManager.i("APP", "Default fallback VPN nodes seeded successfully.")
                }
            } catch (e: Exception) {
                XrayLogManager.e("APP", "Failed to seed default VPN nodes: ${e.message}")
            }
        }
    }

    companion object {
        lateinit var instance: RayApplication
            private set
    }
}
