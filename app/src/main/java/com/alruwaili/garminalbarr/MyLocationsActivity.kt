package com.alruwaili.garminalbarr

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.alruwaili.garminalbarr.billing.FeatureGatekeeper
import com.alruwaili.garminalbarr.map.LocationShareHelper
import com.alruwaili.garminalbarr.map.MapNavigationHelper
import com.alruwaili.garminalbarr.mylocations.ui.MyLocationsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.alruwaili.garminalbarr.track.GpxExporter
import com.alruwaili.garminalbarr.track.TrackEntity
import com.alruwaili.garminalbarr.track.TrackRepository
import com.alruwaili.garminalbarr.network.SignalPointEntity
import com.alruwaili.garminalbarr.network.SignalPointRepository

class MyLocationsActivity : ComponentActivity() {

    private lateinit var sharedLocationViewModel: SharedLocationViewModel
    private val trackRepository by lazy { TrackRepository(this) }
    private val signalPointRepository by lazy { SignalPointRepository(this) }
    private val locationsState = mutableStateOf<List<LocationEntity>>(emptyList())
    private val tracksState = mutableStateOf<List<TrackEntity>>(emptyList())
    private val signalPointsState = mutableStateOf<List<SignalPointEntity>>(emptyList())
    private val currentLocationState = mutableStateOf<LocationData?>(null)
    private val isRestoringState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val app = application as MyApplication
        sharedLocationViewModel = app.sharedLocationViewModel

        sharedLocationViewModel.manualLocations.observe(this) { list ->
            locationsState.value = list?.toList() ?: emptyList()
        }
        sharedLocationViewModel.currentLocation.observe(this) { loc ->
            currentLocationState.value = loc
        }

        lifecycleScope.launch {
            trackRepository.allTracks.collect { list ->
                tracksState.value = list
            }
        }

        lifecycleScope.launch {
            signalPointRepository.allSignalPoints.collect { list ->
                signalPointsState.value = list
            }
        }

        val initialTab = if (intent.getBooleanExtra("open_tracks_tab", false)) 1 else 0

        setContent {
            MyLocationsScreen(
                locations = locationsState.value,
                currentLocation = currentLocationState.value,
                isRestoring = isRestoringState.value,
                onBack = { finish() },
                onLocationClick = { location -> navigateToMap(location) },
                onFavoriteToggle = { location -> toggleFavorite(location) },
                onShareLocation = { location -> shareLocation(location) },
                onDeleteLocalOnly = { location -> deleteLocationLocalOnly(location) },
                onDeleteBoth = { location -> deleteLocationBoth(location) },
                onOpenCompass = { location -> openCompassTo(location) },
                onAddLocation = { title, lat, lon -> addLocation(title, lat, lon) },
                onRestoreFromCloud = { restoreFromCloud() },
                tracks = tracksState.value,
                initialTab = initialTab,
                onTrackClick = { track -> viewTrackOnMap(track) },
                onToggleTrackVisibility = { track -> toggleTrackVisibility(track) },
                onBacktrack = { track -> backtrackOnTrack(track) },
                onExportGpx = { track -> exportGpx(track) },
                onDeleteTrack = { track -> deleteTrack(track) },
                signalPoints = signalPointsState.value,
                onSignalPointGoTo = { point -> navigateToSignalPoint(point) },
                onSignalPointRename = { point, label -> renameSignalPoint(point, label) },
                onSignalPointDelete = { point -> deleteSignalPoint(point) }
            )
        }
    }

    private fun navigateToSignalPoint(point: SignalPointEntity) {
        FeatureGatekeeper.guardAction(this) {
            MapNavigationHelper.goToDesertMap(this, point.latitude, point.longitude, point.displayName)
            finish()
        }
    }

    private fun renameSignalPoint(point: SignalPointEntity, label: String?) {
        lifecycleScope.launch {
            signalPointRepository.rename(point.id, label)
        }
    }

    private fun deleteSignalPoint(point: SignalPointEntity) {
        lifecycleScope.launch {
            signalPointRepository.delete(point)
            Toast.makeText(this@MyLocationsActivity, "تم حذف نقطة الشبكة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun viewTrackOnMap(track: TrackEntity) {
        val intent = Intent(this, com.alruwaili.garminalbarr.map.DesertMapActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("action_view_track_id", track.id)
        }
        startActivity(intent)
        finish()
    }

    private fun toggleTrackVisibility(track: TrackEntity) {
        lifecycleScope.launch {
            trackRepository.setTrackVisibility(track.id, !track.isVisibleOnMap)
        }
    }

    private fun backtrackOnTrack(track: TrackEntity) {
        val intent = Intent(this, com.alruwaili.garminalbarr.map.DesertMapActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("action_backtrack_track_id", track.id)
        }
        startActivity(intent)
        finish()
    }

    private fun exportGpx(track: TrackEntity) {
        lifecycleScope.launch {
            val points = trackRepository.getTrackPoints(track.id)
            GpxExporter.shareGpx(this@MyLocationsActivity, track, points)
        }
    }

    private fun deleteTrack(track: TrackEntity) {
        lifecycleScope.launch {
            trackRepository.deleteTrack(track.id)
            Toast.makeText(this@MyLocationsActivity, "تم حذف المسار بنجاح", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        sharedLocationViewModel.startLocationUpdates(this)
        sharedLocationViewModel.refreshLocation(this)
    }

    override fun onStop() {
        super.onStop()
        sharedLocationViewModel.stopLocationUpdates(this)
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun navigateToMap(location: LocationEntity) {
        val lat = location.latitude
        val lon = location.longitude
        if (!MapNavigationHelper.isValidLatLon(lat, lon)) {
            Toast.makeText(this, "إحداثيات غير صالحة", Toast.LENGTH_SHORT).show()
            return
        }
        FeatureGatekeeper.guardAction(this) {
            MapNavigationHelper.goToDesertMap(this, lat!!, lon!!, location.title)
            finish()
        }
    }

    private fun openCompassTo(location: LocationEntity) {
        val lat = location.latitude
        val lon = location.longitude
        if (!MapNavigationHelper.isValidLatLon(lat, lon)) {
            Toast.makeText(this, "إحداثيات غير صالحة", Toast.LENGTH_SHORT).show()
            return
        }
        FeatureGatekeeper.guardAction(this) {
            MapNavigationHelper.goToDesertMap(this, lat!!, lon!!, location.title, openCompass = true)
            finish()
        }
    }

    private fun toggleFavorite(location: LocationEntity) {
        sharedLocationViewModel.toggleFavoritePersistent(location)
    }

    private fun shareLocation(location: LocationEntity) {
        FeatureGatekeeper.guardAction(this) {
            val lat = location.latitude ?: return@guardAction
            val lon = location.longitude ?: return@guardAction
            LocationShareHelper.shareLocation(this, location.title, lat, lon)
        }
    }

    private fun deleteLocationLocalOnly(location: LocationEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            val storage = MyLocationsStorage(this@MyLocationsActivity)
            val list = storage.load().toMutableList()
            val removed = list.removeAll {
                it.title == location.title && it.latitude == location.latitude && it.longitude == location.longitude
            }
            if (removed) {
                storage.save(list)
                sharedLocationViewModel.manualLocations.postValue(list)
                val lat = location.latitude
                val lon = location.longitude
                val title = location.title
                if (lat != null && lon != null && title != null) {
                    try {
                        RoomDBHelper.getInstance(applicationContext).locationDao.deleteUserSavedLocation(title, lat, lon)
                    } catch (_: Exception) {}
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MyLocationsActivity, "تم الحذف من الجهاز فقط", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteLocationBoth(location: LocationEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            val storage = MyLocationsStorage(this@MyLocationsActivity)
            val list = storage.load().toMutableList()
            val removed = list.removeAll {
                it.title == location.title && it.latitude == location.latitude && it.longitude == location.longitude
            }
            if (removed) {
                storage.save(list)
                sharedLocationViewModel.manualLocations.postValue(list)
                LocationsSyncRepository.replaceCloudFromLocal(applicationContext, list)
                val lat = location.latitude
                val lon = location.longitude
                val title = location.title
                if (lat != null && lon != null && title != null) {
                    try {
                        RoomDBHelper.getInstance(applicationContext).locationDao.deleteUserSavedLocation(title, lat, lon)
                    } catch (_: Exception) {}
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MyLocationsActivity, "تم الحذف من الجهاز والسحابة", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun addLocation(title: String, lat: Double, lon: Double) {
        val entity = LocationEntity(
            title = title,
            latitude = lat,
            longitude = lon,
            isManual = true
        )
        sharedLocationViewModel.addManualLocation(entity)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                RoomDBHelper.getInstance(applicationContext).locationDao.insert(entity)
            } catch (_: Exception) {}
        }
        Toast.makeText(this, "تمت إضافة الموقع بنجاح", Toast.LENGTH_SHORT).show()
    }

    private fun restoreFromCloud() {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "لا يوجد اتصال بالإنترنت", Toast.LENGTH_SHORT).show()
            return
        }

        isRestoringState.value = true
        lifecycleScope.launch(Dispatchers.IO) {
            val cloudLocations = try {
                LocationsSyncRepository.restoreFromCloud(applicationContext)
            } catch (e: Exception) {
                null
            }

            if (cloudLocations.isNullOrEmpty()) {
                withContext(Dispatchers.Main) {
                    isRestoringState.value = false
                    Toast.makeText(
                        this@MyLocationsActivity,
                        "لا توجد مواقع محفوظة في السحابة",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            // Merge + disk write strictly on IO, never on Main.
            val storage = MyLocationsStorage(applicationContext)
            val local = try {
                storage.load().toMutableList()
            } catch (e: Exception) {
                mutableListOf<LocationEntity>()
            }
            var addedCount = 0

            for (cloudLoc in cloudLocations) {
                val isDuplicate = local.any {
                    it.title == cloudLoc.title &&
                            it.latitude == cloudLoc.latitude &&
                            it.longitude == cloudLoc.longitude
                }
                if (!isDuplicate) {
                    local.add(cloudLoc)
                    addedCount++
                }
            }

            try {
                storage.save(local)
            } catch (e: Exception) {
                android.util.Log.e("MyLocationsActivity", "Failed saving restored locations", e)
            }
            val mergedSnapshot: List<LocationEntity> = local.toList()
            val added = addedCount

            withContext(Dispatchers.Main) {
                isRestoringState.value = false
                sharedLocationViewModel.manualLocations.value =
                    mergedSnapshot.toMutableList() as MutableList<LocationEntity>
                val message = if (added > 0) {
                    "تم استعادة $added موقع جديد من السحابة"
                } else {
                    "جميع المواقع السحابية موجودة مسبقاً"
                }
                Toast.makeText(this@MyLocationsActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val configuration = Configuration(newBase.resources.configuration)
        configuration.fontScale = 1.0f
        val context = newBase.createConfigurationContext(configuration)
        super.attachBaseContext(context)
    }
}
