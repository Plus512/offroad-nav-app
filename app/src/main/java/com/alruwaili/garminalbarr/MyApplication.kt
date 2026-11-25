package com.alruwaili.garminalbarr

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import com.alruwaili.garminalbarr.billing.BillingClientWrapper
import com.alruwaili.garminalbarr.billing.SubscriptionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.util.Log
import org.osmdroid.mapsforge.MapsForgeTileSource

class MyApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var sharedLocationViewModel: SharedLocationViewModel

    lateinit var subscriptionManager: SubscriptionManager
        private set
    lateinit var billingClientWrapper: BillingClientWrapper
        private set

    override fun onCreate() {
        super.onCreate()

        // Bridge java.util.logging → android.util.Log so Mapsforge errors appear in logcat
        try {
            val rootLogger = java.util.logging.Logger.getLogger("")
            rootLogger.handlers.forEach { rootLogger.removeHandler(it) }
            rootLogger.addHandler(object : java.util.logging.Handler() {
                private val formatter = java.util.logging.SimpleFormatter()
                override fun publish(record: java.util.logging.LogRecord) {
                    val level = when {
                        record.level.intValue() >= java.util.logging.Level.SEVERE.intValue() -> android.util.Log.ERROR
                        record.level.intValue() >= java.util.logging.Level.WARNING.intValue() -> android.util.Log.WARN
                        else -> android.util.Log.DEBUG
                    }
                    android.util.Log.println(level, "mapsforge-jul", formatter.format(record))
                }
                override fun flush() {}
                override fun close() {}
            })
        } catch (_: Exception) {}

        // Mapsforge REQUIRES this before any rendering
        MapsForgeTileSource.createInstance(this)
        com.alruwaili.garminalbarr.analytics.AnalyticsHelper.init(this)
        sharedLocationViewModel = ViewModelProvider.AndroidViewModelFactory
            .getInstance(this)
            .create(SharedLocationViewModel::class.java)

        subscriptionManager = SubscriptionManager.getInstance(this)
        subscriptionManager.initialize()
        billingClientWrapper = BillingClientWrapper(this, subscriptionManager)
        billingClientWrapper.startConnection()

        // ── Initialize manualLocations from storage (prevents data loss on fresh install) ──
        val storage = MyLocationsStorage(this)
        try {
            val savedLocations = storage.load()
            sharedLocationViewModel.manualLocations.value = savedLocations.toMutableList()
            Log.d("MyApplication", "Initialized manualLocations from storage: ${savedLocations.size} locations")
        } catch (e: Exception) {
            Log.e("MyApplication", "Failed to load locations from storage, starting with empty list", e)
            sharedLocationViewModel.manualLocations.value = mutableListOf()
        }

        // ── My Locations Cloud Sync ──
        setupLocationsSync()

        // Pre-warm bundled tiles + hydrate global cache (restored P0-A)
        applicationScope.launch {
            try {
                if (LocationsCacheManager.isCacheAvailable(this@MyApplication)) {
                    val cachedList = LocationsCacheManager.readCache(this@MyApplication)
                    if (cachedList != null) {
                        sharedLocationViewModel.initializeGlobalCache(cachedList)
                        Log.d("MyApplication", "Global data hydrated from binary cache: ${cachedList.size} records")
                        return@launch
                    }
                }
                val locations = RoomDBHelper.getInstance(this@MyApplication)
                    .locationDao.getLightweightLocationsDirect()
                sharedLocationViewModel.initializeGlobalCache(locations)
                Log.d("MyApplication", "Global data hydrated from Room: ${locations.size} records")
                LocationsCacheManager.saveCache(this@MyApplication, locations)
                Log.d("MyApplication", "Global data saved to binary cache file successfully")
            } catch (e: Exception) {
                Log.e("MyApplication", "Hydration failed", e)
                sharedLocationViewModel.setCacheError(e)
            }
        }
    }

    private fun setupLocationsSync() {
        val storage = MyLocationsStorage(this)
        storage.setOnChangeListener {
            // Debounce: sync 2 seconds after last change
            applicationScope.launch {
                delay(2000)
                val locations = storage.load()
                LocationsSyncRepository.syncToCloud(this@MyApplication, locations)
            }
        }
    }
}