package com.alruwaili.garminalbarr.map

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.alruwaili.garminalbarr.BuildConfig
import com.alruwaili.garminalbarr.map.download.MapRegion
import com.alruwaili.garminalbarr.map.download.RegionRegistry
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.alruwaili.garminalbarr.MyLocationsStorage
import com.alruwaili.garminalbarr.R

import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OsmdroidMapFragment : Fragment() {

    companion object {
        private const val TAG = "OsmdroidMapFragment"
        private const val NAV_PREFS = "desert_nav_prefs"
        private const val KEY_ACTIVE_ORIGIN_LAT = "active_origin_lat"
        private const val KEY_ACTIVE_ORIGIN_LON = "active_origin_lon"
        private const val KEY_ACTIVE_DEST_LAT = "active_dest_lat"
        private const val KEY_ACTIVE_DEST_LON = "active_dest_lon"
        private const val KEY_ACTIVE_DEST_NAME = "active_dest_name"
        private const val KEY_HAS_ACTIVE_ROUTE = "has_active_route"
        private const val KEY_IS_ORIGIN_USER_LOCATION = "active_route_is_origin_user"
        private const val KEY_IS_CAR_ROUTE = "active_route_is_car"
        private const val KEY_CACHED_LEG1 = "cached_leg1"
        private const val KEY_CACHED_LEG2 = "cached_leg2"
        private const val KEY_CACHED_LEG3 = "cached_leg3"
        private const val KEY_ORIENTATION_MODE = "map_orientation_mode"
        // User's chosen orientation (persisted): survives route start/clear and app restart
        private const val KEY_PREF_ORIENTATION = "pref_orientation_mode"
        private const val DIRECT_LINE_REANCHOR_METERS = 3.0
        // Auto-follow resumes this long after the user's last touch on the map
        private const val AUTO_RESUME_FOLLOW_MS = 8000L
        // Compass-driven rotation (Course-Up while stationary): throttle + dead-band
        private const val COMPASS_ROTATE_MIN_INTERVAL_MS = 66L
        private const val COMPASS_ROTATE_DEADBAND_DEG = 1.5f

        private const val INITIAL_ZOOM = 6.0
        private const val TARGET_ZOOM = 12.0
        private const val GPS_FIX_ZOOM = 14.0
        private const val GPS_FIX_ANIMATION_MS = 800L
        private const val DRIVING_ZOOM = 17.0
        private const val DRIVING_ANIM_MS = 900L
        private const val OFF_ROUTE_THRESHOLD_METERS = 60.0
        private const val REJOIN_THRESHOLD_METERS = 20.0
        const val SPEED_DRIVING_THRESHOLD_MPS = 1.5f
        // Hysteresis lower edge: below this speed, blend back to sensor (prevents 1.4–1.6 m/s chatter)
        private const val SPEED_GATE_LOWER_MPS = 1.2f
        // Blend step per GPS fix (~1 Hz): ramp over ~4 fixes for smooth transition
        private const val BLEND_ALPHA_PER_FIX = 0.25f
        val DEFAULT_CENTER = GeoPoint(24.7136, 46.6753)
    }

    interface OnMapInteractionListener {
        fun onRegionChanged(region: MapRegion?)
        fun onMapPointTapped(poi: PoiDetails)
        fun onMapScaleChanged(scaleText: String)
        fun onRouteStateChanged(hasRoute: Boolean)
        fun onAzimuthChanged(azimuth: Float) {}
        fun onNavigationProgress(remainingMeters: Double, bearingDeg: Float) {}
        fun onHeadingUpChanged(isHeadingUp: Boolean) {}
        fun onLocationUpdated(location: Location) {}
        fun onAutoFollowStateChanged(isFollowing: Boolean) {}
    }

    private var mapView: MapView? = null
    private var smoothLocationOverlay: SmoothLocationOverlay? = null
    private var poiOverlay: OsmdroidPoiOverlay? = null
    private var routeOverlay: OsmdroidRouteOverlay? = null
    private var trackOverlay: OsmdroidTrackOverlay? = null
    private var terrainOverlay: OsmdroidTerrainOverlay? = null
    private var rotationOverlay: RotationGestureOverlay? = null
    var interactionListener: OnMapInteractionListener? = null
    private var lastDetectedRegion: MapRegion? = null
    private val handler = Handler(Looper.getMainLooper())
    private var gpsFirstFixReceived = false

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var latestLocation: Location? = null

    // ---- Active navigation & driving follow state ----
    enum class MapOrientationMode {
        NORTH_UP,   // Locked strictly to 0° True North; map never rotates; labels stay horizontal
        // Dynamic heading rotation; gated by speed >= 1.5 m/s. DISABLED app-wide as of
        // 2026-11-25 — nothing in this file sets orientationMode to COURSE_UP anymore
        // (Mapsforge bakes place-name labels as pixels into the tiles, so rotating the
        // canvas rotated the labels too, making them unreadable). The enum value and the
        // COURSE_UP branches below are kept only so this can be re-enabled later if a
        // proper un-rotated label layer is ever built.
        COURSE_UP
    }

    private var orientationMode = MapOrientationMode.NORTH_UP
    private var isAutoFollowSuspended = false
    private var lastCompassRotateMs = 0L
    private var compassMapHeading = Float.NaN
    private val resumeFollowRunnable = Runnable {
        if (isAutoFollowSuspended && isAdded && mapView != null &&
            hasSavedRoute() && isOriginUserLocation) {
            recenterDrivingCamera()
        }
    }
    private var smoothedHeading = 0f
    // Speed-gate blend state: 0f = pure sensor azimuth, 1f = pure GPS course
    private var gpsSensorBlendAlpha = 0f
    private var isOffRouteActive = false

    private var activeDestGeo: GeoPoint? = null
    private var activeDestName: String? = null
    private var isOriginUserLocation: Boolean = true
    private var isCarRouteActive: Boolean = false
    private var customRouteDistanceMeters: Double? = null
    private var customRouteBearingDeg: Float? = null
    private var poiRefreshJob: Job? = null
    private var userLocationsOverlay: FolderOverlay? = null
    private var userLocationsRefreshJob: Job? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        interactionListener = context as? OnMapInteractionListener
    }

    override fun onDetach() {
        super.onDetach()
        interactionListener = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        orientationMode = preferredOrientationMode()
        savedInstanceState?.getString(KEY_ORIENTATION_MODE)?.let {
            try {
                orientationMode = MapOrientationMode.valueOf(it)
            } catch (_: Exception) {}
        }
        val ctx = requireContext().applicationContext
        val prefs = ctx.getSharedPreferences("osmdroid_config", Context.MODE_PRIVATE)
        Configuration.getInstance().setUserAgentValue(ctx.packageName)
        Configuration.getInstance().load(ctx, prefs)
        Configuration.getInstance().setDebugTileProviders(false)
        Configuration.getInstance().setDebugMapTileDownloader(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_ORIENTATION_MODE, orientationMode.name)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val mapView = MapView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        }
        this.mapView = mapView

        // 0. Terrain hillshade overlay — drawn first so every other overlay (location, POIs,
        // route, track markers) paints on top of it, never underneath.
        val terrainOv = OsmdroidTerrainOverlay(requireContext().applicationContext)
        terrainOverlay = terrainOv
        mapView.overlays.add(terrainOv)

        // 1. High-performance SmoothLocationOverlay (VSync-gated rotation + FusedLocation).
        // applicationContext: overlay can outlive the Activity on config change.
        // Static North-Up: azimuth is NEVER forwarded to setMapOrientation —
        // only the arrow overlay rotates. onAzimuthChanged is a pure passthrough.
        val locOverlay = SmoothLocationOverlay(requireContext().applicationContext, mapView)
        locOverlay.onAzimuthChanged = { az ->
            interactionListener?.onAzimuthChanged(az)
            applyCompassRotation(az)
        }
        smoothLocationOverlay = locOverlay
        mapView.overlays.add(locOverlay)
        try {
            mapView.setMapOrientation(0f, false)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "north-up lock failed", e)
        }

        // Rotation gestures disabled: North-Up lock. Pan + pinch zoom stay free.
        val rotationOverlay = RotationGestureOverlay(mapView)
        rotationOverlay.isEnabled = false
        this.rotationOverlay = rotationOverlay
        mapView.overlays.add(rotationOverlay)

        // Gesture overlay: detects manual pan to suspend auto-follow during driving.
        // NEVER consumes events (returns false) so pan/zoom/POI taps work natively.
        val touchSlop = 16f * resources.displayMetrics.density
        val touchSlopSq = touchSlop * touchSlop
        val gestureOverlay = object : org.osmdroid.views.overlay.Overlay() {
            private var downX = 0f
            private var downY = 0f
            override fun onTouchEvent(event: MotionEvent?, mapView: MapView?): Boolean {
                if (event == null) return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        if (isAutoFollowSuspended) scheduleFollowResume()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (dx * dx + dy * dy > touchSlopSq) {
                            if (hasSavedRoute() && isOriginUserLocation && !isAutoFollowSuspended) {
                                isAutoFollowSuspended = true
                                interactionListener?.onAutoFollowStateChanged(false)
                            }
                            if (isAutoFollowSuspended) scheduleFollowResume()
                        }
                    }
                }
                return false
            }
        }
        mapView.overlays.add(gestureOverlay)

        // 2. POI overlay — single owner of tap-anywhere: POI hit → details,
        // empty sand → tap POI via onEmptyAreaTapped. (A separate MapEventsOverlay
        // would double-dispatch the same tap into onMapPointTapped twice.)
        poiOverlay = OsmdroidPoiOverlay(
            onPoiTapped = { poi ->
                interactionListener?.onMapPointTapped(poi)
            },
            onEmptyAreaTapped = { tapGeo ->
                interactionListener?.onMapPointTapped(createTapPoi(tapGeo))
            }
        )
        val density = resources.displayMetrics.density
        poiOverlay?.configure(density)
        mapView.overlays.add(poiOverlay)

        // 2b. User saved locations overlay (live Desert Gold pins)
        val userOverlay = FolderOverlay()
        userLocationsOverlay = userOverlay
        mapView.overlays.add(userOverlay)

        // 3. Route overlay with geodesic accuracy & destination marker
        routeOverlay = OsmdroidRouteOverlay(mapView)

        // 3b. Track overlay for live recording, saved tracks, and backtrack
        trackOverlay = OsmdroidTrackOverlay(mapView)

        // 4. Map scroll/zoom listener
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                detectRegion()
                notifyScaleChange()
                refreshVisiblePois()
                return false
            }
            override fun onZoom(event: ZoomEvent?): Boolean {
                detectRegion()
                notifyScaleChange()
                refreshVisiblePois()
                return false
            }
        })

        // 6. Initial camera position
        val args = arguments
        val targetLat = args?.getDouble("latitude", 0.0) ?: 0.0
        val targetLon = args?.getDouble("longitude", 0.0) ?: 0.0

        var initialCenter = DEFAULT_CENTER
        var initialZoom = INITIAL_ZOOM

        if (targetLat != 0.0 && targetLon != 0.0) {
            initialCenter = GeoPoint(targetLat, targetLon)
            initialZoom = TARGET_ZOOM
            gpsFirstFixReceived = true
        } else {
            try {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    val locManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                    val lastKnown = locManager?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                        ?: locManager?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                        ?: locManager?.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER)

                    if (lastKnown != null && (lastKnown.latitude != 0.0 || lastKnown.longitude != 0.0)) {
                        initialCenter = GeoPoint(lastKnown.latitude, lastKnown.longitude)
                        initialZoom = GPS_FIX_ZOOM
                        latestLocation = lastKnown
                        smoothLocationOverlay?.setLocation(lastKnown)
                        gpsFirstFixReceived = true
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Initial center set from LocationManager lastKnown: ${lastKnown.latitude}, ${lastKnown.longitude}")
                        }
                    }
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Failed to query lastKnownLocation on init", e)
            }
        }

        mapView.controller.setZoom(initialZoom)
        mapView.controller.setCenter(initialCenter)

        downloadWorldMapIfNeeded(mapView)

        // 7. Auto-center & Fused Location Bridging
        setupFusedLocationUpdates(mapView, targetLat == 0.0 && targetLon == 0.0)

        // Post-layout re-center to ensure osmdroid canvas measurement
        mapView.post {
            val centerGeo = getCurrentUserLocation() ?: initialCenter
            mapView.controller.setCenter(centerGeo)
            mapView.controller.setZoom(initialZoom)
            mapView.invalidate()
        }

        // Notify initial scale
        handler.post { notifyScaleChange() }

        return mapView
    }

    private fun createTapPoi(p: GeoPoint): PoiDetails {
        return PoiDetails(
            name = String.format(Locale.US, "%.5f, %.5f", p.latitude, p.longitude),
            category = "user_location",
            latitude = p.latitude,
            longitude = p.longitude
        )
    }

    // ======================== Fused Location & Heading ========================

    /**
     * Restart fused location after a runtime permission grant. [setupFusedLocationUpdates]
     * early-returns when permission is missing at [onCreateView] time, so a later
     * grant needs this explicit retry (called from `onRequestPermissionsResult`).
     */
    fun retryLocationAfterPermissionGranted() {
        if (!isAdded) return
        val mv = mapView ?: return
        try {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                // Client exists but updates may never have been requested
                // (setup threw SecurityException, or updates were dropped):
                // re-request rather than no-op.
                val client = fusedLocationClient
                val callback = locationCallback
                if (client != null && callback != null) {
                    val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
                    client.requestLocationUpdates(req, callback, Looper.getMainLooper())
                } else {
                    setupFusedLocationUpdates(mv, true)
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "retryLocationAfterPermissionGranted failed", e)
        }
    }

    private fun setupFusedLocationUpdates(mapView: MapView, shouldAutoCenter: Boolean) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Location permission not granted — cannot start fused location")
            return
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(requireContext())
        fusedLocationClient = fusedClient

        // Fast immediate check
        fusedClient.lastLocation.addOnSuccessListener { loc: Location? ->
            if (loc != null && (loc.latitude != 0.0 || loc.longitude != 0.0)) {
                handleNewLocation(loc, shouldAutoCenter)
            }
        }

        // Live high-accuracy location callback
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                if (loc.latitude != 0.0 || loc.longitude != 0.0) {
                    handleNewLocation(loc, shouldAutoCenter)
                }
            }
        }
        locationCallback = callback

        try {
            fusedClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "SecurityException requesting location updates", e)
        }
    }

    private fun handleNewLocation(loc: Location, shouldAutoCenter: Boolean) {
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return
        val isFirstFix = !gpsFirstFixReceived
        latestLocation = loc
        smoothLocationOverlay?.setLocation(loc)

        // Live HUD engine: 1 Hz fused fix -> distance/bearing to active destination.
        if (isOriginUserLocation) {
            emitNavigationProgress(loc.latitude, loc.longitude)
        }
        interactionListener?.onLocationUpdated(loc)

        if (isFirstFix && shouldAutoCenter && isAdded && mapView != null) {
            gpsFirstFixReceived = true
            val geo = GeoPoint(loc.latitude, loc.longitude)
            mapView?.post {
                mapView?.controller?.setCenter(geo)
                mapView?.controller?.setZoom(GPS_FIX_ZOOM)
                mapView?.invalidate()
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Location fast-fixed via FusedLocationClient: ${loc.latitude}, ${loc.longitude}")
            }

            // Restore saved route if present
            restoreSavedRouteIfExists()
        } else if (hasSavedRoute() && isOriginUserLocation && isAdded && mapView != null) {
            // ---- Active Driving Navigation Loop ----
            // 1. Dual-source heading determination with speed-gate switching
            val isDrivingSpeed = loc.hasSpeed() && loc.speed >= SPEED_DRIVING_THRESHOLD_MPS
            val isSlowing      = loc.hasSpeed() && loc.speed < SPEED_GATE_LOWER_MPS
            val isActivelyDrivingCar = isCarRouteActive && isDrivingSpeed && loc.hasBearing()

            // Ramp blend alpha smoothly to avoid abrupt source switch at threshold boundary
            gpsSensorBlendAlpha = when {
                isActivelyDrivingCar -> (gpsSensorBlendAlpha + BLEND_ALPHA_PER_FIX).coerceAtMost(1f)
                isSlowing            -> (gpsSensorBlendAlpha - BLEND_ALPHA_PER_FIX).coerceAtLeast(0f)
                else                 -> gpsSensorBlendAlpha // hold during 1.2–1.5 m/s ambiguous band
            }

            val sensorAzimuth = smoothLocationOverlay?.getBearing()?.takeIf { it.isFinite() } ?: smoothedHeading
            val gpsAzimuth: Float = if (loc.hasBearing()) {
                NavigationMath.smoothHeadingStep(smoothedHeading, loc.bearing, alpha = 0.35f)
            } else {
                sensorAzimuth
            }
            // Shortest-arc slerp blends smoothly between the two heading sources
            val blendedHeading = HeadingFilter.shortestArcSlerp(sensorAzimuth, gpsAzimuth, gpsSensorBlendAlpha)
            smoothedHeading = blendedHeading

            if (gpsSensorBlendAlpha > 0f) {
                smoothLocationOverlay?.setDrivingHeading(blendedHeading)
            } else {
                smoothLocationOverlay?.setDrivingHeading(null)
            }

            if (orientationMode == MapOrientationMode.COURSE_UP) {
                if (isDrivingSpeed) {
                    compassMapHeading = smoothedHeading
                    val mapRot = NavigationMath.mapOrientationFor(smoothedHeading)
                    try {
                        mapView?.setMapOrientation(mapRot, false)
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.w(TAG, "setMapOrientation failed", e)
                    }
                }
                // Stationary (speed < 1.5 m/s): Freeze rotation to prevent jitter
            } else {
                // North-Up Locked mode: ensure map orientation remains strictly at 0° True North
                if (mapView?.mapOrientation != 0f) {
                    try {
                        mapView?.setMapOrientation(0f, false)
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.w(TAG, "north-up lock failed", e)
                    }
                }
            }

            // 2. Real-time camera follow with 18% lookahead offset (car sits at 68% screen height)
            if (!isAutoFollowSuspended) {
                val lookAheadCenter = calculateLookAheadCenter(loc, smoothedHeading)
                mapView?.controller?.animateTo(lookAheadCenter)
            }

            // 3a. Straight-line route from the user's position: keep the line
            // anchored at the user so it always points at the target.
            val liveDest = activeDestGeo
            if (!isCarRouteActive && liveDest != null) {
                val here = GeoPoint(loc.latitude, loc.longitude)
                val last = lastDirectLineAnchor
                if (last == null || last.distanceToAsDouble(here) >= DIRECT_LINE_REANCHOR_METERS) {
                    lastDirectLineAnchor = here
                    isOffRouteActive = false
                    routeOverlay?.updateDirectRoute(here, liveDest)
                }
                return
            }

            // 3. Off-route cross-track calculation & rejoin leg
            val routePoints = routeOverlay?.getPrimaryRoutePoints()
            if (!routePoints.isNullOrEmpty() && routePoints.size >= 2) {
                val closest = NavigationMath.findClosestPointOnPolyline(loc.latitude, loc.longitude, routePoints)
                if (closest != null) {
                    val (closestPoint, distMeters) = closest
                    if (!isOffRouteActive && distMeters > OFF_ROUTE_THRESHOLD_METERS) {
                        isOffRouteActive = true
                        routeOverlay?.updateRejoinLeg(GeoPoint(loc.latitude, loc.longitude), closestPoint)
                    } else if (isOffRouteActive) {
                        if (distMeters <= REJOIN_THRESHOLD_METERS) {
                            isOffRouteActive = false
                            routeOverlay?.clearRejoinLeg()
                        } else {
                            routeOverlay?.updateRejoinLeg(GeoPoint(loc.latitude, loc.longitude), closestPoint)
                        }
                    }
                }
            }
        }
    }

    private fun calculateLookAheadCenter(loc: Location, heading: Float): GeoPoint {
        // North-Up: keep the arrow exactly in the middle of the screen.
        if (orientationMode == MapOrientationMode.NORTH_UP) {
            return GeoPoint(loc.latitude, loc.longitude)
        }
        val mv = mapView
        val screenH = if ((mv?.height ?: 0) > 0) mv!!.height.toDouble() else resources.displayMetrics.heightPixels.toDouble()
        val currentZoom = mv?.zoomLevelDouble ?: DRIVING_ZOOM
        val zoom = if (currentZoom < 14.0) DRIVING_ZOOM else currentZoom
        val cosLat = cos(Math.toRadians(loc.latitude))
        val metersPerPx = NavigationMath.EQUATORIAL_METERS_PER_PIXEL_ZOOM_0 * cosLat / 2.0.pow(zoom)
        val lookAheadMeters = (screenH * NavigationMath.DRIVING_LOOKAHEAD_RATIO) * metersPerPx
        return NavigationMath.destinationPoint(loc.latitude, loc.longitude, lookAheadMeters, heading)
    }

    private var lastDirectLineAnchor: GeoPoint? = null

    fun isAutoFollowSuspended(): Boolean = isAutoFollowSuspended

    fun recenterDrivingCamera() {
        handler.removeCallbacks(resumeFollowRunnable)
        isAutoFollowSuspended = false
        interactionListener?.onAutoFollowStateChanged(true)
        val loc = latestLocation ?: return
        val lookAheadCenter = calculateLookAheadCenter(loc, smoothedHeading)
        animateToLocation(lookAheadCenter, DRIVING_ZOOM, 500L)
    }

    fun getCurrentUserLocation(): GeoPoint? {
        val loc = smoothLocationOverlay?.getLocation()
        if (loc != null) return loc
        val fused = latestLocation
        if (fused != null) return GeoPoint(fused.latitude, fused.longitude)
        return null
    }

    /** السرعة الحالية بالكيلومتر/الساعة من آخر قراءة GPS، أو null إن لم تتضمّن القراءة سرعة بعد. */
    fun getCurrentSpeedKmh(): Float? {
        val loc = latestLocation ?: return null
        if (!loc.hasSpeed()) return null
        return loc.speed * 3.6f
    }

    // ======================== Route Navigation & Persistence ========================

    /**
     * @return false (and no state change) when either endpoint is invalid —
     * defense-in-depth behind the call-site validation in DesertMapActivity.
     */
    fun startNavigation(
        startGeo: GeoPoint,
        destGeo: GeoPoint,
        destName: String?,
        isOriginCurrentLocation: Boolean = true
    ): Boolean {
        if (!MapNavigationHelper.isValidLatLon(startGeo.latitude, startGeo.longitude) ||
            !MapNavigationHelper.isValidLatLon(destGeo.latitude, destGeo.longitude)
        ) {
            if (BuildConfig.DEBUG) Log.w(TAG, "startNavigation rejected invalid coords")
            return false
        }
        isCarRouteActive = false
        saveActiveRoute(startGeo.latitude, startGeo.longitude, destGeo.latitude, destGeo.longitude, destName, isOriginCurrentLocation, isCarRoute = false)
        routeOverlay?.showRoute(startGeo, destGeo)

        // Course-Up disabled app-wide (2026-11-25): Mapsforge bakes place-name labels
        // as pixels directly into the rendered tile bitmaps, so rotating the whole
        // canvas in Course-Up rotated those baked-in labels too, making them unreadable.
        // The map now always stays locked to true North regardless of travel direction.
        lastDirectLineAnchor = null
        setOrientationMode(MapOrientationMode.NORTH_UP, showToast = false)

        activeDestGeo = destGeo
        activeDestName = destName

        setupActiveRouteNavigation(
            startGeo = startGeo,
            destGeo = destGeo,
            isOriginCurrentLocation = isOriginCurrentLocation,
            customDistance = null
        )
        ensurePoiOverlayOnTop()
        return true
    }

    fun startCarRouteNavigation(
        routeResult: com.alruwaili.garminalbarr.map.routing.OfflineRouter.RouteResult,
        startGeo: GeoPoint,
        destGeo: GeoPoint,
        destName: String?,
        isOriginCurrentLocation: Boolean = true
    ): Boolean {
        if (!MapNavigationHelper.isValidLatLon(startGeo.latitude, startGeo.longitude) ||
            !MapNavigationHelper.isValidLatLon(destGeo.latitude, destGeo.longitude)
        ) {
            if (BuildConfig.DEBUG) Log.w(TAG, "startCarRouteNavigation rejected invalid coords")
            return false
        }
        isCarRouteActive = true
        saveActiveRoute(
            startGeo.latitude, startGeo.longitude,
            destGeo.latitude, destGeo.longitude,
            destName, isOriginCurrentLocation, isCarRoute = true,
            leg1 = routeResult.leg1Direct, leg2 = routeResult.leg2Track, leg3 = routeResult.leg3Direct
        )

        val leg1 = routeResult.leg1Direct.map { GeoPoint(it.latitude, it.longitude) }
        val leg2 = routeResult.leg2Track.map { GeoPoint(it.latitude, it.longitude) }
        val leg3 = routeResult.leg3Direct.map { GeoPoint(it.latitude, it.longitude) }

        if (routeResult.isFallbackDirect || leg2.isEmpty()) {
            routeOverlay?.showRoute(startGeo, destGeo)
        } else {
            routeOverlay?.showCarRoute(leg1, leg2, leg3)
        }

        activeDestGeo = destGeo
        activeDestName = destName

        setupActiveRouteNavigation(
            startGeo = startGeo,
            destGeo = destGeo,
            isOriginCurrentLocation = isOriginCurrentLocation,
            customDistance = routeResult.distanceMeters
        )
        ensurePoiOverlayOnTop()
        return true
    }

    private fun setupActiveRouteNavigation(
        startGeo: GeoPoint,
        destGeo: GeoPoint,
        isOriginCurrentLocation: Boolean,
        customDistance: Double?
    ) {
        val userLoc = getCurrentUserLocation()
        val isOriginUser = isOriginCurrentLocation
        isOriginUserLocation = isOriginUser
        smoothLocationOverlay?.setRouteInfo(startGeo, destGeo, isOriginUser)

        if (!isOriginUser) {
            customRouteDistanceMeters = customDistance ?: NavigationMath.distanceMeters(startGeo.latitude, startGeo.longitude, destGeo.latitude, destGeo.longitude)
            customRouteBearingDeg = NavigationMath.bearingDeg(startGeo.latitude, startGeo.longitude, destGeo.latitude, destGeo.longitude)
        } else {
            customRouteDistanceMeters = null
            customRouteBearingDeg = null
        }

        isAutoFollowSuspended = false
        isOffRouteActive = false
        gpsSensorBlendAlpha = 0f  // always start blend from zero on new route session
        val loc = latestLocation
        val departure = if (isOriginUser) (userLoc ?: startGeo) else startGeo
        val isActivelyDrivingCar = isOriginUser && isCarRouteActive && loc != null && loc.hasBearing() && loc.hasSpeed() && loc.speed >= SPEED_DRIVING_THRESHOLD_MPS
        val userHeading = if (isActivelyDrivingCar) {
            loc!!.bearing
        } else {
            val az = smoothLocationOverlay?.getBearing() ?: 0f
            if (az.isFinite()) az else (if (loc?.hasBearing() == true) loc.bearing else 0f)
        }
        smoothedHeading = userHeading
        if (isActivelyDrivingCar) {
            smoothLocationOverlay?.setDrivingHeading(smoothedHeading)
        } else {
            smoothLocationOverlay?.setDrivingHeading(null)
        }
        val initialOrientation = if (isOriginUser && orientationMode == MapOrientationMode.COURSE_UP) {
            NavigationMath.mapOrientationFor(smoothedHeading)
        } else {
            0f
        }

        try {
            mapView?.setMapOrientation(initialOrientation, false)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "setMapOrientation failed", e)
        }

        animateToLocation(departure, DRIVING_ZOOM, DRIVING_ANIM_MS)
        interactionListener?.onRouteStateChanged(true)
        interactionListener?.onAutoFollowStateChanged(true)

        if (isOriginUser) {
            val cur = getCurrentUserLocation()
            if (cur != null) {
                emitNavigationProgress(cur.latitude, cur.longitude)
            } else {
                emitNavigationProgress(departure.latitude, departure.longitude)
            }
        } else {
            val dist = customRouteDistanceMeters ?: NavigationMath.distanceMeters(startGeo.latitude, startGeo.longitude, destGeo.latitude, destGeo.longitude)
            val bearing = customRouteBearingDeg ?: NavigationMath.bearingDeg(startGeo.latitude, startGeo.longitude, destGeo.latitude, destGeo.longitude)
            interactionListener?.onNavigationProgress(dist, bearing)
        }
    }

    fun clearRoute() {
        isCarRouteActive = false
        lastDirectLineAnchor = null
        routeOverlay?.hideRoute()
        smoothLocationOverlay?.clearRouteInfo()
        smoothLocationOverlay?.setDrivingHeading(null)
        clearActiveRoutePrefs()
        activeDestGeo = null
        activeDestName = null
        isOriginUserLocation = true
        customRouteDistanceMeters = null
        customRouteBearingDeg = null
        isAutoFollowSuspended = false
        isOffRouteActive = false
        gpsSensorBlendAlpha = 0f
        smoothedHeading = 0f
        handler.removeCallbacks(resumeFollowRunnable)
        setOrientationMode(preferredOrientationMode(), showToast = false)
        mapView?.invalidate()
        interactionListener?.onRouteStateChanged(false)
        interactionListener?.onAutoFollowStateChanged(true)
        ensurePoiOverlayOnTop()
    }

    /**
     * Move POI overlay to the END of the overlay list (topmost position).
     * osmdroid dispatches taps from last overlay backwards — the POI overlay
     * must sit above route Polyline objects so it receives taps first.
     */
    private fun ensurePoiOverlayOnTop() {
        val mv = mapView ?: return
        val poi = poiOverlay ?: return
        mv.overlays.remove(poi)
        mv.overlays.add(poi)
    }

    private fun emitNavigationProgress(fromLat: Double, fromLon: Double) {
        val dest = activeDestGeo ?: return
        val listener = interactionListener ?: return
        try {
            val d = NavigationMath.distanceMeters(fromLat, fromLon, dest.latitude, dest.longitude)
            val b = NavigationMath.bearingDeg(fromLat, fromLon, dest.latitude, dest.longitude)
            listener.onNavigationProgress(d, b)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "emitNavigationProgress failed", e)
        }
    }

    fun hasSavedRoute(): Boolean {
        val prefs = requireContext().getSharedPreferences(NAV_PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_HAS_ACTIVE_ROUTE, false)
    }

    fun getSavedDestName(): String? {
        return try {
            requireContext().getSharedPreferences(NAV_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ACTIVE_DEST_NAME, null)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "getSavedDestName failed", e)
            null
        }
    }

    fun restoreSavedRouteIfExists(): Boolean {
        if (!isAdded) return false
        val prefs = requireContext().getSharedPreferences(NAV_PREFS, Context.MODE_PRIVATE)
        val hasRoute = prefs.getBoolean(KEY_HAS_ACTIVE_ROUTE, false)
        if (!hasRoute) return false

        val destLat = getSafeCoordinate(prefs, KEY_ACTIVE_DEST_LAT)
        val destLon = getSafeCoordinate(prefs, KEY_ACTIVE_DEST_LON)
        if (destLat.isNaN() || destLon.isNaN() || (destLat == 0.0 && destLon == 0.0)) return false

        val originLat = getSafeCoordinate(prefs, KEY_ACTIVE_ORIGIN_LAT)
        val originLon = getSafeCoordinate(prefs, KEY_ACTIVE_ORIGIN_LON)
        val originGeo = if (!originLat.isNaN() && !originLon.isNaN() && (originLat != 0.0 || originLon != 0.0)) {
            GeoPoint(originLat, originLon)
        } else {
            getCurrentUserLocation() ?: DEFAULT_CENTER
        }

        val destName = prefs.getString(KEY_ACTIVE_DEST_NAME, null)
        val destGeo = GeoPoint(destLat, destLon)

        activeDestGeo = destGeo
        activeDestName = destName

        val isCarRoute = prefs.getBoolean(KEY_IS_CAR_ROUTE, false)
        isCarRouteActive = isCarRoute

        if (isCarRoute) {
            val cachedLeg1 = deserializeLeg(prefs.getString(KEY_CACHED_LEG1, null))
            val cachedLeg2 = deserializeLeg(prefs.getString(KEY_CACHED_LEG2, null))
            val cachedLeg3 = deserializeLeg(prefs.getString(KEY_CACHED_LEG3, null))

            if (cachedLeg2.isNotEmpty()) {
                val leg1 = cachedLeg1.map { GeoPoint(it.latitude, it.longitude) }
                val leg2 = cachedLeg2.map { GeoPoint(it.latitude, it.longitude) }
                val leg3 = cachedLeg3.map { GeoPoint(it.latitude, it.longitude) }
                routeOverlay?.showCarRoute(leg1, leg2, leg3)
            } else {
                routeOverlay?.showRoute(originGeo, destGeo)
            }
        } else {
            routeOverlay?.showRoute(originGeo, destGeo)
        }

        val userLoc = getCurrentUserLocation()
        val isOriginUser = prefs.getBoolean(
            KEY_IS_ORIGIN_USER_LOCATION,
            userLoc != null && (originGeo.distanceToAsDouble(userLoc) < 100.0)
        )
        isOriginUserLocation = isOriginUser
        smoothLocationOverlay?.setRouteInfo(originGeo, destGeo, isOriginUser)

        if (!isOriginUser) {
            customRouteDistanceMeters = NavigationMath.distanceMeters(originGeo.latitude, originGeo.longitude, destGeo.latitude, destGeo.longitude)
            customRouteBearingDeg = NavigationMath.bearingDeg(originGeo.latitude, originGeo.longitude, destGeo.latitude, destGeo.longitude)
        } else {
            customRouteDistanceMeters = null
            customRouteBearingDeg = null
        }

        // Restore dynamic driving state
        isAutoFollowSuspended = false
        isOffRouteActive = false
        gpsSensorBlendAlpha = 0f  // always start blend from zero on restored route session
        val loc = latestLocation
        val isActivelyDrivingCar = isOriginUser && isCarRoute && loc != null && loc.hasBearing() && loc.hasSpeed() && loc.speed >= SPEED_DRIVING_THRESHOLD_MPS
        val userHeading = if (isActivelyDrivingCar) {
            loc!!.bearing
        } else {
            val az = smoothLocationOverlay?.getBearing() ?: 0f
            if (az.isFinite()) az else (if (loc?.hasBearing() == true) loc.bearing else 0f)
        }
        smoothedHeading = userHeading
        if (isActivelyDrivingCar) {
            smoothLocationOverlay?.setDrivingHeading(smoothedHeading)
        } else {
            smoothLocationOverlay?.setDrivingHeading(null)
        }
        if (!isCarRoute) {
            // Course-Up disabled app-wide (2026-11-25) — see startNavigation() for why.
            lastDirectLineAnchor = null
            setOrientationMode(MapOrientationMode.NORTH_UP, showToast = false)
        }
        val initialOrientation = if (isOriginUser && orientationMode == MapOrientationMode.COURSE_UP) {
            NavigationMath.mapOrientationFor(smoothedHeading)
        } else {
            0f
        }

        try {
            mapView?.setMapOrientation(initialOrientation, false)
            mapView?.controller?.setZoom(DRIVING_ZOOM)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "restore driving orientation failed", e)
        }

        mapView?.invalidate()
        interactionListener?.onRouteStateChanged(true)
        interactionListener?.onAutoFollowStateChanged(true)
        if (isOriginUser) {
            val cur = getCurrentUserLocation()
            if (cur != null) {
                emitNavigationProgress(cur.latitude, cur.longitude)
            } else {
                emitNavigationProgress(originGeo.latitude, originGeo.longitude)
            }
        } else {
            val dist = customRouteDistanceMeters ?: NavigationMath.distanceMeters(originGeo.latitude, originGeo.longitude, destGeo.latitude, destGeo.longitude)
            val bearing = customRouteBearingDeg ?: NavigationMath.bearingDeg(originGeo.latitude, originGeo.longitude, destGeo.latitude, destGeo.longitude)
            interactionListener?.onNavigationProgress(dist, bearing)
        }
        return true
    }

    private fun getSafeCoordinate(prefs: SharedPreferences, key: String): Double {
        return try {
            val bits = prefs.getLong(key, 0L)
            if (bits != 0L) java.lang.Double.longBitsToDouble(bits) else Double.NaN
        } catch (e: ClassCastException) {
            try {
                val f = prefs.getFloat(key, Float.NaN)
                if (!f.isNaN()) f.toDouble() else Double.NaN
            } catch (e2: Exception) {
                Double.NaN
            }
        } catch (e3: Exception) {
            Double.NaN
        }
    }

    private fun saveActiveRoute(
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double,
        destName: String?,
        isOriginUser: Boolean,
        isCarRoute: Boolean = false,
        leg1: List<com.alruwaili.garminalbarr.map.routing.LatLng>? = null,
        leg2: List<com.alruwaili.garminalbarr.map.routing.LatLng>? = null,
        leg3: List<com.alruwaili.garminalbarr.map.routing.LatLng>? = null
    ) {
        val prefs = requireContext().getSharedPreferences(NAV_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_ACTIVE_ORIGIN_LAT, java.lang.Double.doubleToRawLongBits(originLat))
            .putLong(KEY_ACTIVE_ORIGIN_LON, java.lang.Double.doubleToRawLongBits(originLon))
            .putLong(KEY_ACTIVE_DEST_LAT, java.lang.Double.doubleToRawLongBits(destLat))
            .putLong(KEY_ACTIVE_DEST_LON, java.lang.Double.doubleToRawLongBits(destLon))
            .putString(KEY_ACTIVE_DEST_NAME, destName)
            .putBoolean(KEY_HAS_ACTIVE_ROUTE, true)
            .putBoolean(KEY_IS_ORIGIN_USER_LOCATION, isOriginUser)
            .putBoolean(KEY_IS_CAR_ROUTE, isCarRoute)
            .putString(KEY_CACHED_LEG1, serializeLeg(leg1))
            .putString(KEY_CACHED_LEG2, serializeLeg(leg2))
            .putString(KEY_CACHED_LEG3, serializeLeg(leg3))
            .apply()
    }

    private fun serializeLeg(leg: List<com.alruwaili.garminalbarr.map.routing.LatLng>?): String? {
        if (leg == null || leg.isEmpty()) return null
        return leg.joinToString(";") { "${it.latitude},${it.longitude}" }
    }

    private fun deserializeLeg(raw: String?): List<com.alruwaili.garminalbarr.map.routing.LatLng> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            raw.split(";").map { pair ->
                val (lat, lon) = pair.split(",")
                com.alruwaili.garminalbarr.map.routing.LatLng(lat.toDouble(), lon.toDouble())
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun clearActiveRoutePrefs() {
        val prefs = requireContext().getSharedPreferences(NAV_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_ACTIVE_ORIGIN_LAT)
            .remove(KEY_ACTIVE_ORIGIN_LON)
            .remove(KEY_ACTIVE_DEST_LAT)
            .remove(KEY_ACTIVE_DEST_LON)
            .remove(KEY_ACTIVE_DEST_NAME)
            .remove(KEY_IS_ORIGIN_USER_LOCATION)
            .remove(KEY_CACHED_LEG1)
            .remove(KEY_CACHED_LEG2)
            .remove(KEY_CACHED_LEG3)
            .putBoolean(KEY_HAS_ACTIVE_ROUTE, false)
            .apply()
    }

    // ======================== Core lifecycle ========================

    private fun downloadWorldMapIfNeeded(mapView: MapView) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Initializing map tile source")
        OfflineMapEngine.refreshAfterDownload(requireContext(), mapView)
    }

    fun refreshTileSource(mapView: MapView? = this.mapView) {
        val mv = mapView ?: return
        OfflineMapEngine.refreshAfterDownload(requireContext(), mv)
    }

    /** يُستدعى بعد اكتمال تنزيل بلاطات تضاريس جديدة، كي تُعاد قراءتها من القرص بدل الاعتماد على ذاكرة قديمة. */
    fun refreshTerrainOverlay() {
        terrainOverlay?.invalidateCache()
        mapView?.invalidate()
    }

    /**
     * "الرؤية الصحراوية X-Ray" — يبدّل طبقة التضاريس بين التظليل العادي ووضع التحليل اللوني
     * (ارتفاع + انحدار)، ويطلب إعادة رسم فورية كي يظهر التغيير دون الحاجة لتحريك الخريطة.
     */
    fun setTerrainXRayMode(enabled: Boolean) {
        terrainOverlay?.setRenderMode(if (enabled) TerrainRenderMode.XRAY else TerrainRenderMode.HILLSHADE)
        mapView?.invalidate()
    }

    private fun detectRegion() {
        val mv = mapView ?: return
        val zoom = mv.zoomLevelDouble
        if (zoom < 8.0) {
            if (lastDetectedRegion != null) {
                lastDetectedRegion = null
                interactionListener?.onRegionChanged(null)
            }
            return
        }

        val center = mv.mapCenter
        val region = RegionRegistry.findRegionForLocation(center.latitude, center.longitude)
        if (region?.id != lastDetectedRegion?.id) {
            lastDetectedRegion = region
            interactionListener?.onRegionChanged(region)
        }
    }

    private fun refreshVisiblePois() {
        val mv = mapView ?: return
        val zoom = mv.zoomLevelDouble
        // Video spec: DB + tile POIs appear together at zoom >= 12 (~5.9 km scale).
        if (zoom < 12.0) {
            poiRefreshJob?.cancel()
            poiOverlay?.clearPois()
            mv.invalidate()
            return
        }
        poiRefreshJob?.cancel()
        poiRefreshJob = lifecycleScope.launch {
            if (!isAdded) return@launch
            val ctx = context ?: return@launch
            val bounds = mv.boundingBox
            try {
                val pois = withContext(Dispatchers.IO) {
                    com.alruwaili.garminalbarr.RoomDBHelper.getInstance(ctx)
                        .locationDao.getDesertPoisInBounds(
                            bounds.latSouth, bounds.latNorth,
                            bounds.lonWest, bounds.lonEast
                        )
                }
                if (!isAdded) return@launch
                poiOverlay?.updatePois(pois)
                mv.invalidate()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "POI refresh failed", e)
            }
        }
    }

    fun refreshUserLocations() {
        val mv = mapView ?: return
        userLocationsRefreshJob?.cancel()
        userLocationsRefreshJob = lifecycleScope.launch {
            if (!isAdded) return@launch
            val ctx = context ?: return@launch
            val locations = withContext(Dispatchers.IO) {
                val dbLocations = try {
                    com.alruwaili.garminalbarr.RoomDBHelper.getInstance(ctx)
                        .locationDao.getUserSavedLocations()
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Failed reading user locations from DB", e)
                    emptyList()
                }
                val storageLocations = try {
                    MyLocationsStorage(ctx).load()
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Failed reading user locations from storage", e)
                    emptyList()
                }
                // Merge and deduplicate by title + coordinates
                (dbLocations + storageLocations).distinctBy { loc ->
                    val latStr = String.format(Locale.US, "%.5f", loc.latitude ?: 0.0)
                    val lonStr = String.format(Locale.US, "%.5f", loc.longitude ?: 0.0)
                    "${loc.title}_${latStr}_${lonStr}"
                }
            }

            if (!isAdded) return@launch
            val overlay = userLocationsOverlay ?: return@launch
            overlay.items.clear()

            for (loc in locations) {
                val lat = loc.latitude ?: continue
                val lon = loc.longitude ?: continue
                val locTitle = loc.title?.trim()?.ifEmpty { "موقع محفوظ" } ?: "موقع محفوظ"
                val marker = UserLocationMarker(mv, locTitle).apply {
                    position = GeoPoint(lat, lon)
                    title = locTitle
                    infoWindow = null
                    setOnMarkerClickListener { _, _ ->
                        val poiDetails = PoiDetails(
                            name = locTitle,
                            category = "user_location",
                            latitude = lat,
                            longitude = lon
                        )
                        interactionListener?.onMapPointTapped(poiDetails)
                        true
                    }
                }
                overlay.add(marker)
            }
            mv.invalidate()
        }
    }

    private fun notifyScaleChange() {
        val mv = mapView ?: return
        val zoom = mv.zoomLevelDouble
        val lat = mv.mapCenter.latitude
        val cosLat = cos(lat * PI / 180.0)
        val metersPerPixel = 156543.03 * cosLat / 2.0.pow(zoom)
        val density = resources.displayMetrics.density
        val barPixels = 60f * density
        val barWidthMeters = metersPerPixel * barPixels
        val text = when {
            barWidthMeters < 1000 -> "${barWidthMeters.toInt()} m"
            barWidthMeters < 10000 -> "${"%.1f".format(Locale.US, barWidthMeters / 1000)} km"
            barWidthMeters < 100000 -> "${(barWidthMeters / 1000).toInt()} km"
            else -> "${"%.0f".format(Locale.US, barWidthMeters / 1000)} km"
        }
        interactionListener?.onMapScaleChanged(text)
    }

    override fun onResume() {
        super.onResume()
        val mv = mapView ?: return
        mv.onResume()
        // Static North-Up when not actively following a route.
        if (!hasSavedRoute()) {
            try {
                mv.setMapOrientation(0f, false)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "north-up lock failed", e)
            }
        }
        smoothLocationOverlay?.enable()

        // Resume fused updates
        val callback = locationCallback
        val client = fusedLocationClient
        if (client != null && callback != null && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
            client.requestLocationUpdates(req, callback, Looper.getMainLooper())
        }

        // Check if saved route should be restored
        restoreSavedRouteIfExists()

        // Refresh user-saved location pins
        refreshUserLocations()

        // Refresh saved tracks on map
        refreshVisibleTracks()
    }

    override fun onPause() {
        super.onPause()
        mapView?.onPause()
        smoothLocationOverlay?.disable()
        locationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(resumeFollowRunnable)
        poiRefreshJob?.cancel()
        userLocationsRefreshJob?.cancel()
        userLocationsOverlay?.items?.clear()
        userLocationsOverlay = null
        trackOverlay?.clearLiveTrack()
        trackOverlay?.clearAllSavedTracks()
        trackOverlay?.clearBacktrack()
        trackOverlay = null
        routeOverlay = null
        handler.removeCallbacksAndMessages(null)
        locationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
        locationCallback = null
        fusedLocationClient = null
        smoothLocationOverlay?.destroy()
        smoothLocationOverlay = null
        rotationOverlay = null
        mapView?.onDetach()
        mapView = null
    }

    // ======================== Public API ========================

    fun getMapView(): MapView? = mapView
    fun getSmoothLocationOverlay(): SmoothLocationOverlay? = smoothLocationOverlay

    fun animateToLocation(point: GeoPoint, zoom: Double = GPS_FIX_ZOOM, durationMs: Long = GPS_FIX_ANIMATION_MS) {
        mapView?.controller?.animateTo(point, zoom, durationMs)
    }

    fun zoomIn() {
        mapView?.controller?.zoomIn()
    }

    fun zoomOut() {
        mapView?.controller?.zoomOut()
    }

    fun getOrientationMode(): MapOrientationMode = orientationMode

    /**
     * Course-Up mode is disabled app-wide (2026-11-25 — see startNavigation() for why),
     * so this no longer actually toggles anything: it just resets to North-Up and
     * recenters, the same as [resetMapRotation]. Kept (rather than removed) so the
     * existing compass dock button / side-dial button keep a harmless, useful action
     * instead of becoming dead buttons.
     */
    fun toggleOrientationMode(): MapOrientationMode {
        savePreferredOrientationMode(MapOrientationMode.NORTH_UP)
        setOrientationMode(MapOrientationMode.NORTH_UP, showToast = true)
        return MapOrientationMode.NORTH_UP
    }

    /**
     * Sets the active orientation mode. In North-Up, locks mapOrientation to 0°.
     * In Course-Up, dynamically rotates to smoothed travel bearing if actively moving (speed >= 1.5 m/s).
     */
    fun setOrientationMode(mode: MapOrientationMode, showToast: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { setOrientationMode(mode, showToast) }
            return
        }
        orientationMode = mode
        val mv = mapView ?: return
        when (mode) {
            MapOrientationMode.NORTH_UP -> {
                try {
                    mv.setMapOrientation(0f, false)
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "setMapOrientation 0 failed", e)
                }
                interactionListener?.onHeadingUpChanged(false)
                if (showToast && isAdded) {
                    Toast.makeText(requireContext(), "الشمال للأعلى (North-Up)", Toast.LENGTH_SHORT).show()
                }
            }
            MapOrientationMode.COURSE_UP -> {
                val loc = latestLocation
                compassMapHeading = Float.NaN
                if (loc == null || !loc.hasSpeed() || loc.speed < SPEED_DRIVING_THRESHOLD_MPS) {
                    smoothLocationOverlay?.getBearing()?.takeIf { it.isFinite() }?.let { az ->
                        compassMapHeading = az
                        try { mv.setMapOrientation(NavigationMath.mapOrientationFor(az), false) } catch (_: Exception) {}
                    }
                } else if (loc.hasSpeed() && loc.speed >= SPEED_DRIVING_THRESHOLD_MPS) {
                    val rot = NavigationMath.mapOrientationFor(smoothedHeading)
                    try {
                        mv.setMapOrientation(rot, false)
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.w(TAG, "setMapOrientation course-up failed", e)
                    }
                }
                interactionListener?.onHeadingUpChanged(true)
                if (showToast && isAdded) {
                    Toast.makeText(requireContext(), "اتجاه السير (Course-Up)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ======================== Compass rotation & follow helpers ========================

    /**
     * Course-Up is disabled app-wide (2026-11-25), so this always returns NORTH_UP now —
     * even if an older install already saved COURSE_UP to SharedPreferences before this
     * change, it is never honored.
     */
    private fun preferredOrientationMode(): MapOrientationMode = MapOrientationMode.NORTH_UP

    private fun savePreferredOrientationMode(mode: MapOrientationMode) {
        try {
            requireContext().getSharedPreferences(NAV_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PREF_ORIENTATION, mode.name).apply()
        } catch (_: Exception) {}
    }

    private fun scheduleFollowResume() {
        handler.removeCallbacks(resumeFollowRunnable)
        handler.postDelayed(resumeFollowRunnable, AUTO_RESUME_FOLLOW_MS)
    }

    /**
     * Course-Up while stationary or slow: rotate the map with the phone's compass,
     * so the line/arrow toward the target stays pointing the way the phone faces.
     * While driving fast, GPS course takes over (handled in handleNewLocation).
     */
    private fun applyCompassRotation(azimuth: Float) {
        if (orientationMode != MapOrientationMode.COURSE_UP || !azimuth.isFinite()) return
        val mv = mapView ?: return
        if (gpsSensorBlendAlpha > 0f) return // GPS course is driving the rotation
        val loc = latestLocation
        if (loc != null && loc.hasSpeed() && loc.speed >= SPEED_DRIVING_THRESHOLD_MPS && isCarRouteActive) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastCompassRotateMs < COMPASS_ROTATE_MIN_INTERVAL_MS) return
        val prev = compassMapHeading
        val next = if (prev.isFinite()) HeadingFilter.shortestArcSlerp(prev, azimuth, 0.35f) else azimuth
        if (prev.isFinite()) {
            var d = kotlin.math.abs(next - prev) % 360f
            if (d > 180f) d = 360f - d
            if (d < COMPASS_ROTATE_DEADBAND_DEG) return
        }
        compassMapHeading = next
        lastCompassRotateMs = now
        try {
            mv.setMapOrientation(NavigationMath.mapOrientationFor(next), false)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "compass rotation failed", e)
        }
    }

    /** Compass button: reset to North-Up (0°) + recenter on live user location. */
    fun resetMapRotation() {
        setOrientationMode(MapOrientationMode.NORTH_UP, showToast = false)
        getCurrentUserLocation()?.let { animateToLocation(it, GPS_FIX_ZOOM, GPS_FIX_ANIMATION_MS) }
    }

    fun setCenter(lat: Double, lon: Double, zoom: Double) {
        mapView?.controller?.setZoom(zoom)
        mapView?.controller?.setCenter(GeoPoint(lat, lon))
    }

    // ======================== Track Recording & Saved Tracks API ========================

    fun appendTrackPoint(geo: GeoPoint) {
        trackOverlay?.appendLivePoint(geo)
    }

    fun restoreTrackPoints(points: List<GeoPoint>, colorHex: String? = null) {
        trackOverlay?.restoreLiveTrack(points, colorHex)
    }

    fun clearLiveTrack() {
        trackOverlay?.clearLiveTrack()
    }

    fun setLiveTrackColor(colorHex: String) {
        trackOverlay?.setLiveTrackColor(colorHex)
    }

    fun showSavedTrack(trackId: Long, points: List<GeoPoint>, colorHex: String) {
        trackOverlay?.showSavedTrack(trackId, points, colorHex)
    }

    fun hideSavedTrack(trackId: Long) {
        trackOverlay?.hideSavedTrack(trackId)
    }

    fun clearAllSavedTracks() {
        trackOverlay?.clearAllSavedTracks()
    }

    fun showBacktrack(points: List<GeoPoint>) {
        trackOverlay?.showBacktrack(points)
    }

    fun clearBacktrack() {
        trackOverlay?.clearBacktrack()
    }

    fun refreshVisibleTracks() {
        if (!isAdded) return
        val ctx = context ?: return
        lifecycleScope.launch {
            try {
                val repo = com.alruwaili.garminalbarr.track.TrackRepository(ctx)
                val visible = repo.getVisibleTracks()
                trackOverlay?.clearAllSavedTracks()
                for (t in visible) {
                    val pts = repo.getTrackPoints(t.id)
                    val geos = pts.map { GeoPoint(it.latitude, it.longitude) }
                    trackOverlay?.showSavedTrack(t.id, geos, t.colorHex)
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Failed refreshing visible tracks", e)
            }
        }
    }
}
