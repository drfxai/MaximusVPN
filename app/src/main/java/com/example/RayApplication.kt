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

        // Clean up legacy non-functional seed nodes and initialize profile state
        CoroutineScope(Dispatchers.IO).launch {
            try {
                subscriptionRepository.migrateSensitiveUrls()
                serverRepository.migrateSensitiveSubscriptionSources()
                serverRepository.delete("seed-vless-ws-1")
                serverRepository.delete("seed-vless-reality-1")

                val currentSelected = settingsRepository.getSettings().selectedProfileId
                if (currentSelected == "seed-vless-ws-1" || currentSelected == "seed-vless-reality-1") {
                    val firstValid = serverRepository.getAllProfilesOnce().firstOrNull()
                    settingsRepository.setSelectedProfileId(firstValid?.id)
                }
            } catch (e: Exception) {
                XrayLogManager.w("APP", "Profile startup cleanup notice: ${e.message}")
            }
        }
    }

    companion object {
        lateinit var instance: RayApplication
            private set
    }
}
