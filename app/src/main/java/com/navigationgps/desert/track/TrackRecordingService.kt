package com.navigationgps.desert.track

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
import com.navigationgps.desert.R
import com.navigationgps.desert.map.DesertMapActivity
import com.navigationgps.desert.map.NavigationMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min

enum class RecordingStatus {
    IDLE,
    RECORDING,
    PAUSED
}

data class TrackRecordingSnapshot(
    val status: RecordingStatus = RecordingStatus.IDLE,
    val startTimeMillis: Long = 0L,
    val elapsedDurationSeconds: Long = 0L,
    val totalDistanceMeters: Double = 0.0,
    val currentSpeedMps: Float = 0f,
    val maxSpeedMps: Float = 0f,
    val avgSpeedMps: Float = 0f,
    val currentAltitudeMeters: Double? = null,
    val minAltitudeMeters: Double? = null,
    val maxAltitudeMeters: Double? = null,
    val pointsCount: Int = 0,
    val latestPoint: RecordedPoint? = null
)

class TrackRecordingService : Service() {

    companion object {
        private const val TAG = "TrackRecordingService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "desert_track_recording_channel"

        const val ACTION_START = "com.navigationgps.desert.track.START"
        const val ACTION_PAUSE = "com.navigationgps.desert.track.PAUSE"
        const val ACTION_RESUME = "com.navigationgps.desert.track.RESUME"
        const val ACTION_STOP = "com.navigationgps.desert.track.STOP"
        const val ACTION_DISCARD = "com.navigationgps.desert.track.DISCARD"

        const val EXTRA_OPEN_SAVE_TRACK = "extra_open_save_track"

        private val _recordingSnapshot = MutableStateFlow(TrackRecordingSnapshot())
        val recordingSnapshot: StateFlow<TrackRecordingSnapshot> = _recordingSnapshot.asStateFlow()

        // Fast concurrent copy-on-write buffer for direct map overlay rendering and save
        private val _activePoints = CopyOnWriteArrayList<TrackPointEntity>()
        val activePoints: List<TrackPointEntity> get() = _activePoints

        fun start(context: Context) {
            val intent = Intent(context, TrackRecordingService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pause(context: Context) {
            val intent = Intent(context, TrackRecordingService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, TrackRecordingService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrackRecordingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun discard(context: Context) {
            val intent = Intent(context, TrackRecordingService::class.java).apply {
                action = ACTION_DISCARD
            }
            context.startService(intent)
        }

        fun clearActivePoints() {
            _activePoints.clear()
            _recordingSnapshot.value = TrackRecordingSnapshot()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickerJob: Job? = null

    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private val jitterFilter = TrackJitterFilter()

    private var startTimeMillis: Long = 0L
    private var elapsedSeconds: Long = 0L
    private var totalDistanceMeters: Double = 0.0
    private var maxSpeedMps: Float = 0f
    private var minAltitudeMeters: Double? = null
    private var maxAltitudeMeters: Double? = null
    private var currentAltitudeMeters: Double? = null
    private var currentSpeedMps: Float = 0f
    private var lastNotificationUpdateTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> handleStop()
            ACTION_DISCARD -> handleDiscard()
            else -> {
                if (_recordingSnapshot.value.status == RecordingStatus.IDLE) {
                    handleStart()
                }
            }
        }
        return START_STICKY
    }

    private fun handleStart() {
        if (_recordingSnapshot.value.status == RecordingStatus.RECORDING) return

        startTimeMillis = System.currentTimeMillis()
        elapsedSeconds = 0L
        totalDistanceMeters = 0.0
        maxSpeedMps = 0f
        minAltitudeMeters = null
        maxAltitudeMeters = null
        currentAltitudeMeters = null
        currentSpeedMps = 0f
        _activePoints.clear()
        jitterFilter.reset()

        val hasLocationPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPerm) {
            Log.e(TAG, "Location permission missing, aborting foreground start")
            stopSelf()
            return
        }

        val notification = buildNotification(isPaused = false)
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

        updateSnapshot(RecordingStatus.RECORDING)
        startLocationUpdates()
        startTicker()
    }

    private fun handlePause() {
        if (_recordingSnapshot.value.status != RecordingStatus.RECORDING) return
        tickerJob?.cancel()
        updateSnapshot(RecordingStatus.PAUSED)
        updateNotification(isPaused = true)
    }

    private fun handleResume() {
        if (_recordingSnapshot.value.status != RecordingStatus.PAUSED) return
        updateSnapshot(RecordingStatus.RECORDING)
        startTicker()
        updateNotification(isPaused = false)
    }

    private fun handleStop() {
        tickerJob?.cancel()
        stopLocationUpdates()
        clearActivePoints()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleDiscard() {
        handleStop()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                if (_recordingSnapshot.value.status == RecordingStatus.RECORDING) {
                    elapsedSeconds++
                    updateSnapshot(RecordingStatus.RECORDING)
                    val now = System.currentTimeMillis()
                    if (now - lastNotificationUpdateTime >= 1000L) {
                        lastNotificationUpdateTime = now
                        updateNotification(isPaused = false)
                    }
                }
            }
        }
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

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                if (loc.latitude == 0.0 && loc.longitude == 0.0) return
                if (_recordingSnapshot.value.status == RecordingStatus.RECORDING) {
                    processNewLocation(loc)
                }
            }
        }
        locationCallback = callback

        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
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

    private fun processNewLocation(loc: Location) {
        val result = jitterFilter.filter(loc)
        if (result is FilterResult.Accept) {
            val pt = result.point
            totalDistanceMeters += pt.distanceDeltaMeters
            currentSpeedMps = pt.speedMps ?: 0f
            maxSpeedMps = max(maxSpeedMps, currentSpeedMps)

            val alt = pt.altitude
            currentAltitudeMeters = alt
            if (alt != null) {
                minAltitudeMeters = min(minAltitudeMeters ?: alt, alt)
                maxAltitudeMeters = max(maxAltitudeMeters ?: alt, alt)
            }

            val entity = TrackPointEntity(
                trackId = 0L, // set upon final save
                seqIndex = _activePoints.size,
                latitude = pt.latitude,
                longitude = pt.longitude,
                altitude = pt.altitude,
                speedMps = pt.speedMps,
                bearingDeg = pt.bearingDeg,
                timestampMillis = pt.timestampMillis
            )
            _activePoints.add(entity)

            updateSnapshot(RecordingStatus.RECORDING, latestPoint = pt)
        }
    }

    private fun updateSnapshot(status: RecordingStatus, latestPoint: RecordedPoint? = null) {
        val avgSpeed = if (elapsedSeconds > 0) {
            (totalDistanceMeters / elapsedSeconds).toFloat()
        } else 0f

        _recordingSnapshot.value = TrackRecordingSnapshot(
            status = status,
            startTimeMillis = startTimeMillis,
            elapsedDurationSeconds = elapsedSeconds,
            totalDistanceMeters = totalDistanceMeters,
            currentSpeedMps = currentSpeedMps,
            maxSpeedMps = maxSpeedMps,
            avgSpeedMps = avgSpeed,
            currentAltitudeMeters = currentAltitudeMeters,
            minAltitudeMeters = minAltitudeMeters,
            maxAltitudeMeters = maxAltitudeMeters,
            pointsCount = _activePoints.size,
            latestPoint = latestPoint ?: _recordingSnapshot.value.latestPoint
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "تسجيل الأثر (GPS Tracking)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار متابعة تسجيل أثر الرحلة البرية في الخلفية"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(isPaused: Boolean): Notification {
        val mapIntent = Intent(this, DesertMapActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeIntent = Intent(this, TrackRecordingService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val pauseResumePendingIntent = PendingIntent.getService(
            this,
            1,
            pauseResumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val saveIntent = Intent(this, DesertMapActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_SAVE_TRACK, true)
        }
        val savePendingIntent = PendingIntent.getActivity(
            this,
            2,
            saveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val formattedTime = formatElapsed(elapsedSeconds)
        val formattedDist = formatDistance(totalDistanceMeters)
        val formattedSpeed = String.format(Locale.US, "%.0f كم/س", currentSpeedMps * 3.6f)

        val title = if (isPaused) "تسجيل الأثر متوقف مؤقتًا ⏸" else "تسجيل الأثر جاري... ●"
        val body = "$formattedDist | $formattedTime | $formattedSpeed"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.final_app_icon_removebg)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                if (isPaused) "استمرار ▶" else "إيقاف مؤقت ⏸",
                pauseResumePendingIntent
            )
            .addAction(
                0,
                "إنهاء وحفظ ⏹",
                savePendingIntent
            )

        return builder.build()
    }

    private fun updateNotification(isPaused: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(isPaused))
    }

    private fun formatElapsed(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    private fun formatDistance(distMeters: Double): String {
        return if (distMeters >= 1000.0) {
            String.format(Locale.US, "%.1f كم", distMeters / 1000.0)
        } else {
            String.format(Locale.US, "%.0f م", distMeters)
        }
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        serviceScope.cancel()
        stopLocationUpdates()
        super.onDestroy()
    }
}
