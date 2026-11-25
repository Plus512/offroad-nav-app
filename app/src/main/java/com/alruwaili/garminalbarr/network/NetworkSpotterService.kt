package com.alruwaili.garminalbarr.network

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.alruwaili.garminalbarr.R
import com.alruwaili.garminalbarr.map.DesertMapActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "راصد الشبكة" (Network / Signal Spotter): a foreground service that, while active,
 * keeps track of the device's last known location and automatically records a
 * "signal point" (its coordinates) the exact moment cellular connectivity is LOST —
 * so the user can later retrace their steps back to the last place signal was available.
 *
 * Mirrors the structure of [com.alruwaili.garminalbarr.track.TrackRecordingService]:
 * a started (not bound) foreground Service, driven by explicit Intent actions, exposing
 * its running state as a StateFlow so the UI (button color + top-of-screen red-blink HUD)
 * can react without binding to the service.
 */
class NetworkSpotterService : Service() {

    companion object {
        private const val TAG = "NetworkSpotterService"
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "desert_network_spotter_channel"

        const val ACTION_START = "com.alruwaili.garminalbarr.network.START"
        const val ACTION_STOP = "com.alruwaili.garminalbarr.network.STOP"

        private val _isActive = MutableStateFlow(false)
        val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

        // Bumped on every successful auto-recorded signal point, purely so the UI
        // (the "نقاط الشبكة" tab / toast) can react to a fresh capture if needed.
        private val _lastCapturedPoint = MutableStateFlow<SignalPointEntity?>(null)
        val lastCapturedPoint: StateFlow<SignalPointEntity?> = _lastCapturedPoint.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, NetworkSpotterService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, NetworkSpotterService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: SignalPointRepository

    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var lastKnownLocation: Location? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        repository = SignalPointRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> handleStop()
            else -> handleStart()
        }
        return START_STICKY
    }

    private fun handleStart() {
        if (_isActive.value) return

        val hasLocationPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPerm) {
            Log.e(TAG, "Location permission missing, aborting foreground start")
            stopSelf()
            return
        }

        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }

        _isActive.value = true
        startLocationUpdates()
        registerNetworkCallback()
    }

    private fun handleStop() {
        _isActive.value = false
        stopLocationUpdates()
        unregisterNetworkCallback()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "ACCESS_FINE_LOCATION not granted, cannot start updates")
            return
        }

        val client = LocationServices.getFusedLocationProviderClient(this)
        fusedClient = client

        // Lower-frequency balanced-power updates: we only need a "last known location"
        // good enough to tag a signal-loss point, not a dense continuous track.
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 8000L)
            .setMinUpdateIntervalMillis(5000L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                if (loc.latitude == 0.0 && loc.longitude == 0.0) return
                lastKnownLocation = loc
            }
        }
        locationCallback = callback

        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            // Seed immediately with the last cached fix so an early signal loss still has a point.
            client.lastLocation.addOnSuccessListener { loc -> if (loc != null) lastKnownLocation = loc }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException requesting location updates", e)
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { cb ->
            try {
                fusedClient?.removeLocationUpdates(cb)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing location updates", e)
            }
            locationCallback = null
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        // onLost() only fires for a transport that WAS available and then went away,
        // so this cannot misfire on first registration while there is simply no signal yet.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                recordSignalPoint()
            }
        }
        networkCallback = callback

        try {
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register cellular NetworkCallback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            try {
                connectivityManager?.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering NetworkCallback", e)
            }
        }
        networkCallback = null
    }

    private fun recordSignalPoint() {
        val loc = lastKnownLocation ?: return
        serviceScope.launch {
            val number = repository.nextPointNumber()
            val point = SignalPointEntity(
                pointNumber = number,
                latitude = loc.latitude,
                longitude = loc.longitude,
                capturedAtMillis = System.currentTimeMillis()
            )
            val id = repository.insert(point)
            _lastCapturedPoint.value = point.copy(id = id)
            updateNotification(justCaptured = true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "راصد الشبكة (Network Spotter)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار متابعة راصد الشبكة أثناء عمله في الخلفية"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(justCaptured: Boolean = false): Notification {
        val mapIntent = Intent(this, DesertMapActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, NetworkSpotterService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (justCaptured) "تم تسجيل نقطة شبكة ●" else "راصد الشبكة يعمل ●"
        val body = "يتم تسجيل نقطة شبكة تلقائيًا عند فقدان تغطية الشبكة"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.final_app_icon_removebg)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "إيقاف راصد الشبكة ⏹", stopPendingIntent)
            .build()
    }

    private fun updateNotification(justCaptured: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(justCaptured))
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopLocationUpdates()
        unregisterNetworkCallback()
        super.onDestroy()
    }
}
