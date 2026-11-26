package com.alruwaili.garminalbarr.map

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.alruwaili.garminalbarr.billing.FeatureGatekeeper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.alruwaili.garminalbarr.FavoritesActivity
import android.os.Build
import android.util.Log
import com.alruwaili.garminalbarr.LocationEntity
import com.alruwaili.garminalbarr.MyApplication
import com.alruwaili.garminalbarr.R
import com.alruwaili.garminalbarr.RoomDBHelper
import com.alruwaili.garminalbarr.map.download.DownloadState
import com.alruwaili.garminalbarr.map.download.DownloadStateManager
import com.alruwaili.garminalbarr.map.download.MapRegion
import com.alruwaili.garminalbarr.map.download.RegionRegistry
import com.alruwaili.garminalbarr.map.download.TileDownloadService
import com.alruwaili.garminalbarr.map.ui.ContextualDownloadCard
import com.alruwaili.garminalbarr.map.ui.DownloadManagerScreen
import com.alruwaili.garminalbarr.map.ui.ActiveRouteUiState
import com.alruwaili.garminalbarr.map.ui.FloatingMiniTargetCompass
import com.alruwaili.garminalbarr.map.ui.LandmarkSearchOverlay
import com.alruwaili.garminalbarr.map.ui.MapControlsOverlay
import com.alruwaili.garminalbarr.map.ui.NavigationHud
import com.alruwaili.garminalbarr.map.ui.PoiBottomSheet
import com.alruwaili.garminalbarr.map.ui.CompassTarget
import com.alruwaili.garminalbarr.map.ui.TargetCompassOverlay
import com.alruwaili.garminalbarr.map.routing.CarRouteMode
import com.alruwaili.garminalbarr.map.ui.CarRouteOptionsDialog
import com.alruwaili.garminalbarr.map.ui.RouteSlot
import com.alruwaili.garminalbarr.map.ui.SlotType
import com.alruwaili.garminalbarr.map.ui.TrackRecordingHud
import com.alruwaili.garminalbarr.map.ui.NetworkSpotterToggleButton
import com.alruwaili.garminalbarr.network.NetworkSpotterService
import com.alruwaili.garminalbarr.network.ui.NetworkSpotterHud
import com.alruwaili.garminalbarr.CacheState
import com.alruwaili.garminalbarr.LocationLightweight
import com.alruwaili.garminalbarr.desertmind.ui.DesertMindDialog
import com.alruwaili.garminalbarr.desertmind.DesertRadarEngine
import com.alruwaili.garminalbarr.desertmind.ui.DesertRadarRadiusDialog
import com.alruwaili.garminalbarr.desertmind.ui.DesertRadarSweepOverlay
import com.alruwaili.garminalbarr.desertmind.ui.DigitalAssistantScreen
import com.alruwaili.garminalbarr.map.ui.SaveTrackDialog
import com.alruwaili.garminalbarr.map.ui.DiscardTrackDialog
import com.alruwaili.garminalbarr.track.RecordingStatus
import com.alruwaili.garminalbarr.track.TrackEntity
import com.alruwaili.garminalbarr.track.TrackPointEntity
import com.alruwaili.garminalbarr.track.TrackRecordingService
import com.alruwaili.garminalbarr.track.TrackRecordingSnapshot
import com.alruwaili.garminalbarr.track.TrackRepository
import kotlin.math.ln
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.util.Locale

class DesertMapActivity : FragmentActivity(), OsmdroidMapFragment.OnMapInteractionListener {

    private var inAppUpdater: com.alruwaili.garminalbarr.update.InAppUpdater? = null
    private var lastEntitlementCheckMs = 0L

    /**
     * يمنع بقاء توجيه بدأ أثناء التجربة مفتوحاً بعد انتهائها
     * (المسار المحفوظ يُستعاد تلقائياً عند كل فتح للتطبيق).
     */
    private fun enforceEntitlementOnActiveRoute() {
        val sub = (application as MyApplication).subscriptionManager
        if (sub.hasFullAccessWithGrace()) return
        val navPrefs = getSharedPreferences("desert_nav_prefs", Context.MODE_PRIVATE)
        if (!navPrefs.getBoolean("has_active_route", false)) return
        val frag = osmdroidFragment
        if (frag != null && frag.isAdded) {
            frag.clearRoute()
        } else {
            navPrefs.edit().putBoolean("has_active_route", false).apply()
        }
        hasActiveRouteState.value = false
        Toast.makeText(this, "انتهت الفترة التجريبية — تم إيقاف التوجيه. اشترك لمتابعة الملاحة.", Toast.LENGTH_LONG).show()
        startActivity(Intent(this, com.alruwaili.garminalbarr.billing.PaywallFeaturesActivity::class.java))
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST = 1003
    }

    private val trackRepository by lazy { TrackRepository(this) }
    private val recordingSnapshotState = mutableStateOf(TrackRecordingSnapshot())
    private val isNetworkSpotterActiveState = mutableStateOf(false)
    // "العقل الصحراوي الذكي" — dialog visibility + the cached locations list it reads from.
    private val showDesertMindDialogState = mutableStateOf(false)
    private val desertMindLocationsState = mutableStateOf<List<LocationLightweight>>(emptyList())
    // "الرادار الصحراوي" — radius-picker visibility, then the active sweep's radius (km), or
    // null when no sweep is running.
    private val showDesertRadarPickerState = mutableStateOf(false)
    private val activeDesertRadarKmState = mutableStateOf<Int?>(null)
    // "الأدوات" — single menu gathering every new feature button added this session.
    private val showToolsMenuState = mutableStateOf(false)
    // "الرؤية الصحراوية X-Ray" — طبقة تحليلية (ارتفاع+انحدار) فوق الخريطة المسطحة الحالية،
    // بديلة للتظليل العادي عند التفعيل فقط.
    private val isXRayModeState = mutableStateOf(false)
    // "الرؤية الصحراوية X-Ray" — إعادة تصميم 2026-11-26: شاشة تمثيلية متعددة المستويات
    // (قريب/متوسط/كامل) بدل مجرد تبديل تشغيل/إيقاف على الخريطة المسطحة.
    private val showXRayExperienceState = mutableStateOf(false)
    // "المساعد الرقمي" — زر مستقل بين راصد الشبكة والأدوات، يفتح شاشة HUD رقمية كاملة.
    private val showDigitalAssistantState = mutableStateOf(false)
    // ارتفاع حقيقي من ذاكرة التخزين المؤقت لبيانات DEM (TerrainElevationLookup) — يُحسب في
    // Dispatchers.IO عند فتح شاشة المساعد الرقمي فقط (ليس عند كل إعادة رسم)، وليس بيانات وهمية.
    private val digitalAssistantElevationState = mutableStateOf<Double?>(null)
    private val showSaveTrackDialog = mutableStateOf(false)
    private val showDiscardTrackDialog = mutableStateOf(false)
    private val showCarRouteOptionsDialog = mutableStateOf(false)
    private val pendingCarRouteOrigin = mutableStateOf<RouteSlot?>(null)
    private val pendingCarRouteDest = mutableStateOf<RouteSlot?>(null)
    private var lastSelectedCarRouteMode = CarRouteMode.CLOSEST_PAVED
    private var activeTrackColorHex: String = "#E53935"
    private var lastRenderedPointTimestamp: Long = 0L

    private val downloadStates = mutableStateMapOf<String, DownloadState>()
    private val downloadProgress = mutableStateMapOf<String, Float>()
    private val routingStates = mutableStateMapOf<String, DownloadState>()
    private val routingProgress = mutableStateMapOf<String, Float>()
    private val terrainStates = mutableStateMapOf<String, DownloadState>()
    private val terrainProgress = mutableStateMapOf<String, Float>()
    private val storageUsedBytes = mutableStateOf(0L)
    private val routingStorageUsedBytes = mutableStateOf(0L)
    private val terrainStorageUsedBytes = mutableStateOf(0L)
    private val downloadTitleState = mutableStateOf<String?>(null)
    private val pendingRegion = mutableStateOf<MapRegion?>(null)
    private val selectedPoi = mutableStateOf<PoiDetails?>(null)
    private val scaleTextState = mutableStateOf("")
    private val hasActiveRouteState = mutableStateOf(false)
    private val showDownloadManager = mutableStateOf(false)
    private val showSearchOverlay = mutableStateOf(false)
    private val searchViewModel: LandmarkSearchViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(
            this,
            LandmarkSearchViewModel.Factory((application as MyApplication).sharedLocationViewModel)
        )[LandmarkSearchViewModel::class.java]
    }
    private var osmdroidFragment: OsmdroidMapFragment? = null
    private val downloadService = TileDownloadService()
    private var brouterEngine: com.alruwaili.garminalbarr.map.routing.BRouterOfflineEngine? = null
    private val isCarRouteState = mutableStateOf(false)
    private val isCalculatingRouteState = mutableStateOf(false)
    private val carRouteDistanceState = mutableStateOf<Double?>(null)
    private var carDistanceJob: Job? = null
    /**
     * Cached result from [preCalculateCarRouteDistance]. When the user taps the car route
     * button, [startCarNavigation] reuses this result instead of running a second full
     * BRouter graph search — eliminating the double-calculation regression.
     * Invalidated whenever origin or destination changes, or when the route is cleared.
     */
    private var cachedCarRouteOrigin: RouteSlot? = null
    private var cachedCarRouteDest: RouteSlot? = null
    private var cachedCarRouteResult: com.alruwaili.garminalbarr.map.routing.OfflineRouter.RouteResult? = null

    private val pickingSlotTypeState = mutableStateOf<SlotType?>(null)
    private val activePickerSlotState = mutableStateOf<SlotType?>(null)
    private val activeOriginSlotState = mutableStateOf<RouteSlot?>(null)
    private val activeDestSlotState = mutableStateOf<RouteSlot?>(null)
    private val azimuthState = mutableFloatStateOf(0f)

    // ---- Active navigation HUD state (1 Hz live engine, never sensor-rate) ----
    private val activeRouteNameState = mutableStateOf<String?>(null)
    private val remainingMetersState = mutableStateOf<Double?>(null)
    // Live user→destination geodesic bearing (1 Hz). NaN = no fix yet.
    // Read only by compass overlay leaves — the small HUD stays distance-only.
    private val bearingState = mutableFloatStateOf(Float.NaN)
    // Full-screen target compass overlay. Non-null = visible.
    private val compassTargetState = mutableStateOf<CompassTarget?>(null)
    private val isAutoFollowSuspendedState = mutableStateOf(false)
    private val isCourseUpState = mutableStateOf(false)

    override fun onRegionChanged(region: MapRegion?) {
        pendingRegion.value = region
    }

    override fun onMapPointTapped(poi: PoiDetails) {
        val picking = pickingSlotTypeState.value
        if (picking != null) {
            val tappedSlot = RouteSlot(
                title = poi.name,
                latitude = poi.latitude,
                longitude = poi.longitude,
                isCurrentLocation = false
            )
            if (picking == SlotType.ORIGIN) {
                activeOriginSlotState.value = tappedSlot
            } else {
                activeDestSlotState.value = tappedSlot
            }
            pickingSlotTypeState.value = null
            activePickerSlotState.value = null
            if (selectedPoi.value == null) {
                selectedPoi.value = poi
            }
        } else {
            val userLoc = osmdroidFragment?.getCurrentUserLocation()
            val userLat = userLoc?.latitude ?: OsmdroidMapFragment.DEFAULT_CENTER.latitude
            val userLon = userLoc?.longitude ?: OsmdroidMapFragment.DEFAULT_CENTER.longitude
            activeOriginSlotState.value = RouteSlot("موقعي الحالي", userLat, userLon, isCurrentLocation = true)
            activeDestSlotState.value = RouteSlot(poi.name, poi.latitude, poi.longitude, isCurrentLocation = false)
            activePickerSlotState.value = null
            selectedPoi.value = poi
        }
    }

    override fun onAzimuthChanged(azimuth: Float) {
        azimuthState.floatValue = azimuth
    }

    override fun onMapScaleChanged(scaleText: String) {
        scaleTextState.value = scaleText
    }

    override fun onRouteStateChanged(hasRoute: Boolean) {
        hasActiveRouteState.value = hasRoute
        if (!hasRoute) {
            activeRouteNameState.value = null
            remainingMetersState.value = null
            bearingState.floatValue = Float.NaN
            isAutoFollowSuspendedState.value = false
        }
    }

    override fun onAutoFollowStateChanged(isFollowing: Boolean) {
        isAutoFollowSuspendedState.value = !isFollowing
    }

    override fun onNavigationProgress(remainingMeters: Double, bearingDeg: Float) {
        remainingMetersState.value = remainingMeters
        bearingState.floatValue = bearingDeg
    }

    override fun onHeadingUpChanged(isHeadingUp: Boolean) {
        isCourseUpState.value = isHeadingUp
    }

    override fun onLocationUpdated(location: android.location.Location) {
        // فحص دوري (كل دقيقة) أثناء التوجيه: إن انتهت التجربة أو الاشتراك يتوقف التوجيه
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (nowMs - lastEntitlementCheckMs > 60_000L) {
            lastEntitlementCheckMs = nowMs
            enforceEntitlementOnActiveRoute()
        }
        val curOrigin = activeOriginSlotState.value
        if (!hasActiveRouteState.value && (curOrigin == null || curOrigin.isCurrentLocation)) {
            val dist = if (curOrigin != null) {
                NavigationMath.distanceMeters(curOrigin.latitude, curOrigin.longitude, location.latitude, location.longitude)
            } else {
                Double.MAX_VALUE
            }
            if (dist > 20.0) {
                activeOriginSlotState.value = RouteSlot(
                    title = "موقعي الحالي",
                    latitude = location.latitude,
                    longitude = location.longitude,
                    isCurrentLocation = true
                )
            }
        }
    }

    private fun startDownload(region: MapRegion) {
        if (downloadStates[region.id] == DownloadState.DOWNLOADING) return

        lifecycleScope.launch {
            downloadStates[region.id] = DownloadState.DOWNLOADING
            downloadProgress[region.id] = 0f

            val result = downloadService.downloadMapFile(this@DesertMapActivity, region) { progress ->
                downloadProgress[region.id] = progress
            }

            result.fold(
                onSuccess = {
                    downloadStates[region.id] = DownloadState.DOWNLOADED
                    downloadProgress.remove(region.id)
                    storageUsedBytes.value = MapFileManager.getStorageUsedBytes(this@DesertMapActivity)
                    osmdroidFragment?.refreshTileSource()
                    Toast.makeText(this@DesertMapActivity, "تم تحميل ${region.nameAr}", Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    downloadStates[region.id] = DownloadState.NOT_DOWNLOADED
                    downloadProgress.remove(region.id)
                    Toast.makeText(this@DesertMapActivity, "فشل التحميل: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun deleteRegion(region: MapRegion) {
        if (MapFileManager.deleteRegion(this, region.id)) {
            downloadStates[region.id] = DownloadState.NOT_DOWNLOADED
            storageUsedBytes.value = MapFileManager.getStorageUsedBytes(this)
            osmdroidFragment?.refreshTileSource()
            Toast.makeText(this, "تم حذف ${region.nameAr}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRoutingDownload(region: MapRegion) {
        if (routingStates[region.id] == DownloadState.DOWNLOADING) return

        lifecycleScope.launch {
            routingStates[region.id] = DownloadState.DOWNLOADING
            routingProgress[region.id] = 0f

            val result = downloadService.downloadCountryRouting(this@DesertMapActivity, region) { progress ->
                routingProgress[region.id] = progress
            }

            result.fold(
                onSuccess = {
                    routingStates[region.id] = DownloadState.DOWNLOADED
                    routingProgress.remove(region.id)
                    routingStorageUsedBytes.value = MapFileManager.getRoutingStorageUsedBytes(this@DesertMapActivity)
                    Toast.makeText(this@DesertMapActivity, "تم تحميل مسارات ${region.nameAr}", Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    routingStates[region.id] = DownloadState.NOT_DOWNLOADED
                    routingProgress.remove(region.id)
                    Toast.makeText(this@DesertMapActivity, "فشل تحميل المسارات: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun deleteRouting(region: MapRegion) {
        if (MapFileManager.deleteCountryRouting(this, region.id)) {
            routingStates[region.id] = DownloadState.NOT_DOWNLOADED
            routingStorageUsedBytes.value = MapFileManager.getRoutingStorageUsedBytes(this)
            Toast.makeText(this, "تم حذف مسارات ${region.nameAr}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startTerrainDownload(region: MapRegion) {
        if (terrainStates[region.id] == DownloadState.DOWNLOADING) return

        lifecycleScope.launch {
            terrainStates[region.id] = DownloadState.DOWNLOADING
            terrainProgress[region.id] = 0f

            val result = downloadService.downloadTerrainRegion(this@DesertMapActivity, region) { progress ->
                terrainProgress[region.id] = progress
            }

            result.fold(
                onSuccess = {
                    terrainStates[region.id] = DownloadState.DOWNLOADED
                    terrainProgress.remove(region.id)
                    terrainStorageUsedBytes.value = MapFileManager.getTerrainStorageUsedBytes(this@DesertMapActivity)
                    osmdroidFragment?.refreshTerrainOverlay()
                    Toast.makeText(this@DesertMapActivity, "تم تحميل تضاريس ${region.nameAr}", Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    terrainStates[region.id] = DownloadState.NOT_DOWNLOADED
                    terrainProgress.remove(region.id)
                    Toast.makeText(this@DesertMapActivity, "فشل تحميل التضاريس: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun deleteTerrain(region: MapRegion) {
        if (MapFileManager.deleteTerrainRegion(this, region.id)) {
            terrainStates[region.id] = DownloadState.NOT_DOWNLOADED
            terrainStorageUsedBytes.value = MapFileManager.getTerrainStorageUsedBytes(this)
            osmdroidFragment?.refreshTerrainOverlay()
            Toast.makeText(this, "تم حذف تضاريس ${region.nameAr}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleModularDownload(region: MapRegion, downloadMap: Boolean, downloadRouting: Boolean) {
        if (downloadMap) {
            startDownload(region)
        }
        if (downloadRouting) {
            startRoutingDownload(region)
        }
    }

    private fun resolveActualOrigin(origin: RouteSlot): RouteSlot {
        if (!origin.isCurrentLocation) return origin
        val userLoc = osmdroidFragment?.getCurrentUserLocation()
        return if (userLoc != null && MapNavigationHelper.isValidLatLon(userLoc.latitude, userLoc.longitude)) {
            RouteSlot(origin.title, userLoc.latitude, userLoc.longitude, isCurrentLocation = true)
        } else {
            origin
        }
    }

    private fun startCarNavigation(
        origin: RouteSlot,
        dest: RouteSlot,
        mode: CarRouteMode = lastSelectedCarRouteMode
    ) {
        lastSelectedCarRouteMode = mode
        carDistanceJob?.cancel()
        carRouteDistanceState.value = null
        val actualOrigin = resolveActualOrigin(origin)

        if (!MapNavigationHelper.isValidLatLon(actualOrigin.latitude, actualOrigin.longitude) ||
            !MapNavigationHelper.isValidLatLon(dest.latitude, dest.longitude)
        ) {
            Toast.makeText(this, "إحداثيات غير صالحة", Toast.LENGTH_SHORT).show()
            return
        }

        val engine = brouterEngine
        if (engine == null) {
            Toast.makeText(this, "محرك المسارات جاري التهيئة، يرجى المحاولة مرة أخرى", Toast.LENGTH_SHORT).show()
            return
        }

        isCalculatingRouteState.value = true
        lifecycleScope.launch {
            try {
                val routeResult = if (mode == CarRouteMode.CLOSEST_PAVED &&
                    cachedCarRouteResult != null &&
                    doubleEquals(cachedCarRouteOrigin?.latitude, actualOrigin.latitude) &&
                    doubleEquals(cachedCarRouteOrigin?.longitude, actualOrigin.longitude) &&
                    doubleEquals(cachedCarRouteDest?.latitude, dest.latitude) &&
                    doubleEquals(cachedCarRouteDest?.longitude, dest.longitude)
                ) {
                    val cached = cachedCarRouteResult!!
                    cachedCarRouteOrigin = null
                    cachedCarRouteDest = null
                    cachedCarRouteResult = null
                    cached
                } else {
                    val originLatLng = com.alruwaili.garminalbarr.map.routing.LatLng(actualOrigin.latitude, actualOrigin.longitude)
                    val destLatLng = com.alruwaili.garminalbarr.map.routing.LatLng(dest.latitude, dest.longitude)
                    engine.calculateRoute(originLatLng, destLatLng, mode)
                }

                val originGeo = GeoPoint(actualOrigin.latitude, actualOrigin.longitude)
                val destGeo = GeoPoint(dest.latitude, dest.longitude)
                val started = osmdroidFragment?.startCarRouteNavigation(
                    routeResult = routeResult,
                    startGeo = originGeo,
                    destGeo = destGeo,
                    destName = dest.title,
                    isOriginCurrentLocation = origin.isCurrentLocation
                ) ?: false

                if (!started) {
                    Toast.makeText(this@DesertMapActivity, "فشل بدء الملاحة", Toast.LENGTH_SHORT).show()
                } else {
                    hasActiveRouteState.value = true
                    isCarRouteState.value = !routeResult.isFallbackDirect
                    activeOriginSlotState.value = actualOrigin
                    activeDestSlotState.value = dest
                    activeRouteNameState.value = dest.title
                    remainingMetersState.value = routeResult.distanceMeters
                    bearingState.floatValue = NavigationMath.bearingDeg(
                        actualOrigin.latitude, actualOrigin.longitude,
                        dest.latitude, dest.longitude
                    )
                    selectedPoi.value = null
                    activePickerSlotState.value = null

                    if (routeResult.warningMessage != null) {
                        Toast.makeText(this@DesertMapActivity, routeResult.warningMessage, Toast.LENGTH_SHORT).show()
                    } else {
                        val modeLabel = if (mode == CarRouteMode.CLOSEST_PAVED) "أقرب وصول" else "أقصر مسار إجمالي"
                        Toast.makeText(this@DesertMapActivity, "تم حساب مسار السيارة ($modeLabel) بنجاح", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@DesertMapActivity, "خطأ في حساب المسار: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isCalculatingRouteState.value = false
            }
        }
    }

    private fun preCalculateCarRouteDistance(origin: RouteSlot, dest: RouteSlot) {
        carDistanceJob?.cancel()
        carRouteDistanceState.value = null
        cachedCarRouteOrigin = null
        cachedCarRouteDest = null
        cachedCarRouteResult = null
        val engine = brouterEngine ?: return
        carDistanceJob = lifecycleScope.launch {
            delay(800)
            try {
                val actualOrigin = resolveActualOrigin(origin)
                if (!MapNavigationHelper.isValidLatLon(actualOrigin.latitude, actualOrigin.longitude) ||
                    !MapNavigationHelper.isValidLatLon(dest.latitude, dest.longitude)
                ) return@launch
                val originLatLng = com.alruwaili.garminalbarr.map.routing.LatLng(actualOrigin.latitude, actualOrigin.longitude)
                val destLatLng = com.alruwaili.garminalbarr.map.routing.LatLng(dest.latitude, dest.longitude)
                val result = engine.calculateRoute(originLatLng, destLatLng)
                if (isActive) {
                    if (!result.isFallbackDirect) {
                        carRouteDistanceState.value = result.distanceMeters / 1000.0
                    } else {
                        carRouteDistanceState.value = null
                    }
                    cachedCarRouteOrigin = actualOrigin
                    cachedCarRouteDest = dest
                    cachedCarRouteResult = result
                }
            } catch (e: Exception) {
                if (isActive) carRouteDistanceState.value = null
            }
        }
    }

    private fun toggleRouteMode() {
        val origin = activeOriginSlotState.value ?: return
        val dest = activeDestSlotState.value ?: return
        FeatureGatekeeper.guardAction(this) {
            val actualOrigin = resolveActualOrigin(origin)
            if (isCarRouteState.value) {
            // Switch to direct compass route
            val originGeo = GeoPoint(actualOrigin.latitude, actualOrigin.longitude)
            val destGeo = GeoPoint(dest.latitude, dest.longitude)
            osmdroidFragment?.startNavigation(
                startGeo = originGeo,
                destGeo = destGeo,
                destName = dest.title,
                isOriginCurrentLocation = origin.isCurrentLocation
            )
            isCarRouteState.value = false
            activeOriginSlotState.value = actualOrigin
            remainingMetersState.value = NavigationMath.distanceMeters(
                actualOrigin.latitude, actualOrigin.longitude,
                dest.latitude, dest.longitude
            )
            bearingState.floatValue = NavigationMath.bearingDeg(
                actualOrigin.latitude, actualOrigin.longitude,
                dest.latitude, dest.longitude
            )
            Toast.makeText(this, "تم التبديل إلى المسار المباشر (البوصلة)", Toast.LENGTH_SHORT).show()
        } else {
            // Switch to car route retaining last selected mode
            startCarNavigation(actualOrigin, dest, lastSelectedCarRouteMode)
        }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inAppUpdater = com.alruwaili.garminalbarr.update.InAppUpdater(this).also { it.check() }

        val hasTarget = (intent.hasExtra("latitude") && intent.hasExtra("longitude")) ||
                (intent.hasExtra("EXTRA_TARGET_LAT") && intent.hasExtra("EXTRA_TARGET_LON"))
        val rawLat = if (intent.hasExtra("EXTRA_TARGET_LAT")) {
            intent.getDoubleExtra("EXTRA_TARGET_LAT", Double.NaN)
        } else {
            intent.getDoubleExtra("latitude", Double.NaN)
        }
        val rawLon = if (intent.hasExtra("EXTRA_TARGET_LON")) {
            intent.getDoubleExtra("EXTRA_TARGET_LON", Double.NaN)
        } else {
            intent.getDoubleExtra("longitude", Double.NaN)
        }
        val targetLatitude = if (hasTarget && rawLat.isFinite() && rawLat in -90.0..90.0) rawLat else 0.0
        val targetLongitude = if (hasTarget && rawLon.isFinite() && rawLon in -180.0..180.0) rawLon else 0.0
        val targetTitle = intent.getStringExtra("EXTRA_TARGET_NAME") ?: intent.getStringExtra("title")

        WindowCompat.setDecorFitsSystemWindows(window, false)        // Request location permission if not granted
        ensureLocationPermission()

        // تذكير بالمتبقي من التجربة المجانية (بالساعة الموثوقة) مرة عند فتح الخريطة
        if (savedInstanceState == null) {
            val sub = com.alruwaili.garminalbarr.billing.SubscriptionManager.getInstance(this)
            if (!sub.hasVipAccess && !sub.isPurchased && sub.isTrialActive) {
                Toast.makeText(this, sub.trialRemainingText(), Toast.LENGTH_LONG).show()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    showSearchOverlay.value -> {
                        showSearchOverlay.value = false
                        searchViewModel.onQueryChanged("")
                    }
                    compassTargetState.value != null -> {
                        compassTargetState.value = null
                    }
                    showDownloadManager.value -> {
                        showDownloadManager.value = false
                    }
                    pickingSlotTypeState.value != null -> {
                        pickingSlotTypeState.value = null
                    }
                    selectedPoi.value != null -> {
                        selectedPoi.value = null
                        activePickerSlotState.value = null
                        carDistanceJob?.cancel()
                        carRouteDistanceState.value = null
                        if (!hasActiveRouteState.value) {
                            activeOriginSlotState.value = null
                            activeDestSlotState.value = null
                        }
                    }
                    else -> {
                        finishAffinity()
                    }
                }
            }
        })

        // Ensure BRouter assets are unpacked into getExternalFilesDir(null)/brouter/ asynchronously off the UI thread,
        // then construct the engine on IO as well (file I/O in constructor init block).
        lifecycleScope.launch(Dispatchers.IO) {
            MapFileManager.ensureBrouterAssets(applicationContext)
            brouterEngine = com.alruwaili.garminalbarr.map.routing.BRouterOfflineEngine(this@DesertMapActivity)
        }

        setContentView(R.layout.activity_osmdroid_map)

        if (savedInstanceState == null) {
            val fragment = OsmdroidMapFragment()
            val args = Bundle().apply {
                putDouble("latitude", targetLatitude)
                putDouble("longitude", targetLongitude)
                putString("title", targetTitle)
            }
            fragment.arguments = args
            supportFragmentManager.beginTransaction()
                .replace(R.id.map_container, fragment)
                .commit()
        }

        supportFragmentManager.executePendingTransactions()
        osmdroidFragment = supportFragmentManager.findFragmentById(R.id.map_container) as? OsmdroidMapFragment

        val diskDownloaded = MapFileManager.getDownloadedRegions(this)
        for (region in RegionRegistry.ALL_REGIONS) {
            if (region.id in diskDownloaded) {
                downloadStates[region.id] = DownloadState.DOWNLOADED
            } else {
                downloadStates[region.id] = DownloadState.NOT_DOWNLOADED
            }
            if (MapFileManager.isCountryRoutingDownloaded(this, region.id)) {
                routingStates[region.id] = DownloadState.DOWNLOADED
            } else {
                routingStates[region.id] = DownloadState.NOT_DOWNLOADED
            }
            if (MapFileManager.isTerrainDownloaded(this, region.id)) {
                terrainStates[region.id] = DownloadState.DOWNLOADED
            } else {
                terrainStates[region.id] = DownloadState.NOT_DOWNLOADED
            }
        }
        storageUsedBytes.value = MapFileManager.getStorageUsedBytes(this)
        routingStorageUsedBytes.value = MapFileManager.getRoutingStorageUsedBytes(this)
        terrainStorageUsedBytes.value = MapFileManager.getTerrainStorageUsedBytes(this)

        // Initialize active route state from persistence
        syncActiveRouteFromPersistence()

        // Handle incoming target coordinate if passed via intent
        handleIncomingTargetIntent(intent)

        // Collect live track recording snapshots from service
        lifecycleScope.launch {
            TrackRecordingService.recordingSnapshot.collect { snapshot ->
                val prevStatus = recordingSnapshotState.value.status
                recordingSnapshotState.value = snapshot

                if (snapshot.status == RecordingStatus.RECORDING && snapshot.latestPoint != null) {
                    val pt = snapshot.latestPoint
                    if (pt.timestampMillis != lastRenderedPointTimestamp) {
                        lastRenderedPointTimestamp = pt.timestampMillis
                        osmdroidFragment?.appendTrackPoint(GeoPoint(pt.latitude, pt.longitude))
                    }
                }

                if (prevStatus == RecordingStatus.IDLE && snapshot.status == RecordingStatus.RECORDING) {
                    val existing = TrackRecordingService.activePoints.map { GeoPoint(it.latitude, it.longitude) }
                    if (existing.isNotEmpty()) {
                        lastRenderedPointTimestamp = TrackRecordingService.activePoints.lastOrNull()?.timestampMillis ?: 0L
                        osmdroidFragment?.restoreTrackPoints(existing, activeTrackColorHex)
                    }
                }
            }
        }

        // Collect "راصد الشبكة" running state from its service, independent of the track recorder.
        lifecycleScope.launch {
            NetworkSpotterService.isActive.collect { active ->
                isNetworkSpotterActiveState.value = active
            }
        }

        // "العقل الصحراوي الذكي" reads the same already-loaded app-wide locations cache
        // used by landmark search (SharedLocationViewModel.globalLocationsCache) — no
        // separate DB query of its own.
        lifecycleScope.launch {
            (application as MyApplication).sharedLocationViewModel.globalLocationsCache.collect { state ->
                if (state is CacheState.Ready) {
                    desertMindLocationsState.value = state.locations
                }
            }
        }

        val composeOverlay = findViewById<androidx.compose.ui.platform.ComposeView>(R.id.compose_overlay_container)
        composeOverlay.setViewCompositionStrategy(
            androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeOverlay.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                val region = pendingRegion.value
            val isManagerOpen = showDownloadManager.value
            val currentPoi = selectedPoi.value
            val scaleText = scaleTextState.value
            val hasRoute = hasActiveRouteState.value
            // Do NOT read azimuthState.floatValue here: it would recompose this entire
            // Box (cards + dock + bottom sheet) at sensor rate. Pass the State down so
            // only DockCompassButton recomposes (~15 Hz), Canvas stays VSync-gated.
            // HUD reads only 1 Hz remaining-distance state — cheap.
            val pickingSlotType = pickingSlotTypeState.value
            val activeRouteName = activeRouteNameState.value
            val remainingMeters = remainingMetersState.value
            val recSnapshot = recordingSnapshotState.value
            val isSpotterActive = isNetworkSpotterActiveState.value
            // ترتيب ثابت لعمود الأزرار الدائرية على يمين الشاشة: البوصلة (عند وجود مسار
            // نشط فقط) ← راصد الشبكة ← المساعد الرقمي ← الأدوات. عند ظهور شريط الوجهة
            // والمسافة أعلى الشاشة (hasRoute) يُزاح العمود بالكامل للأسفل بمقدار ثابت حتى
            // لا يتراكب معه، ومع زر البوصلة الصغير الذي يظهر فوقه فقط في تلك الحالة.
            val rightColumnCompassTop = 136.dp
            val rightColumnNetworkSpotterTop = if (hasRoute) 200.dp else 48.dp
            val rightColumnDigitalAssistantTop = if (hasRoute) 264.dp else 112.dp
            val rightColumnToolsTop = if (hasRoute) 328.dp else 176.dp
            // إصلاح 2026-11-26: شاشة "الرادار الصحراوي" ملء الشاشة كانت تُرسم في الشجرة قبل
            // شريط راصد الشبكة العلوي وزر/لوحة تسجيل الأثر، فكانا يظهران فوقها (ترتيب Z في
            // Compose يتبع ترتيب الكود، لا الظهور البصري فقط) ويغطيان شريط "ظهر حتى الآن X من
            // Y موقعًا" وعنوان الشاشة. الإصلاح: إخفاء هذه العناصر العائمة صراحةً أثناء فتح
            // الرادار كامل الشاشة، بدل الاعتماد على ترتيب الرسم فقط.
            val isRadarOpen = activeDesertRadarKmState.value != null

            if (isManagerOpen) {
                DownloadManagerScreen(
                    regions = RegionRegistry.ALL_REGIONS,
                    downloadStates = downloadStates.toMap(),
                    downloadProgress = downloadProgress.toMap(),
                    routingStates = routingStates.toMap(),
                    routingProgress = routingProgress.toMap(),
                    terrainStates = terrainStates.toMap(),
                    terrainProgress = terrainProgress.toMap(),
                    storageUsedBytes = storageUsedBytes.value,
                    routingStorageUsedBytes = routingStorageUsedBytes.value,
                    terrainStorageUsedBytes = terrainStorageUsedBytes.value,
                    onDownload = { startDownload(it) },
                    onDelete = { deleteRegion(it) },
                    onDownloadRouting = { startRoutingDownload(it) },
                    onDeleteRouting = { deleteRouting(it) },
                    onDownloadTerrain = { startTerrainDownload(it) },
                    onDeleteTerrain = { deleteTerrain(it) },
                    onBack = { showDownloadManager.value = false }
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (region != null) {
                        val isMapDownloaded = downloadStates[region.id] == DownloadState.DOWNLOADED
                        val isRoutingDownloaded = routingStates[region.id] == DownloadState.DOWNLOADED
                        val isDownloading = downloadStates[region.id] == DownloadState.DOWNLOADING || routingStates[region.id] == DownloadState.DOWNLOADING
                        val currentProgress = when {
                            downloadStates[region.id] == DownloadState.DOWNLOADING && routingStates[region.id] == DownloadState.DOWNLOADING -> {
                                ((downloadProgress[region.id] ?: 0f) + (routingProgress[region.id] ?: 0f)) / 2f
                            }
                            downloadStates[region.id] == DownloadState.DOWNLOADING -> downloadProgress[region.id]
                            routingStates[region.id] == DownloadState.DOWNLOADING -> routingProgress[region.id]
                            else -> null
                        }
                        val currentDownloadState = if (isDownloading) {
                            DownloadState.DOWNLOADING
                        } else if (isMapDownloaded && (region.routingSegments.isEmpty() || isRoutingDownloaded)) {
                            DownloadState.DOWNLOADED
                        } else {
                            DownloadState.NOT_DOWNLOADED
                        }

                        ContextualDownloadCard(
                            region = region,
                            isMapDownloaded = isMapDownloaded,
                            isRoutingDownloaded = isRoutingDownloaded,
                            downloadState = currentDownloadState,
                            downloadProgress = currentProgress,
                            downloadTitle = downloadTitleState.value,
                            onDownload = { r, dlMap, dlRoute ->
                                handleModularDownload(r, dlMap, dlRoute)
                            },
                            onDismiss = { pendingRegion.value = null },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // سكريم شفاف لإغلاق قائمة "الأدوات" عند الضغط خارجها — يُعلَن هنا، قبل
                    // MapControlsOverlay (أزرار التكبير/التصغير ضمنها)، كي تبقى أزرار التكبير
                    // فوقه في ترتيب الرسم فتستقبل ضغطاتها هي أولاً، بدل أن يبتلعها هذا السكريم
                    // كما كان يحدث مع DropdownMenu/Popup القياسية (نافذة منفصلة تبتلع أول ضغطة
                    // خارجها في أي مكان بالشاشة بصرف النظر عن ترتيب الكود) — إصلاح المهمة #150.
                    if (showToolsMenuState.value) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) {
                                    showToolsMenuState.value = false
                                }
                        )
                    }

                    val isOriginUser = activeOriginSlotState.value?.isCurrentLocation != false
                    MapControlsOverlay(
                        azimuthState = azimuthState,
                        scaleText = scaleText,
                        onCompassClick = {
                            val newMode = osmdroidFragment?.toggleOrientationMode()
                            isCourseUpState.value = (newMode == OsmdroidMapFragment.MapOrientationMode.COURSE_UP)
                        },
                        onZoomIn = { osmdroidFragment?.zoomIn() },
                        onZoomOut = { osmdroidFragment?.zoomOut() },
                        onGpsClick = {
                            if (hasRoute && isOriginUser) {
                                osmdroidFragment?.recenterDrivingCamera()
                            } else {
                                val userLoc = osmdroidFragment?.getCurrentUserLocation()
                                if (userLoc != null) {
                                    osmdroidFragment?.animateToLocation(userLoc, 14.0, 500L)
                                }
                            }
                        },
                        onSavedClick = {
                            startActivity(Intent(this@DesertMapActivity, FavoritesActivity::class.java))
                        },
                        onMyLocationsClick = {
                            startActivity(Intent(this@DesertMapActivity, com.alruwaili.garminalbarr.MyLocationsActivity::class.java))
                        },
                        onMenuClick = {
                            storageUsedBytes.value = MapFileManager.getStorageUsedBytes(this@DesertMapActivity)
                            routingStorageUsedBytes.value = MapFileManager.getRoutingStorageUsedBytes(this@DesertMapActivity)
                            showDownloadManager.value = true
                        },
                        onSearchClick = {
                            showSearchOverlay.value = true
                        },
                        isRecenterVisible = hasRoute && isOriginUser && isAutoFollowSuspendedState.value,
                        onRecenterClick = {
                            osmdroidFragment?.recenterDrivingCamera()
                        },
                        isCourseUp = isCourseUpState.value,
                        onOrientationToggle = {
                            val newMode = osmdroidFragment?.toggleOrientationMode()
                            isCourseUpState.value = (newMode == OsmdroidMapFragment.MapOrientationMode.COURSE_UP)
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Unified floating Navigation HUD (distance-only subtitle)
                    if (hasRoute && pickingSlotType == null && activeRouteName != null) {
                        NavigationHud(
                            state = ActiveRouteUiState(
                                targetName = activeRouteName,
                                remainingMeters = remainingMeters,
                                isCarRoute = isCarRouteState.value,
                                isCalculatingRoute = isCalculatingRouteState.value
                            ),
                            onEdit = {
                                // Just re-open PoiBottomSheet — keep the current route alive
                                // until the user explicitly deletes it or picks a new route.
                                val destSlot = activeDestSlotState.value
                                if (destSlot != null) {
                                    selectedPoi.value = PoiDetails(
                                        name = destSlot.title,
                                        category = "user_location",
                                        latitude = destSlot.latitude,
                                        longitude = destSlot.longitude
                                    )
                                    activePickerSlotState.value = null
                                }
                            },
                            onCancel = {
                                osmdroidFragment?.clearRoute()
                                osmdroidFragment?.clearBacktrack()
                                hasActiveRouteState.value = false
                                isCarRouteState.value = false
                                isCalculatingRouteState.value = false
                                activeRouteNameState.value = null
                                remainingMetersState.value = null
                                bearingState.floatValue = Float.NaN
                                activeOriginSlotState.value = null
                                activeDestSlotState.value = null
                                Toast.makeText(this@DesertMapActivity, "تم حذف المسار", Toast.LENGTH_SHORT).show()
                            },
                            onToggleRouteMode = {
                                toggleRouteMode()
                            },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 48.dp, start = 16.dp, end = 16.dp)
                                .fillMaxWidth()
                                .widthIn(max = 440.dp)
                        )

                        // Floating Mini Target Compass (below HUD, Top-Right physical alignment)
                        val destSlot = activeDestSlotState.value
                        if (destSlot != null) {
                            FloatingMiniTargetCompass(
                                azimuthState = azimuthState,
                                bearingState = bearingState,
                                activeDestState = activeDestSlotState,
                                activeOriginState = activeOriginSlotState,
                                locationProvider = { osmdroidFragment?.getCurrentUserLocation() },
                                onClick = {
                                    FeatureGatekeeper.guardAction(this@DesertMapActivity) {
                                        val userLoc = osmdroidFragment?.getCurrentUserLocation()
                                        val uLat = userLoc?.latitude ?: activeOriginSlotState.value?.latitude ?: destSlot.latitude
                                        val uLon = userLoc?.longitude ?: activeOriginSlotState.value?.longitude ?: destSlot.longitude
                                        compassTargetState.value = CompassTarget(
                                            slot = destSlot,
                                            userLat = uLat,
                                            userLon = uLon
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .align(AbsoluteAlignment.TopRight)
                                    .absolutePadding(top = rightColumnCompassTop, right = 16.dp)
                            )
                        }
                    }

                    // عمود الأزرار الدائرية الثابت على يمين الشاشة. الترتيب من الأعلى للأسفل
                    // ثابت دائمًا: البوصلة (فوق، عند وجود مسار نشط فقط) ← راصد الشبكة ←
                    // المساعد الرقمي ← الأدوات. عندما يظهر شريط الوجهة/المسافة أعلى الشاشة
                    // (hasRoute)، يُزاح العمود بالكامل للأسفل كي لا يتراكب معه أو مع زر
                    // البوصلة الصغير أعلاه؛ وإلا يبدأ العمود من أعلى الشاشة مباشرة.
                    if (pickingSlotType == null && !isRadarOpen) {
                        // "راصد الشبكة" (Network Spotter) floating toggle button.
                        NetworkSpotterToggleButton(
                            isActive = isSpotterActive,
                            onClick = { toggleNetworkSpotter() },
                            modifier = Modifier
                                .align(AbsoluteAlignment.TopRight)
                                .absolutePadding(top = rightColumnNetworkSpotterTop, right = 16.dp)
                        )

                        // "المساعد الرقمي" — زر دائري مستقل بين راصد الشبكة والأدوات، يفتح
                        // شاشة HUD رقمية كاملة الشاشة (السرعة/الاتجاه/المسافات/بوصلة رقمية/
                        // أقرب المواقع/تحليل التضاريس).
                        Surface(
                            shape = CircleShape,
                            color = Color(0xE61F1A16),
                            border = BorderStroke(1.dp, Color(0xFFDDA01E)),
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .align(AbsoluteAlignment.TopRight)
                                .absolutePadding(top = rightColumnDigitalAssistantTop, right = 16.dp)
                                .size(48.dp)
                                .clickable {
                                    FeatureGatekeeper.guardAction(this@DesertMapActivity) {
                                        showDigitalAssistantState.value = true
                                    }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = "المساعد الرقمي",
                                    tint = Color(0xFFDDA01E),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        // "الأدوات" (Tools) — single floating button, directly below the
                        // المساعد الرقمي button, gathering every new feature added this
                        // session (العقل الصحراوي الذكي، الرادار الصحراوي، وما يُضاف لاحقًا)
                        // under one menu instead of a separate icon per feature, per his
                        // explicit instruction.
                        Box(
                            modifier = Modifier
                                .align(AbsoluteAlignment.TopRight)
                                .absolutePadding(top = rightColumnToolsTop, right = 16.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.End) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xE61F1A16),
                                    border = BorderStroke(1.dp, Color(0xFFDDA01E)),
                                    shadowElevation = 6.dp,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clickable {
                                            FeatureGatekeeper.guardAction(this@DesertMapActivity) {
                                                showToolsMenuState.value = true
                                            }
                                        }
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        Icon(
                                            imageVector = Icons.Default.Build,
                                            contentDescription = "الأدوات",
                                            tint = Color(0xFFDDA01E),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }

                                // تصميم صحراوي موحّد (خلفية داكنة + نص أخضر زاهٍ + زر خروج أسفل
                                // القائمة). لوحة يدوية (Surface عادية) بدل DropdownMenu/Popup
                                // القياسية — إصلاح المهمة #150: Popup يفتح نافذة Android منفصلة
                                // تبتلع أول ضغطة خارجها في كل الشاشة (حتى فوق أزرار التكبير/
                                // التصغير البعيدة)، فكانت ضغطة واحدة تُغلق القائمة فقط دون أن
                                // تصل أبدًا لزر +/- تحتها. هذه اللوحة عنصر Compose عادي ضمن نفس
                                // النافذة، فلا تبتلع شيئًا خارج حدودها المرئية الفعلية.
                                val toolsItemGreen = Color(0xFF39FF6A)
                                if (showToolsMenuState.value) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = Color(0xFF1F1A16),
                                        border = BorderStroke(1.dp, Color(0xFFDDA01E)),
                                        shadowElevation = 10.dp,
                                        modifier = Modifier.width(230.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                            Text(
                                                text = "العقل الصحراوي الذكي",
                                                color = toolsItemGreen,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        showToolsMenuState.value = false
                                                        showDesertMindDialogState.value = true
                                                    }
                                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                            )
                                            Text(
                                                text = "الرادار الصحراوي",
                                                color = toolsItemGreen,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        showToolsMenuState.value = false
                                                        showDesertRadarPickerState.value = true
                                                    }
                                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                            )
                                            // "الرؤية الصحراوية X-Ray" — تفتح الآن شاشة تمثيلية
                                            // متعددة المستويات بدل مجرد تبديل تشغيل/إيقاف.
                                            Text(
                                                text = "الرؤية الصحراوية X-Ray",
                                                color = toolsItemGreen,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        showToolsMenuState.value = false
                                                        showXRayExperienceState.value = true
                                                    }
                                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                            )
                                            androidx.compose.material3.Divider(color = Color(0xFF3D2B1F))
                                            Text(
                                                text = "خروج",
                                                color = Color(0xFFDDA01E),
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { showToolsMenuState.value = false }
                                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (showDesertMindDialogState.value) {
                        val userLocNow = osmdroidFragment?.getCurrentUserLocation()
                        val tripStart = TrackRecordingService.activePoints.firstOrNull()
                        DesertMindDialog(
                            currentLat = userLocNow?.latitude,
                            currentLon = userLocNow?.longitude,
                            allLocations = desertMindLocationsState.value,
                            brouterEngine = brouterEngine,
                            selectedPoi = selectedPoi.value,
                            azimuthDeg = azimuthState.floatValue,
                            tripStartLat = tripStart?.latitude,
                            tripStartLon = tripStart?.longitude,
                            onGoTo = { lat, lon, title ->
                                desertMindNavigateTo(lat, lon, title)
                                showDesertMindDialogState.value = false
                            },
                            onDismiss = { showDesertMindDialogState.value = false }
                        )
                    }

                    if (showDesertRadarPickerState.value) {
                        DesertRadarRadiusDialog(
                            onStart = { radiusKm ->
                                showDesertRadarPickerState.value = false
                                // يُوسِّط الخريطة الحية على موقع المستخدم أولاً كي تظهر
                                // بشكل صحيح داخل الدائرة الرادارية المقصوصة فوقها مباشرة
                                // (الشاشة الجديدة تعرض الخريطة الفعلية خلفها، لا خلفية مجردة).
                                val userLocNow = osmdroidFragment?.getCurrentUserLocation()
                                if (userLocNow != null) {
                                    osmdroidFragment?.animateToLocation(userLocNow, 13.0, 300L)
                                }
                                activeDesertRadarKmState.value = radiusKm
                            },
                            onDismiss = { showDesertRadarPickerState.value = false }
                        )
                    }

                    activeDesertRadarKmState.value?.let { radiusKm ->
                        val userLocNow = osmdroidFragment?.getCurrentUserLocation()
                        if (userLocNow == null) {
                            LaunchedEffect(radiusKm) {
                                Toast.makeText(this@DesertMapActivity, "يتعذّر تحديد موقعك الحالي الآن (GPS غير متاح بعد)", Toast.LENGTH_SHORT).show()
                                activeDesertRadarKmState.value = null
                            }
                        } else {
                            val radiusMeters = radiusKm * 1000.0
                            val radarTargets = DesertRadarEngine.locationsWithinRadius(
                                userLocNow.latitude, userLocNow.longitude,
                                desertMindLocationsState.value, radiusMeters
                            )
                            DesertRadarSweepOverlay(
                                targets = radarTargets,
                                radiusMeters = radiusMeters,
                                onGoTo = { lat, lon, title ->
                                    desertMindNavigateTo(lat, lon, title)
                                    activeDesertRadarKmState.value = null
                                },
                                onClose = { activeDesertRadarKmState.value = null }
                            )
                        }
                    }

                    // Top-of-screen red-blink HUD: visible proof that "راصد الشبكة" is recording.
                    // مخفي أثناء فتح الرادار الصحراوي كامل الشاشة كي لا يتراكب مع عنوانها.
                    if (!isRadarOpen) {
                        NetworkSpotterHud(
                            isActive = isSpotterActive,
                            onStopClick = { toggleNetworkSpotter() },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 10.dp, start = 16.dp, end = 16.dp)
                        )
                    }

                    // Track Recording Overlay: Trigger Pill or Live HUD
                    if (pickingSlotType == null && !isRadarOpen) {
                        if (recSnapshot.status == RecordingStatus.IDLE) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = Color(0xE61F1A16),
                                border = BorderStroke(1.dp, Color(0xFFDDA01E)),
                                shadowElevation = 6.dp,
                                modifier = Modifier
                                    .align(AbsoluteAlignment.TopLeft)
                                    .absolutePadding(top = if (hasRoute) 126.dp else 48.dp, left = 16.dp)
                                    .clickable {
                                        FeatureGatekeeper.guardAction(this@DesertMapActivity) {
                                            startTrackRecording()
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFE53935))
                                    )
                                    Text(
                                        text = "تسجيل الأثر",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        } else {
                            TrackRecordingHud(
                                snapshot = recSnapshot,
                                onPause = { TrackRecordingService.pause(this@DesertMapActivity) },
                                onResume = { TrackRecordingService.resume(this@DesertMapActivity) },
                                onSaveRequest = {
                                    TrackRecordingService.pause(this@DesertMapActivity)
                                    showSaveTrackDialog.value = true
                                },
                                onDiscardRequest = { showDiscardTrackDialog.value = true },
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = if (hasRoute) 124.dp else 48.dp, start = 16.dp, end = 16.dp)
                            )
                        }
                    }

                    // Top Map-Picking Banner
                    if (pickingSlotType != null) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = Color(0xFF1F1A16),
                            border = BorderStroke(1.5.dp, Color(0xFFDDA01E)),
                            shadowElevation = 8.dp,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 48.dp, start = 16.dp, end = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Place,
                                    contentDescription = null,
                                    tint = Color(0xFFDDA01E),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (pickingSlotType == SlotType.ORIGIN) "اضغط على الخريطة لتحديد نقطة الانطلاق" else "اضغط على الخريطة لتحديد نقطة الوصول",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                TextButton(
                                    onClick = {
                                        pickingSlotTypeState.value = null
                                        if (selectedPoi.value == null && currentPoi != null) {
                                            selectedPoi.value = currentPoi
                                        }
                                    }
                                ) {
                                    Text(
                                        text = "إلغاء",
                                        color = Color(0xFFE53935),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Dual-Point Route BottomSheet
                    val originSlot = activeOriginSlotState.value ?: run {
                        val userLoc = osmdroidFragment?.getCurrentUserLocation()
                        val uLat = userLoc?.latitude ?: OsmdroidMapFragment.DEFAULT_CENTER.latitude
                        val uLon = userLoc?.longitude ?: OsmdroidMapFragment.DEFAULT_CENTER.longitude
                        RouteSlot("موقعي الحالي", uLat, uLon, isCurrentLocation = true)
                    }
                    val destSlot = activeDestSlotState.value ?: if (currentPoi != null) {
                        RouteSlot(currentPoi.name, currentPoi.latitude, currentPoi.longitude, isCurrentLocation = false)
                    } else {
                        RouteSlot("نقطة وصول", 0.0, 0.0, isCurrentLocation = false)
                    }

                    // Pre-calculate car route distance when POI, origin, or destination changes
                    androidx.compose.runtime.LaunchedEffect(currentPoi, originSlot, destSlot) {
                        if (currentPoi != null && pickingSlotType == null) {
                            preCalculateCarRouteDistance(originSlot, destSlot)
                        } else {
                            carDistanceJob?.cancel()
                            carRouteDistanceState.value = null
                        }
                    }

                    if (currentPoi != null && pickingSlotType == null) {
                        val userLoc = osmdroidFragment?.getCurrentUserLocation()
                        val userLat = userLoc?.latitude ?: OsmdroidMapFragment.DEFAULT_CENTER.latitude
                        val userLon = userLoc?.longitude ?: OsmdroidMapFragment.DEFAULT_CENTER.longitude

                        PoiBottomSheet(
                            poi = currentPoi,
                            originSlot = originSlot,
                            destSlot = destSlot,
                            activePickerSlot = activePickerSlotState.value,
                            onActivePickerSlotChanged = { activePickerSlotState.value = it },
                            onOriginChanged = { activeOriginSlotState.value = it },
                            onDestinationChanged = { activeDestSlotState.value = it },
                            onSwapSlots = {
                                val curOrigin = activeOriginSlotState.value ?: originSlot
                                val curDest = activeDestSlotState.value ?: destSlot
                                activeOriginSlotState.value = curDest
                                activeDestSlotState.value = curOrigin
                            },
                            userLat = userLat,
                            userLon = userLon,
                            azimuthState = azimuthState,
                            onOpenTargetCompass = { slot ->
                                FeatureGatekeeper.guardAction(this@DesertMapActivity) {
                                    compassTargetState.value = CompassTarget(
                                        slot = slot,
                                        userLat = userLat,
                                        userLon = userLon
                                    )
                                }
                            },
                            carRouteDistanceKm = carRouteDistanceState.value,
                            onSaveLocation = { title, lat, lon ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val entity = LocationEntity(
                                        title = title,
                                        latitude = lat,
                                        longitude = lon,
                                        isManual = true
                                    )
                                    try {
                                        RoomDBHelper.getInstance(applicationContext).locationDao.insert(entity)
                                    } catch (e: Exception) {
                                        android.util.Log.e("DesertMapActivity", "Failed to insert location to Room", e)
                                    }
                                    val app = application as? MyApplication
                                    app?.sharedLocationViewModel?.addManualLocation(entity)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@DesertMapActivity, "تم حفظ الموقع في مواقفي بنجاح", Toast.LENGTH_SHORT).show()
                                        osmdroidFragment?.refreshUserLocations()
                                    }
                                }
                            },
                            onDismiss = {
                                selectedPoi.value = null
                                activePickerSlotState.value = null
                                carDistanceJob?.cancel()
                                carRouteDistanceState.value = null
                                if (!hasActiveRouteState.value) {
                                    activeOriginSlotState.value = null
                                    activeDestSlotState.value = null
                                }
                            },
                            onPickFromMap = { slotType ->
                                activePickerSlotState.value = null
                                pickingSlotTypeState.value = slotType
                            },
                            onCompassNavigation = { origin, dest ->
                                FeatureGatekeeper.guardAction(this@DesertMapActivity) {
                                    val actualOrigin = resolveActualOrigin(origin)

                                    // Never route to the (0,0) placeholder or garbage coords:
                                    // that draws a line to the Gulf of Guinea + persists junk.
                                    if (!MapNavigationHelper.isValidLatLon(actualOrigin.latitude, actualOrigin.longitude) ||
                                        !MapNavigationHelper.isValidLatLon(dest.latitude, dest.longitude)
                                    ) {
                                        Toast.makeText(
                                            this@DesertMapActivity,
                                            "إحداثيات غير صالحة",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        val originGeo = GeoPoint(actualOrigin.latitude, actualOrigin.longitude)
                                        val destGeo = GeoPoint(dest.latitude, dest.longitude)
                                        val started = osmdroidFragment?.startNavigation(
                                            startGeo = originGeo,
                                            destGeo = destGeo,
                                            destName = dest.title,
                                            isOriginCurrentLocation = origin.isCurrentLocation
                                        ) ?: false
                                        if (!started) {
                                            Toast.makeText(
                                                this@DesertMapActivity,
                                                "إحداثيات غير صالحة",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            hasActiveRouteState.value = true
                                            isCarRouteState.value = false
                                            // Retain slots for HUD Edit (do NOT null them).
                                            activeOriginSlotState.value = actualOrigin
                                            activeDestSlotState.value = dest
                                            activeRouteNameState.value = dest.title
                                            remainingMetersState.value = NavigationMath.distanceMeters(
                                                actualOrigin.latitude, actualOrigin.longitude,
                                                dest.latitude, dest.longitude
                                            )
                                            bearingState.floatValue = NavigationMath.bearingDeg(
                                                actualOrigin.latitude, actualOrigin.longitude,
                                                dest.latitude, dest.longitude
                                            )
                                            selectedPoi.value = null
                                            activePickerSlotState.value = null
                                        }
                                    }
                                }
                            },
                            onCarRouteNavigation = { origin, dest ->
                                FeatureGatekeeper.guardAction(this@DesertMapActivity) {
                                    pendingCarRouteOrigin.value = origin
                                    pendingCarRouteDest.value = dest
                                    showCarRouteOptionsDialog.value = true
                                }
                            },
                            onShareLocation = { title, lat, lon ->
                                FeatureGatekeeper.guardAction(this@DesertMapActivity) {
                                    LocationShareHelper.shareLocation(this@DesertMapActivity, title, lat, lon)
                                }
                            }
                        )
                    }
                    // Full-screen target compass: edge-to-edge over map/HUD/sheet.
                    // Reads shared azimuth/bearing state — no new sensors.
                    compassTargetState.value?.let { target ->
                        TargetCompassOverlay(
                            target = target,
                            azimuthState = azimuthState,
                            bearingState = bearingState,
                            activeDest = activeDestSlotState.value,
                            remainingMeters = remainingMetersState.value,
                            locationProvider = { osmdroidFragment?.getCurrentUserLocation() },
                            onClose = { compassTargetState.value = null }
                        )
                    }

                    // "المساعد الرقمي" — شاشة HUD كاملة الشاشة فوق كل شيء آخر عند فتحها.
                    if (showDigitalAssistantState.value) {
                        val huLoc = osmdroidFragment?.getCurrentUserLocation()
                        LaunchedEffect(showDigitalAssistantState.value, huLoc?.latitude, huLoc?.longitude) {
                            if (huLoc != null) {
                                digitalAssistantElevationState.value = withContext(Dispatchers.IO) {
                                    TerrainElevationLookup.currentElevationMeters(
                                        this@DesertMapActivity, huLoc.latitude, huLoc.longitude
                                    )
                                }
                            } else {
                                digitalAssistantElevationState.value = null
                            }
                        }
                        val snapshot = recordingSnapshotState.value
                        DigitalAssistantScreen(
                            speedKmh = osmdroidFragment?.getCurrentSpeedKmh(),
                            azimuthDeg = azimuthState.value,
                            hasActiveRoute = hasActiveRouteState.value,
                            destinationName = activeDestSlotState.value?.title,
                            remainingMeters = remainingMetersState.value,
                            isRecordingTrack = snapshot.status == RecordingStatus.RECORDING,
                            traveledMeters = snapshot.totalDistanceMeters,
                            elevationMeters = digitalAssistantElevationState.value,
                            nearbyLocations = desertMindLocationsState.value,
                            userLat = huLoc?.latitude,
                            userLon = huLoc?.longitude,
                            onClose = { showDigitalAssistantState.value = false },
                            onComingSoon = {
                                Toast.makeText(this@DesertMapActivity, "قريبًا إن شاء الله", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }

                    // "الرؤية الصحراوية X-Ray" — إعادة تصميم 2026-11-26: بدل تبديل تشغيل/
                    // إيقاف بسيط، 3 مستويات عرض حقيقية (ليست مشهدًا ثلاثي الأبعاد — اختار هو
                    // صراحة "طبقة تحليلية على الخريطة المسطحة الحالية" من بين الخيارات
                    // المطروحة عليه): كل مستوى يُفعّل طبقة X-Ray الحقيقية (ارتفاع+انحدار من
                    // DEM) ثم يحرك كاميرا الخريطة فعليًا لنطاق مقارب لحجمه المطلوب. المستويان
                    // الأوسع يعتمدان على مسار فعلي نشط (نقطة الانطلاق/الوجهة الحقيقيتين)؛ بلا
                    // مسار نشط يُعرض حول موقع المستخدم الحالي مع ملاحظة صادقة بذلك، لا بيانات
                    // ملفّقة عن رحلة غير موجودة.
                    if (showXRayExperienceState.value) {
                        val xrUserLoc = osmdroidFragment?.getCurrentUserLocation()
                        val xrOrigin = activeOriginSlotState.value
                        val xrDest = activeDestSlotState.value
                        val xrHasRoute = hasActiveRouteState.value && xrOrigin != null && xrDest != null
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xE60D0905))
                        ) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(horizontal = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "الرؤية الصحراوية X-Ray",
                                    color = Color(0xFFD4A017),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "طبقة تحليلية حقيقية (ارتفاع وانحدار التضاريس) فوق الخريطة المسطحة الحالية — اختر نطاق العرض:",
                                    color = Color(0xFFF5E6C8),
                                    fontSize = 13.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(18.dp))

                                // مستوى قريب: ~20 كم² حول موقع المستخدم الحالي فعليًا.
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF2C2013),
                                    border = BorderStroke(1.dp, Color(0xFFD4A017)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            osmdroidFragment?.setTerrainXRayMode(true)
                                            if (xrUserLoc != null) {
                                                osmdroidFragment?.animateToLocation(xrUserLoc, zoomForSpanKm(4.5), 500L)
                                            }
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text("مستوى قريب", color = Color(0xFF39FF6A), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Text("تضاريس محيطة بموقعك الحالي (~20 كم²)", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))

                                // مستوى متوسط: ~100 كم² — يتمركز على منتصف المسافة بين المستخدم
                                // والوجهة الحقيقية إن وُجد مسار نشط، وإلا حول موقعه الحالي فقط.
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF2C2013),
                                    border = BorderStroke(1.dp, Color(0xFFD4A017)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            osmdroidFragment?.setTerrainXRayMode(true)
                                            val center = if (xrHasRoute && xrUserLoc != null) {
                                                GeoPoint((xrUserLoc.latitude + xrDest!!.latitude) / 2.0, (xrUserLoc.longitude + xrDest.longitude) / 2.0)
                                            } else xrUserLoc
                                            if (center != null) {
                                                osmdroidFragment?.animateToLocation(center, zoomForSpanKm(10.0), 500L)
                                            }
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text("مستوى متوسط", color = Color(0xFF39FF6A), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Text(
                                            if (xrHasRoute) "مسار رحلتك الحالية (~100 كم²)" else "حول موقعك الحالي — لا توجد وجهة نشطة",
                                            color = Color(0xFF9E9E9E),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))

                                // مستوى كامل: يحتاج فعليًا نقطتي انطلاق/وجهة حقيقيتين من مسار
                                // نشط. بلا مسار نشط لا تُلفَّق "رحلة" وهمية — يُعرض بدلاً منها
                                // نفس نطاق موقع المستخدم الحالي مع توضيح صريح لذلك.
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF2C2013),
                                    border = BorderStroke(1.dp, Color(0xFFD4A017)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            osmdroidFragment?.setTerrainXRayMode(true)
                                            if (xrHasRoute) {
                                                val spanKm = NavigationMath.distanceMeters(
                                                    xrOrigin!!.latitude, xrOrigin.longitude, xrDest!!.latitude, xrDest.longitude
                                                ) / 1000.0
                                                val center = GeoPoint((xrOrigin.latitude + xrDest.latitude) / 2.0, (xrOrigin.longitude + xrDest.longitude) / 2.0)
                                                osmdroidFragment?.animateToLocation(center, zoomForSpanKm(spanKm), 500L)
                                            } else if (xrUserLoc != null) {
                                                osmdroidFragment?.animateToLocation(xrUserLoc, zoomForSpanKm(4.5), 500L)
                                            }
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text("مستوى كامل", color = Color(0xFF39FF6A), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Text(
                                            if (xrHasRoute) "الرحلة كاملة من الانطلاق إلى الوجهة" else "لا توجد رحلة نشطة لعرضها كاملة",
                                            color = Color(0xFF9E9E9E),
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(20.dp))
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = Color(0xFF39FF6A),
                                    modifier = Modifier
                                        .clickable {
                                            osmdroidFragment?.setTerrainXRayMode(false)
                                            showXRayExperienceState.value = false
                                        }
                                ) {
                                    Text(
                                        text = "خروج",
                                        color = Color.Black,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Full-screen landmark search overlay
                    if (showSearchOverlay.value) {
                        val searchUserLoc = osmdroidFragment?.getCurrentUserLocation()
                        LandmarkSearchOverlay(
                            viewModel = searchViewModel,
                            userLat = searchUserLoc?.latitude,
                            userLon = searchUserLoc?.longitude,
                            onOpenCompass = { landmark ->
                                val lat = landmark.latitude
                                val lon = landmark.longitude
                                if (MapNavigationHelper.isValidLatLon(lat, lon) && searchUserLoc != null) {
                                    FeatureGatekeeper.guardAction(this@DesertMapActivity) {
                                        showSearchOverlay.value = false
                                        searchViewModel.onQueryChanged("")
                                        compassTargetState.value = CompassTarget(
                                            slot = RouteSlot(landmark.title ?: "موقع محدد", lat!!, lon!!, isCurrentLocation = false),
                                            userLat = searchUserLoc.latitude,
                                            userLon = searchUserLoc.longitude
                                        )
                                    }
                                }
                            },
                            onDismiss = {
                                showSearchOverlay.value = false
                                searchViewModel.onQueryChanged("")
                            },
                            onResultSelected = { landmark ->
                                FeatureGatekeeper.guardAction(this@DesertMapActivity) {
                                    showSearchOverlay.value = false
                                    searchViewModel.onQueryChanged("")
                                    val lat = landmark.latitude
                                    val lon = landmark.longitude
                                    if (!MapNavigationHelper.isValidLatLon(lat, lon)) {
                                        Toast.makeText(this@DesertMapActivity, "إحداثيات غير صالحة", Toast.LENGTH_SHORT).show()
                                        return@guardAction
                                    }
                                    val targetGeo = GeoPoint(lat!!, lon!!)
                                    osmdroidFragment?.getMapView()?.post {
                                        osmdroidFragment?.animateToLocation(targetGeo, 15.0, 800L)
                                    }
                                    val poi = PoiDetails(
                                        name = landmark.title ?: "موقع محدد",
                                        category = "user_location",
                                        latitude = lat,
                                        longitude = lon
                                    )
                                    val userLoc = osmdroidFragment?.getCurrentUserLocation()
                                    val userLat = userLoc?.latitude ?: OsmdroidMapFragment.DEFAULT_CENTER.latitude
                                    val userLon = userLoc?.longitude ?: OsmdroidMapFragment.DEFAULT_CENTER.longitude
                                    activeOriginSlotState.value = RouteSlot("موقعي الحالي", userLat, userLon, isCurrentLocation = true)
                                    activeDestSlotState.value = RouteSlot(poi.name, lat, lon, isCurrentLocation = false)
                                    activePickerSlotState.value = null
                                    selectedPoi.value = poi
                                }
                            }
                        )
                    }

                    // Save Track Dialog
                    if (showSaveTrackDialog.value) {
                        SaveTrackDialog(
                            initialDistanceMeters = recSnapshot.totalDistanceMeters,
                            initialDurationSeconds = recSnapshot.elapsedDurationSeconds,
                            onSave = { title, desc, colorHex ->
                                saveActiveTrack(title, desc, colorHex)
                                showSaveTrackDialog.value = false
                            },
                            onResumeRecording = {
                                TrackRecordingService.resume(this@DesertMapActivity)
                                showSaveTrackDialog.value = false
                            },
                            onDiscard = {
                                showSaveTrackDialog.value = false
                                showDiscardTrackDialog.value = true
                            },
                            onDismiss = {
                                showSaveTrackDialog.value = false
                            }
                        )
                    }

                    // Discard Track Dialog
                    if (showDiscardTrackDialog.value) {
                        DiscardTrackDialog(
                            onConfirmDiscard = {
                                discardActiveTrack()
                                showDiscardTrackDialog.value = false
                            },
                            onDismiss = {
                                showDiscardTrackDialog.value = false
                            }
                        )
                    }

                    // Car Route Options Dialog
                    if (showCarRouteOptionsDialog.value) {
                        val origin = pendingCarRouteOrigin.value
                        val dest = pendingCarRouteDest.value
                        CarRouteOptionsDialog(
                            precalculatedDistanceKm = carRouteDistanceState.value,
                            onSelectMode = { selectedMode ->
                                showCarRouteOptionsDialog.value = false
                                if (origin != null && dest != null) {
                                    startCarNavigation(origin, dest, selectedMode)
                                }
                                pendingCarRouteOrigin.value = null
                                pendingCarRouteDest.value = null
                            },
                            onDismiss = {
                                showCarRouteOptionsDialog.value = false
                                pendingCarRouteOrigin.value = null
                                pendingCarRouteDest.value = null
                            }
                        )
                    }
                }
            }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        inAppUpdater?.onResume()
        enforceEntitlementOnActiveRoute()
        isCourseUpState.value = (osmdroidFragment?.getOrientationMode() == OsmdroidMapFragment.MapOrientationMode.COURSE_UP)
        syncActiveRouteFromPersistence()
    }

    override fun onDestroy() {
        inAppUpdater?.release()
        inAppUpdater = null
        super.onDestroy()
    }

    private fun syncActiveRouteFromPersistence() {
        val hasRoute = osmdroidFragment?.hasSavedRoute() == true
        hasActiveRouteState.value = hasRoute
        if (hasRoute) {
            activeRouteNameState.value = osmdroidFragment?.getSavedDestName()
            val navPrefs = getSharedPreferences("desert_nav_prefs", Context.MODE_PRIVATE)
            isCarRouteState.value = navPrefs.getBoolean("active_route_is_car", false)

            val originLatBits = navPrefs.getLong("active_origin_lat", 0L)
            val originLonBits = navPrefs.getLong("active_origin_lon", 0L)
            val isOriginUser = navPrefs.getBoolean("active_route_is_origin_user", true)
            var originLat = Double.NaN
            var originLon = Double.NaN
            if (originLatBits != 0L && originLonBits != 0L) {
                originLat = java.lang.Double.longBitsToDouble(originLatBits)
                originLon = java.lang.Double.longBitsToDouble(originLonBits)
                activeOriginSlotState.value = RouteSlot(
                    title = if (isOriginUser) "موقعي الحالي" else "نقطة انطلاق",
                    latitude = originLat,
                    longitude = originLon,
                    isCurrentLocation = isOriginUser
                )
            }

            val destLatBits = navPrefs.getLong("active_dest_lat", 0L)
            val destLonBits = navPrefs.getLong("active_dest_lon", 0L)
            if (destLatBits != 0L && destLonBits != 0L) {
                val destLat = java.lang.Double.longBitsToDouble(destLatBits)
                val destLon = java.lang.Double.longBitsToDouble(destLonBits)
                val destName = navPrefs.getString("active_dest_name", null) ?: ""
                activeDestSlotState.value = RouteSlot(destName, destLat, destLon, isCurrentLocation = false)

                // Immediately initialize distance and bearing from persisted origin to destination
                if (originLat.isFinite() && originLon.isFinite() &&
                    (originLat != 0.0 || originLon != 0.0) &&
                    (destLat != 0.0 || destLon != 0.0)
                ) {
                    if (remainingMetersState.value == null) {
                        remainingMetersState.value = NavigationMath.distanceMeters(originLat, originLon, destLat, destLon)
                    }
                    bearingState.floatValue = NavigationMath.bearingDeg(originLat, originLon, destLat, destLon)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingTargetIntent(intent)
    }

    private fun handleIncomingTargetIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.getBooleanExtra(TrackRecordingService.EXTRA_OPEN_SAVE_TRACK, false)) {
            showSaveTrackDialog.value = true
        }

        if (intent.hasExtra("action_view_track_id")) {
            val trackId = intent.getLongExtra("action_view_track_id", -1L)
            if (trackId != -1L) {
                viewTrackOnMap(trackId)
            }
        }

        if (intent.hasExtra("action_backtrack_track_id")) {
            val trackId = intent.getLongExtra("action_backtrack_track_id", -1L)
            if (trackId != -1L) {
                FeatureGatekeeper.guardAction(this) {
                    startBacktrack(trackId)
                }
            }
        }

        val hasTarget = (intent.hasExtra("latitude") && intent.hasExtra("longitude")) ||
                (intent.hasExtra("EXTRA_TARGET_LAT") && intent.hasExtra("EXTRA_TARGET_LON"))
        if (!hasTarget) return

        val rawLat = if (intent.hasExtra("EXTRA_TARGET_LAT")) {
            intent.getDoubleExtra("EXTRA_TARGET_LAT", Double.NaN)
        } else {
            intent.getDoubleExtra("latitude", Double.NaN)
        }
        val rawLon = if (intent.hasExtra("EXTRA_TARGET_LON")) {
            intent.getDoubleExtra("EXTRA_TARGET_LON", Double.NaN)
        } else {
            intent.getDoubleExtra("longitude", Double.NaN)
        }
        val rawTitle = intent.getStringExtra("EXTRA_TARGET_NAME") ?: intent.getStringExtra("title")

        if (rawLat.isFinite() && rawLat in -90.0..90.0 && rawLon.isFinite() && rawLon in -180.0..180.0 && (rawLat != 0.0 || rawLon != 0.0)) {
            // New external target invalidates any active route: clear first so
            // the HUD (old dest) and bottom sheet (new dest) cannot disagree.
            if (hasActiveRouteState.value) {
                osmdroidFragment?.clearRoute()
                hasActiveRouteState.value = false
                isCarRouteState.value = false
                isCalculatingRouteState.value = false
                activeRouteNameState.value = null
                remainingMetersState.value = null
                bearingState.floatValue = Float.NaN
            }
            val targetGeo = GeoPoint(rawLat, rawLon)
            // Defer until MapView layout: animateTo before first layout is dropped
            // on cold starts from Favorites/Locations.
            osmdroidFragment?.getMapView()?.post {
                osmdroidFragment?.animateToLocation(targetGeo, 15.0, 800L)
            }
            val poi = PoiDetails(
                name = rawTitle ?: String.format(Locale.US, "%.5f, %.5f", rawLat, rawLon),
                category = "user_location",
                latitude = rawLat,
                longitude = rawLon
            )
            selectedPoi.value = poi
            activeDestSlotState.value = RouteSlot(poi.name, rawLat, rawLon, isCurrentLocation = false)

            // 2026-11-26: سهم الاتجاه في المفضلة/مواقعي يطلب فتح البوصلة الكاملة فورًا
            // نحو هذا الموقع، لا مجرد تمركز الخريطة عليه. الموقع الحالي للمستخدم (إن
            // وُجد) هو نفس المصدر الحي المستخدم في desertMindNavigateTo بالأسفل.
            if (intent.getBooleanExtra("EXTRA_OPEN_COMPASS", false)) {
                val origin = osmdroidFragment?.getCurrentUserLocation()
                val originLat = origin?.latitude ?: OsmdroidMapFragment.DEFAULT_CENTER.latitude
                val originLon = origin?.longitude ?: OsmdroidMapFragment.DEFAULT_CENTER.longitude
                compassTargetState.value = CompassTarget(
                    slot = RouteSlot(poi.name, rawLat, rawLon, isCurrentLocation = false),
                    userLat = originLat,
                    userLon = originLon
                )
            }
        }
    }

    /**
     * Shared "اذهب إلى" helper for "العقل الصحراوي الذكي" and "الرادار الصحراوي" — both
     * features only ever navigate from the user's current location to one named target
     * point, so they share this single implementation (mirrors the existing
     * onCompassNavigation path used by [PoiBottomSheet]) instead of duplicating it.
     */
    /**
     * تقدير تقريبي قياسي لمستوى تكبير خريطة الويب/أوسمدرويد الذي يُظهر نطاقًا بعرض [spanKm]
     * كم تقريبًا — يُستخدم فقط لتحريك الكاميرا بين مستويات "الرؤية الصحراوية X-Ray" الثلاثة
     * (قريب/متوسط/كامل)؛ ليس عرضًا ثلاثي الأبعاد حقيقيًا، فقط تقريب+تمركز على الخريطة
     * المسطحة الحالية، طبقًا لاختياره الصريح "طبقة تحليلية على الخريطة المسطحة الحالية".
     */
    private fun zoomForSpanKm(spanKm: Double): Double {
        val z = 14.0 - (ln(spanKm.coerceAtLeast(0.5)) / ln(2.0))
        return z.coerceIn(6.0, 15.0)
    }

    private fun desertMindNavigateTo(lat: Double, lon: Double, title: String) {
        FeatureGatekeeper.guardAction(this@DesertMapActivity) {
            val origin = osmdroidFragment?.getCurrentUserLocation()
            if (origin == null ||
                !MapNavigationHelper.isValidLatLon(origin.latitude, origin.longitude) ||
                !MapNavigationHelper.isValidLatLon(lat, lon)
            ) {
                Toast.makeText(this@DesertMapActivity, "إحداثيات غير صالحة", Toast.LENGTH_SHORT).show()
                return@guardAction
            }
            val originGeo = GeoPoint(origin.latitude, origin.longitude)
            val destGeo = GeoPoint(lat, lon)
            val started = osmdroidFragment?.startNavigation(
                startGeo = originGeo,
                destGeo = destGeo,
                destName = title,
                isOriginCurrentLocation = true
            ) ?: false
            if (started) {
                hasActiveRouteState.value = true
                isCarRouteState.value = false
                activeOriginSlotState.value = RouteSlot(
                    title = "موقعي الحالي",
                    latitude = origin.latitude,
                    longitude = origin.longitude,
                    isCurrentLocation = true
                )
                activeDestSlotState.value = RouteSlot(title = title, latitude = lat, longitude = lon)
                activeRouteNameState.value = title
                remainingMetersState.value = NavigationMath.distanceMeters(
                    origin.latitude, origin.longitude, lat, lon
                )
                bearingState.floatValue = NavigationMath.bearingDeg(
                    origin.latitude, origin.longitude, lat, lon
                )
                selectedPoi.value = null
            } else {
                Toast.makeText(this@DesertMapActivity, "فشل بدء الملاحة", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleNetworkSpotter() {
        if (isNetworkSpotterActiveState.value) {
            NetworkSpotterService.stop(this)
            Toast.makeText(this, "تم إيقاف راصد الشبكة", Toast.LENGTH_SHORT).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ensureLocationPermission()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST
                )
            }
        }

        NetworkSpotterService.start(this)
        Toast.makeText(this, "تم تفعيل راصد الشبكة، سيتم تسجيل نقطة عند فقدان الشبكة تلقائيًا", Toast.LENGTH_SHORT).show()
    }

    private fun startTrackRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ensureLocationPermission()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST
                )
            }
        }

        osmdroidFragment?.clearLiveTrack()
        lastRenderedPointTimestamp = 0L
        TrackRecordingService.start(this)
        Toast.makeText(this, "بدأ تسجيل مسار الرحلة", Toast.LENGTH_SHORT).show()
    }

    private fun saveActiveTrack(title: String, desc: String?, colorHex: String) {
        val snapshot = recordingSnapshotState.value
        val points = TrackRecordingService.activePoints.toList()
        if (points.isEmpty()) {
            Toast.makeText(this, "لا توجد نقاط مسجلة لحفظها", Toast.LENGTH_SHORT).show()
            TrackRecordingService.discard(this)
            osmdroidFragment?.clearLiveTrack()
            lastRenderedPointTimestamp = 0L
            return
        }

        val startTime = points.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        val endTime = points.lastOrNull()?.timestampMillis ?: System.currentTimeMillis()

        val track = TrackEntity(
            title = title.ifBlank { "مسار بدون عنوان" },
            description = desc,
            colorHex = colorHex,
            startTimeMillis = startTime,
            endTimeMillis = endTime,
            totalDistanceMeters = snapshot.totalDistanceMeters,
            durationSeconds = snapshot.elapsedDurationSeconds,
            maxSpeedMps = snapshot.maxSpeedMps,
            avgSpeedMps = snapshot.avgSpeedMps,
            minAltitudeMeters = snapshot.minAltitudeMeters,
            maxAltitudeMeters = snapshot.maxAltitudeMeters,
            isVisibleOnMap = true,
            pointsCount = points.size
        )

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                trackRepository.saveTrack(track, points)
                withContext(Dispatchers.Main) {
                    TrackRecordingService.stop(this@DesertMapActivity)
                    osmdroidFragment?.clearLiveTrack()
                    lastRenderedPointTimestamp = 0L
                    osmdroidFragment?.refreshVisibleTracks()
                    Toast.makeText(this@DesertMapActivity, "تم حفظ المسار بنجاح", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("DesertMapActivity", "Error saving track", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DesertMapActivity, "خطأ في حفظ المسار: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun discardActiveTrack() {
        TrackRecordingService.discard(this)
        osmdroidFragment?.clearLiveTrack()
        lastRenderedPointTimestamp = 0L
        Toast.makeText(this, "تم إلغاء وحذف مسار الرحلة", Toast.LENGTH_SHORT).show()
    }

    private fun startBacktrack(trackId: Long) {
        lifecycleScope.launch {
            try {
                val track = trackRepository.getTrack(trackId) ?: return@launch
                val points = trackRepository.getTrackPoints(trackId)
                if (points.size < 2) {
                    Toast.makeText(this@DesertMapActivity, "المسار لا يحتوي على نقاط كافية للعودة", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val reversed = points.reversed()
                val geoPoints = reversed.map { GeoPoint(it.latitude, it.longitude) }
                osmdroidFragment?.showBacktrack(geoPoints)

                val returnDest = points.first()
                val userLoc = osmdroidFragment?.getCurrentUserLocation()
                val userLat = userLoc?.latitude ?: reversed.first().latitude
                val userLon = userLoc?.longitude ?: reversed.first().longitude

                activeOriginSlotState.value = RouteSlot("موقعي الحالي", userLat, userLon, isCurrentLocation = true)
                activeDestSlotState.value = RouteSlot(
                    title = "بداية مسار: ${track.title}",
                    latitude = returnDest.latitude,
                    longitude = returnDest.longitude,
                    isCurrentLocation = false
                )

                val startGeo = GeoPoint(userLat, userLon)
                val destGeo = GeoPoint(returnDest.latitude, returnDest.longitude)
                osmdroidFragment?.startNavigation(
                    startGeo = startGeo,
                    destGeo = destGeo,
                    destName = "العودة: ${track.title}",
                    isOriginCurrentLocation = true
                )

                hasActiveRouteState.value = true
                isCarRouteState.value = false
                activeRouteNameState.value = "العودة: ${track.title}"
                remainingMetersState.value = NavigationMath.distanceMeters(userLat, userLon, returnDest.latitude, returnDest.longitude)
                bearingState.floatValue = NavigationMath.bearingDeg(userLat, userLon, returnDest.latitude, returnDest.longitude)

                Toast.makeText(this@DesertMapActivity, "بدء العودة على نفس الأثر: ${track.title}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("DesertMapActivity", "Error starting backtrack", e)
                Toast.makeText(this@DesertMapActivity, "خطأ في بدء العودة: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun viewTrackOnMap(trackId: Long) {
        lifecycleScope.launch {
            try {
                val track = trackRepository.getTrack(trackId) ?: return@launch
                val points = trackRepository.getTrackPoints(trackId)
                if (points.isEmpty()) return@launch

                val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
                osmdroidFragment?.showSavedTrack(track.id, geoPoints, track.colorHex)

                val midPoint = geoPoints[geoPoints.size / 2]
                osmdroidFragment?.getMapView()?.post {
                    osmdroidFragment?.animateToLocation(midPoint, 14.0, 800L)
                }
                Toast.makeText(this@DesertMapActivity, "عرض مسار: ${track.title}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("DesertMapActivity", "Error viewing track", e)
            }
        }
    }

    /**
     * يطلب إذن الموقع عند كل تشغيل ما دام غير ممنوح.
     * نافذة النظام تعرض: «أثناء استخدام التطبيق» (دائم أثناء الاستخدام) / «هذه المرة فقط» / «عدم السماح».
     * [إصلاح] أندرويد 12+ يتجاهل الطلب إذا طُلب FINE وحده بدون COARSE — لذلك نطلبهما معاً.
     */
    private fun ensureLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) return
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val askedBefore = getSharedPreferences("perm_prefs", Context.MODE_PRIVATE)
            .getBoolean("location_asked", false)
        val canShowSystemDialog = !askedBefore ||
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION) ||
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (canShowSystemDialog) {
            getSharedPreferences("perm_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("location_asked", true).apply()
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        } else {
            // رفض المستخدم مرتين → النظام لم يعد يعرض النافذة؛ نوجّهه للإعدادات
            showLocationSettingsDialog(coarseGranted)
        }
    }

    private fun showLocationSettingsDialog(onlyApproximate: Boolean) {
        if (isFinishing || isDestroyed) return
        android.app.AlertDialog.Builder(this)
            .setTitle("إذن الموقع")
            .setMessage(
                if (onlyApproximate)
                    "التطبيق يحتاج «الموقع الدقيق» للملاحة في الصحراء. من الإعدادات ← الأذونات ← الموقع، فعّل «استخدام الموقع الدقيق»."
                else
                    "التطبيق يحتاج إذن الموقع لعرض مكانك والملاحة. من الإعدادات ← الأذونات ← الموقع، اختر «السماح أثناء استخدام التطبيق»."
            )
            .setPositiveButton("فتح الإعدادات") { _, _ ->
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", packageName, null)))
                } catch (_: Exception) {}
            }
            .setNegativeButton("لاحقاً", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            val coarseOnly = !fineGranted && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (coarseOnly) {
                // اختار «تقريبي»: الملاحة تحتاج الدقيق
                showLocationSettingsDialog(onlyApproximate = true)
            } else if (fineGranted) {
                osmdroidFragment?.retryLocationAfterPermissionGranted()
                osmdroidFragment?.restoreSavedRouteIfExists()
                val loc = osmdroidFragment?.getCurrentUserLocation()
                if (loc != null) {
                    osmdroidFragment?.animateToLocation(loc, 14.0, 1000L)
                }
            } else {
                Toast.makeText(this, "الموقع مطلوب لعرض موقعك وبدء الملاحة", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun doubleEquals(a: Double?, b: Double, eps: Double = 1e-9): Boolean {
        if (a == null) return false
        return Math.abs(a - b) < eps
    }
}

