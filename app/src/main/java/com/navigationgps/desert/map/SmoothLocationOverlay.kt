package com.navigationgps.desert.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.WindowManager
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs

/**
 * High-performance, VSync-gated location & heading overlay.
 *
 * Static North-Up: the map never rotates. A gold vector chevron draws over
 * the live GPS fix with an exact center pivot (translate → rotate → scale),
 * zoom-scaled 75%..125% across zoom 5..19, rotating with the smoothed heading.
 *
 * Heading pipeline (HEADING_V2, default on — see [HeadingFlags]):
 * rotation-vector (fallback: geomagnetic rotation-vector, then accel+mag)
 * @ SENSOR_DELAY_GAME on a dedicated "HeadingSensor" thread,
 * tilt/display remap, adaptive EMA ([HeadingFilter]), single Choreographer
 * frame callback with 0.05° draw gate. Zero invalidates while frozen.
 *
 * Legacy path (flag off): static lerp, preserved for field rollback.
 */
class SmoothLocationOverlay(
    context: Context,
    private val mapView: MapView
) : Overlay(), SensorEventListener {

    companion object {
        private const val TAG = "SmoothLocationOverlay"
        private const val PIN_WIDTH_DP = 32
        private const val PIN_HEIGHT_DP = 44

        // Precision gold chevron: base footprint at zoom 15, scaled 75%..125%
        // across zoom 5 (country overview) .. 19 (trail close-up).
        private const val ARROW_BASE_DP = 48f
        private const val ZOOM_MIN = 5.0
        private const val ZOOM_MAX = 19.0
        private const val SCALE_MIN = 0.75f
        private const val SCALE_SPAN = 0.5f

        /** Zoom-dependent arrow scale, clamped. Pure — unit-tested. */
        fun zoomScaleFor(zoom: Double): Float =
            (SCALE_MIN + ((zoom - ZOOM_MIN) / (ZOOM_MAX - ZOOM_MIN)).coerceIn(0.0, 1.0) * SCALE_SPAN).toFloat()

        // Legacy fallback constants (HEADING_V2 off only).
        private const val LEGACY_SMOOTHING_FACTOR = 0.28f
        private const val LEGACY_DEADBAND_DEG = 0.4f

        private const val DRAW_GATE_DEG = 0.05f
        private const val COMPOSE_THROTTLE_MS = 16L
        private const val FALLBACK_LPF_ALPHA = 0.20f

        // Vehicle-mount tilt guard: beyond this pitch the portrait remap
        // becomes ambiguous and azimuth can flip 180° — hold last heading.
        private const val PITCH_FREEZE_DEG = 65f
    }

    private val appContext: Context = context.applicationContext
    private val filter = HeadingFilter()

    @Volatile private var renderAzimuth = 0f
    @Volatile private var renderDirty = false
    private var lastDrawnAzimuth = Float.NaN
    private var lastSensorNanos = 0L
    private var firstFixAfterEnable = true

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var choreographer: Choreographer? = null
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isEnabled) return
            if (renderDirty) {
                renderDirty = false
                // 0.05° hysteresis on the draw gate: sub-pixel rotations skip a redraw.
                if (lastDrawnAzimuth.isNaN() ||
                    abs(HeadingFilter.shortestArcDelta(renderAzimuth, lastDrawnAzimuth)) > DRAW_GATE_DEG
                ) {
                    lastDrawnAzimuth = renderAzimuth
                    mapView.postInvalidateOnAnimation()
                }
            }
            // Re-arm only while active; callbacks stop when disabled
            // (frozen state simply skips invalidates, near-zero cost).
            if (isEnabled) choreographer?.postFrameCallback(this)
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastComposeNotifyMs = 0L
    @Volatile private var pendingComposeAzimuth = Float.NaN
    private val composeDispatchRunnable = Runnable {
        val az = pendingComposeAzimuth
        pendingComposeAzimuth = Float.NaN
        if (isEnabled && az.isFinite()) {
            onAzimuthChanged?.invoke(az)
        }
    }

    private val remapIn = FloatArray(9)
    private val remapOut = FloatArray(9)
    private var cachedRotation = -1
    private var cachedAxisX = SensorManager.AXIS_X
    private var cachedAxisY = SensorManager.AXIS_Z

    // Fallback LPF state (preallocated, no per-event alloc).
    private val gravityLp = FloatArray(3)
    private var hasGravityLp = false
    private val magneticLp = FloatArray(3)
    private var hasMagneticLp = false
    private val fallbackR = FloatArray(9)
    private val fallbackI = FloatArray(9)

    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private var geomagRotationSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var magnetSensor: Sensor? = null
    private var useRotationVector = false

    private val orientationAngles = FloatArray(3)

    @Volatile private var targetAzimuth = 0f
    @Volatile private var currentAzimuth = 0f
    private var userGeoPoint: GeoPoint? = null
    private var accuracyMeters = 0f
    @Volatile var onAzimuthChanged: ((Float) -> Unit)? = null

    // Route Origin / Destination state (route polyline bearing reference).
    // The arrow itself always draws over the live user GPS location.
    private var routeOriginGeo: GeoPoint? = null
    private var routeDestGeo: GeoPoint? = null
    private var isOriginUserLocation: Boolean = true
    @Volatile private var drivingHeading: Float? = null

    @Volatile private var isEnabled = false
    private var pinBitmap: Bitmap? = null
    private val accuracyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accuracyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Cached gold-chevron paints: zero per-frame alloc at 60 fps.
    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowDotCorePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Cached chevron path (UI thread only): geometry depends on density alone,
    // so rebuild only when density changes — zero alloc across frames.
    private var arrowPath: Path? = null
    private var arrowPathDensity = -1f
    private var arrowPathRadius = 0f
    private val screenPoint = Point()

    init {
        initSensors()
        initPaint()
        initArrowPaints()
        createPinBitmap()
    }

    private fun initSensors() {
        try {
            val sm = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            sensorManager = sm
            if (sm != null) {
                rotationSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                if (rotationSensor != null) {
                    useRotationVector = true
                } else {
                    // Tier 2: geomagnetic rotation vector — firmware tilt-compensated,
                    // lower power than accel+mag polling, same getRotationMatrixFromVector path.
                    geomagRotationSensor =
                        sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
                    if (geomagRotationSensor != null) {
                        useRotationVector = true
                    } else {
                        accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                        magnetSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
                    }
                }
            }
        } catch (e: Exception) {
            if (com.navigationgps.desert.BuildConfig.DEBUG) Log.w(TAG, "Sensor initialization failed", e)
        }
    }

    private fun initPaint() {
        accuracyPaint.apply {
            color = Color.parseColor("#22E53935")
            style = Paint.Style.FILL
        }
        accuracyStrokePaint.apply {
            color = Color.parseColor("#55E53935")
            style = Paint.Style.STROKE
            strokeWidth = 2f * appContext.resources.displayMetrics.density
        }
    }

    /**
     * Classic Red Location Pin (#E53935) with white border and center dot.
     * Anchored at bottom tip (center-x, height).
     */
    private fun createPinBitmap() {
        try {
            val density = appContext.resources.displayMetrics.density
            val w = (PIN_WIDTH_DP * density).toInt().coerceAtLeast(48)
            val h = (PIN_HEIGHT_DP * density).toInt().coerceAtLeast(66)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            val cx = w / 2f
            val r = w * 0.42f
            val cy = r + 2f * density

            val path = Path().apply {
                moveTo(cx, h - 2f * density)
                lineTo(cx - r * 0.85f, cy + r * 0.5f)
                arcTo(cx - r, cy - r, cx + r, cy + r, 150f, 240f, false)
                lineTo(cx, h - 2f * density)
                close()
            }

            // 1. Drop shadow
            paint.color = Color.parseColor("#44000000")
            paint.style = Paint.Style.FILL
            canvas.save()
            canvas.translate(0f, 2f * density)
            canvas.drawPath(path, paint)
            canvas.restore()

            // 2. Thick white stroke
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.5f * density
            paint.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(path, paint)

            // 3. Vibrant Red fill (#E53935)
            paint.color = Color.parseColor("#E53935")
            paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)

            // 4. White inner circle
            paint.color = Color.WHITE
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, r * 0.42f, paint)

            // 5. Center dot (#E53935)
            paint.color = Color.parseColor("#E53935")
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, r * 0.22f, paint)

            pinBitmap = bmp
        } catch (e: Exception) {
            if (com.navigationgps.desert.BuildConfig.DEBUG) Log.w(TAG, "Failed to create pin bitmap", e)
        }
    }

    /**
     * Precision gold navigation chevron, drawn centered at (0, 0).
     * Caller must translate to the GPS screen point, rotate by the smoothed
     * azimuth (0° = North), and scale by [zoomScaleFor] before calling.
     * Stealth geometry: sharp nose, wide wings, recessed tail notch + GPS dot.
     */
    private fun initArrowPaints() {
        arrowShadowPaint.apply {
            color = Color.parseColor("#44000000")
            style = Paint.Style.FILL
        }
        arrowFillPaint.apply {
            color = Color.parseColor("#D4A017") // Desert Gold
            style = Paint.Style.FILL
        }
        arrowStrokePaint.apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        arrowDotPaint.apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        arrowDotCorePaint.apply {
            color = Color.parseColor("#7A5A00")
            style = Paint.Style.FILL
        }
    }

    private fun drawNavigationArrow(canvas: Canvas, density: Float) {
        // Rebuild only on density change; reuse + rewind otherwise.
        var path = arrowPath
        if (path == null || arrowPathDensity != density) {
            val r = ARROW_BASE_DP * density * 0.5f
            path = (path ?: Path()).apply {
                rewind()
                moveTo(0f, -r) // nose → True North at rotation 0
                lineTo(0.72f * r, 0.68f * r) // right wing
                lineTo(0f, 0.32f * r) // recessed tail notch
                lineTo(-0.72f * r, 0.68f * r) // left wing
                close()
            }
            arrowPath = path
            arrowPathDensity = density
            arrowPathRadius = r
        }
        val r = arrowPathRadius
        // 1. Subtle shadow (offset down).
        canvas.save()
        canvas.translate(0f, 2.5f * density)
        canvas.drawPath(path, arrowShadowPaint)
        canvas.restore()
        // 2. Gold fill + 2dp crisp white outline (contrast over sand/satellite).
        canvas.drawPath(path, arrowFillPaint)
        arrowStrokePaint.strokeWidth = 2f * density
        canvas.drawPath(path, arrowStrokePaint)
        // 3. Central GPS anchor dot.
        canvas.drawCircle(0f, 0f, r * 0.19f, arrowDotPaint)
        canvas.drawCircle(0f, 0f, r * 0.10f, arrowDotCorePaint)
    }

    fun setLocation(loc: Location) {
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return
        val prev = userGeoPoint
        val newPoint = GeoPoint(loc.latitude, loc.longitude)
        // Skip redraw on GPS jitter: displacement < 1 m and accuracy change < 2 m.
        if (prev != null) {
            val movedM = prev.distanceToAsDouble(newPoint)
            if (movedM < 1.0 && abs(loc.accuracy - accuracyMeters) < 2f) {
                accuracyMeters = loc.accuracy
                return
            }
        }
        userGeoPoint = newPoint
        accuracyMeters = loc.accuracy
        requestDraw()
    }

    fun getLocation(): GeoPoint? = userGeoPoint

    fun getBearing(): Float = renderAzimuth

    fun setRouteInfo(originGeo: GeoPoint?, destGeo: GeoPoint?, isOriginUser: Boolean) {
        routeOriginGeo = originGeo
        routeDestGeo = destGeo
        isOriginUserLocation = isOriginUser
        requestDraw()
    }

    /**
     * Overrides arrow heading with the GPS-derived driving heading during active navigation.
     * When null, falls back to the sensor-derived renderAzimuth.
     */
    fun setDrivingHeading(heading: Float?) {
        drivingHeading = heading
        requestDraw()
    }

    fun clearRouteInfo() {
        routeOriginGeo = null
        routeDestGeo = null
        isOriginUserLocation = true
        drivingHeading = null
        requestDraw()
    }

    private fun requestDraw() {
        renderDirty = true
    }

    fun enable() {
        if (isEnabled) return
        // Fail before flipping state: a null SensorManager must not leave
        // the overlay half-enabled (isEnabled=true with no sensors/Choreographer).
        val sm = sensorManager ?: return
        isEnabled = true
        firstFixAfterEnable = true
        lastSensorNanos = 0L
        cachedRotation = -1  // invalidate display rotation cache; resolveAxes() will re-query fresh
        if (sensorThread == null) {
            val t = HandlerThread("HeadingSensor").also { it.start() }
            sensorThread = t
            sensorHandler = Handler(t.looper)
        }
        val h = sensorHandler
        try {
            if (useRotationVector) {
                // Tier 1 preferred, Tier 2 fallback — whichever initSensors resolved.
                val vectorSensor = rotationSensor ?: geomagRotationSensor
                vectorSensor?.let {
                    if (h != null) sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, h)
                    else sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
            } else {
                accelSensor?.let {
                    if (h != null) sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, h)
                    else sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
                magnetSensor?.let {
                    if (h != null) sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, h)
                    else sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
            }
        } catch (e: Exception) {
            if (com.navigationgps.desert.BuildConfig.DEBUG) Log.w(TAG, "Failed to register sensor listener", e)
        }
        // Choreographer must be touched on the main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            choreographer = Choreographer.getInstance()
            lastDrawnAzimuth = Float.NaN
            choreographer?.postFrameCallback(frameCallback)
        } else {
            lastDrawnAzimuth = Float.NaN
            mainHandler.post {
                if (isEnabled) {
                    choreographer = Choreographer.getInstance()
                    choreographer?.postFrameCallback(frameCallback)
                }
            }
        }
    }

    fun disable() {
        if (!isEnabled) return
        isEnabled = false
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {}
        try {
            choreographer?.removeFrameCallback(frameCallback)
        } catch (_: Exception) {}
        choreographer = null
        try {
            mainHandler.removeCallbacks(composeDispatchRunnable)
            pendingComposeAzimuth = Float.NaN
        } catch (_: Exception) {}
    }

    fun destroy() {
        disable()
        renderDirty = false
        onAzimuthChanged = null
        mainHandler.removeCallbacksAndMessages(null)
        try {
            sensorHandler?.removeCallbacksAndMessages(null)
        } catch (_: Exception) {}
        try {
            sensorThread?.quitSafely()
        } catch (_: Exception) {}
        sensorThread = null
        sensorHandler = null
        // Recycle on the main thread so an in-flight draw() (UI thread) cannot
        // observe a recycled bitmap. disable() already stopped frame callbacks,
        // and the isRecycled guards in draw() cover any trailing frame.
        val recycle = {
            try {
                pinBitmap?.recycle()
            } catch (_: Exception) {}
            pinBitmap = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) recycle() else mainHandler.post { recycle() }
        sensorManager = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isEnabled) return
        // Runs on the "HeadingSensor" thread — never touch Views directly here.
        try {
            if (!HeadingFlags.HEADING_V2_ENABLED) {
                onSensorChangedLegacy(event)
                return
            }
            val isVectorEvent = event.sensor.type == Sensor.TYPE_ROTATION_VECTOR ||
                event.sensor.type == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
            val target: Float = if (useRotationVector && isVectorEvent) {
                azimuthFromRotationVector(event.values) ?: return
            } else {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        if (!hasGravityLp) {
                            event.values.copyInto(gravityLp)
                            hasGravityLp = true
                            return
                        }
                        lowPassInto(event.values, gravityLp, FALLBACK_LPF_ALPHA)
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        if (!hasMagneticLp) {
                            event.values.copyInto(magneticLp)
                            hasMagneticLp = true
                            return
                        }
                        lowPassInto(event.values, magneticLp, FALLBACK_LPF_ALPHA)
                    }
                    // Ignore vector/unknown events on the accel+mag tier: computing
                    // from stale gravity/mag here would publish a warped azimuth.
                    else -> return
                }
                if (!hasGravityLp || !hasMagneticLp) return
                if (!SensorManager.getRotationMatrix(fallbackR, fallbackI, gravityLp, magneticLp)) return
                azimuthFromMatrix(fallbackR) ?: return
            }

            // Tilt guard: portrait remap flips azimuth 180° when the phone is
            // tilted past horizontal (vehicle mount wobble). Hold last heading
            // instead of publishing a backward arrow. firstFixAfterEnable is
            // intentionally left set so the next level sample snaps cleanly.
            if (isPitchExtreme()) return

            val now = event.timestamp // ns, boot-time base
            val dtSec = if (lastSensorNanos == 0L) 0.02f
            else ((now - lastSensorNanos) / 1e9).toFloat().coerceIn(0.008f, 0.10f)
            lastSensorNanos = now

            if (firstFixAfterEnable) {
                // Snap on resume: no sweep-from-stale-value spin.
                firstFixAfterEnable = false
                filter.snapTo(target)
                publish(target)
                return
            }
            // Frozen → null → publish nothing: zero invalidates at rest.
            filter.update(target, dtSec)?.let { publish(it) }
        } catch (e: Exception) {
            if (com.navigationgps.desert.BuildConfig.DEBUG) Log.w(TAG, "heading sample dropped", e)
        }
    }

    /** Legacy static-lerp path, kept for HEADING_V2 rollback. */
    private fun onSensorChangedLegacy(event: SensorEvent) {
        val isVectorEvent = event.sensor.type == Sensor.TYPE_ROTATION_VECTOR ||
            event.sensor.type == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
        if (useRotationVector && isVectorEvent) {
            // Tilt-compensated like V2: portrait remap via resolveAxes() so
            // 30-60° hand/car-mount pitch does not warp or invert azimuth.
            // (Previously raw getOrientation without remap — fixed.)
            val deg = azimuthFromRotationVector(event.values) ?: return
            targetAzimuth = deg
            legacyApplySmoothing()
        } else {
            // Note: legacy path keeps unfiltered copies (rollback only, not the
            // hot path) but MUST use the same tilt-compensated remap as V2 —
            // raw getOrientation warps at 30-60° hand/car-mount pitch.
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> gravityLp.let {
                    event.values.copyInto(gravityLp); hasGravityLp = true
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    event.values.copyInto(magneticLp); hasMagneticLp = true
                }
                else -> return
            }
            if (!hasGravityLp || !hasMagneticLp) return
            // Reuse V2 scratch buffers — no per-sample alloc on sensor thread.
            if (SensorManager.getRotationMatrix(fallbackR, fallbackI, gravityLp, magneticLp)) {
                val deg = azimuthFromMatrix(fallbackR) ?: return
                targetAzimuth = deg
                legacyApplySmoothing()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (!HeadingFlags.HEADING_V2_ENABLED) return
        // Degrade gracefully near metal instead of jumping.
        filter.accuracyScale = when (accuracy) {
            SensorManager.SENSOR_STATUS_UNRELIABLE -> 0.35f
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 0.6f
            else -> 1.0f
        }
        filter.deadbandStable = if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) 1.8f else 0.9f
    }

    private fun legacyApplySmoothing() {
        // Same tilt guard as the V2 path: never smooth toward a flipped sample.
        if (isPitchExtreme()) return
        // Shortest-arc angular difference (kept verbatim — pinned by unit test).
        val diff = ((targetAzimuth - currentAzimuth + 540f) % 360f) - 180f
        if (abs(diff) > LEGACY_DEADBAND_DEG) {
            val next = (currentAzimuth + diff * LEGACY_SMOOTHING_FACTOR + 360f) % 360f
            publish(next)
        }
    }

    private fun publish(az: Float) {
        renderAzimuth = az
        renderDirty = true
        currentAzimuth = az // keep getBearing()/legacy contract; volatile write
        // Throttle Compose propagation to ~60 Hz; Canvas stays full-rate via Choreographer.
        val nowMs = SystemClock.uptimeMillis()
        if (nowMs - lastComposeNotifyMs >= COMPOSE_THROTTLE_MS) {
            lastComposeNotifyMs = nowMs
            if (onAzimuthChanged != null && isEnabled) {
                val wasPending = pendingComposeAzimuth.isFinite()
                pendingComposeAzimuth = az
                if (!wasPending) {
                    mainHandler.post(composeDispatchRunnable)
                }
            }
        }
    }

    // ---- orientation helpers (zero-alloc, cached display rotation) ----
    private fun resolveAxes(): Pair<Int, Int> {
        val rot = try {
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        } catch (_: Exception) {
            Surface.ROTATION_0
        }
        if (rot == cachedRotation) return cachedAxisX to cachedAxisY
        val (x, y) = when (rot) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Z to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Z
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Z to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Z
        }
        cachedRotation = rot
        cachedAxisX = x
        cachedAxisY = y
        return x to y
    }

    private fun azimuthFromRotationVector(v: FloatArray): Float? {
        SensorManager.getRotationMatrixFromVector(remapIn, v)
        return azimuthFromMatrix(remapIn)
    }

    private fun azimuthFromMatrix(r: FloatArray): Float? {
        val (ax, ay) = resolveAxes()
        if (!SensorManager.remapCoordinateSystem(r, ax, ay, remapOut)) return null
        SensorManager.getOrientation(remapOut, orientationAngles)
        var deg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        if (deg < 0f) deg += 360f
        return deg
    }

    /**
     * True when device tilt is past the tilt-guard threshold.
     * Pitch/roll are read from the REMAPPED portrait frame (AXIS_X → AXIS_Z),
     * not raw device axes — the 65° threshold is calibrated for that frame.
     * Reads [orientationAngles] filled by the most recent `getOrientation`
     * call on this (sensor) thread — never call from the UI thread.
     *
     * Portrait remap keeps azimuth stable at normal hand/car-mount pitch
     * (30–60° stays live). Past horizontal the remap goes ambiguous and
     * azimuth can flip 180°, so we hold last heading instead of publishing
     * a backward arrow. Roll is checked too: side-tilt warps azimuth the
     * same way.
     */
    private fun isPitchExtreme(): Boolean {
        val pitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
        val rollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
        return abs(pitchDeg) > PITCH_FREEZE_DEG || abs(rollDeg) > PITCH_FREEZE_DEG
    }

    private fun lowPassInto(input: FloatArray, out: FloatArray, alpha: Float) {
        out[0] += alpha * (input[0] - out[0])
        out[1] += alpha * (input[1] - out[1])
        out[2] += alpha * (input[2] - out[2])
    }

    override fun draw(canvas: Canvas?, projection: org.osmdroid.views.Projection?) {
        if (canvas == null || projection == null) return

        // Static North-Up: the map never rotates, so the gold chevron over the
        // live GPS fix rotates freely with the smoothed physical heading.
        // Exact center pivot: translate to the GPS pixel, rotate about (0, 0),
        // scale by zoom, draw the chevron centered at (0, 0).
        userGeoPoint?.let { userPoint ->
            projection.toPixels(userPoint, screenPoint)
            val ux = screenPoint.x.toFloat()
            val uy = screenPoint.y.toFloat()

            // Draw accuracy circle (unrotated, unscaled).
            if (accuracyMeters in 5f..500f) {
                val metersPerPixel = projection.metersToEquatorPixels(1f)
                val radiusPx = accuracyMeters * metersPerPixel
                if (radiusPx > 10f) {
                    canvas.drawCircle(ux, uy, radiusPx, accuracyPaint)
                    canvas.drawCircle(ux, uy, radiusPx, accuracyStrokePaint)
                }
            }

            // Read driving heading if actively navigating, otherwise fall back to sensor azimuth.
            val heading = drivingHeading ?: renderAzimuth
            // Osmdroid rotates the canvas around the center by mapOrientation.
            // Drawing the chevron at `heading` in world coordinates keeps it pointing
            // in the exact world travel direction: in Heading-Up (map rotated by -heading),
            // heading + (-heading) = 0° (straight UP at 12 o'clock along the road).
            // In North-Up (mapOrientation = 0), it rotates freely to heading.
            val rotation = heading
            val zoom = try {
                mapView.zoomLevelDouble
            } catch (_: Exception) {
                15.0
            }
            val s = zoomScaleFor(zoom)
            val density = appContext.resources.displayMetrics.density

            canvas.save()
            canvas.translate(ux, uy)
            canvas.rotate(rotation)
            canvas.scale(s, s)
            drawNavigationArrow(canvas, density)
            canvas.restore()
        }

        // Destination POI marker: classic red location pin
        routeDestGeo?.let { destPoint ->
            drawPin(canvas, destPoint, projection, screenPoint)
        }

        // Custom origin marker (only rendered when origin is NOT the user GPS location)
        if (!isOriginUserLocation) {
            routeOriginGeo?.let { originPoint ->
                drawPin(canvas, originPoint, projection, screenPoint)
            }
        }
    }

    private fun drawPin(
        canvas: Canvas,
        point: GeoPoint,
        projection: org.osmdroid.views.Projection,
        screenPoint: Point
    ) {
        val pin = pinBitmap?.takeUnless { it.isRecycled } ?: return
        projection.toPixels(point, screenPoint)
        val x = screenPoint.x.toFloat()
        val y = screenPoint.y.toFloat()
        val halfW = pin.width / 2f
        val h = pin.height.toFloat()
        canvas.drawBitmap(pin, x - halfW, y - h, null)
    }
}
