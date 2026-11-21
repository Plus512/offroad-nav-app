package com.alruwaili.dalilalbar

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.alruwaili.dalilalbar.databinding.ActivityMainBinding
import android.os.Handler
import android.os.Looper
import com.alruwaili.dalilalbar.nav.GeoMath
import com.alruwaili.dalilalbar.nav.LocationLabelStyle
import com.alruwaili.dalilalbar.nav.LocationsRepository
import com.alruwaili.dalilalbar.nav.NavMode
import com.alruwaili.dalilalbar.nav.RouteOutcome
import com.alruwaili.dalilalbar.nav.RoutingEngine
import com.alruwaili.dalilalbar.nav.TrackRecorder
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.scalebar.ScaleBarOptions
import org.maplibre.android.plugins.scalebar.ScaleBarPlugin
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * الشاشة الرئيسية — «دليل البر»: خريطة متجهة (vector) من بيانات OpenFreeMap/OpenStreetMap الحقيقية،
 * بنمط ألوان مخصّص (خلفية رملية، غطاء نباتي أخضر زاهٍ، شعاب/أودية بارزة بلون ولون خط محسَّنين، تسميات
 * عربية) — كل ذلك مرسوم من الهندسة الحقيقية لبيانات OpenStreetMap نفسها (ملف style_dalil.json)،
 * وليس رسماً توضيحياً كما في فيديوهات العرض التمثيلية فقط.
 *
 * تضيف هذه النسخة: اللمس على الخريطة لعرض المسافة واختيار نوع الملاحة (خط مستقيم/سيارة)، محرك
 * مسارات حقيقي يجلب شبكة الطرق/الدروب من Overpass API (خدمة OpenStreetMap الرسمية) ويفضّل الطريق
 * المعبّد ثم الدرب (ردمية) ثم يتراجع لخط مستقيم عند انعدام الاتصال الحقيقي بين النقطتين، تسجيل مسار
 * فعلي كملف GPX، وبوصلة مخصّصة تشير نحو الوجهة المختارة وتفتح عرضاً منقسماً (خريطة + بوصلة) عند الضغط.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var locationManager: LocationManager
    private var firstFixHandled = false

    private val firstFixListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!firstFixHandled) {
                firstFixHandled = true
                binding.status.visibility = View.GONE
                mapLibreMapOrNull?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 15.0)
                )
            }
            locationManager.removeUpdates(this)
        }
    }

    /** تحديثات موقع مستمرة (بخلاف firstFixListener أحادي المرة): تغذّي تسجيل المسار وبوصلة الوجهة. */
    private val continuousLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            if (trackRecorder.isRecording) trackRecorder.addFix(location)
            refreshCompassAndPanel()
        }
    }

    private var mapLibreMapOrNull: MapLibreMap? = null
    private var loadedStyle: Style? = null
    private var locationComponentActive = false
    private var lastLocation: Location? = null

    private lateinit var offlineController: OfflineRegionsController
    private val downloadedRegionIds = mutableSetOf<String>()
    private val downloadingRegionIds = mutableSetOf<String>()
    private var lastOfflinePromptKey: String? = null

    private lateinit var trackRecorder: TrackRecorder
    private var activeDestination: LatLng? = null
    private var splitViewVisible = false

    private lateinit var locationsRepository: LocationsRepository
    private var lastLocationsQueryKey: String? = null
    private var locationsQueryInFlight = false

    // ===== اتجاه الجهاز الحقيقي (بوصلة فعلية تدور مع دوران الهاتف، وليست رقماً ثابتاً) =====
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    /** زاوية الشمال المغناطيسي/الحقيقي الحالية بالنسبة لاتجاه الهاتف، محدَّثة من حسّاس الجهاز فعلياً. */
    private var deviceAzimuthDegrees: Float = 0f
    private val rotationRaw = FloatArray(9)
    private val orientationRaw = FloatArray(3)

    private val orientationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotationRaw, event.values)
            SensorManager.getOrientation(rotationRaw, orientationRaw)
            val azimuthDeg = Math.toDegrees(orientationRaw[0].toDouble())
            deviceAzimuthDegrees = (((azimuthDeg + 360.0) % 360.0)).toFloat()
            refreshCompassAndPanel()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) {
                startLocating()
            } else {
                binding.status.text = getString(R.string.need_location_perm)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        MapLibre.getInstance(this)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.map.onCreate(savedInstanceState)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        trackRecorder = TrackRecorder(applicationContext)
        locationsRepository = LocationsRepository(applicationContext)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        offlineController = OfflineRegionsController(applicationContext)
        offlineController.listDownloadedIds { ids -> downloadedRegionIds.addAll(ids) }

        binding.map.getMapAsync { map ->
            mapLibreMapOrNull = map
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(24.7136, 46.6753)) // الرياض كمركز افتراضي قبل تحديد الموقع
                .zoom(5.0)
                .build()
            map.setStyle(Style.Builder().fromUri("asset://style_dalil.json")) { style ->
                loadedStyle = style
                tryActivateLocationComponent()
                setupNavigationLayers(style)
                setupLocationsLabelsLayer(style)
            }

            // نُعطّل بوصلة المكتبة الجاهزة لأننا نستخدم بوصلتنا المخصّصة (compassArrow) التي نتحكم
            // بزاويتها بالكامل (شمال افتراضياً، أو اتجاه الوجهة المختارة فعلياً) وبحدث ضغط خاص بها.
            map.uiSettings.isCompassEnabled = false

            setupScaleBar(map)

            map.addOnMapClickListener { point ->
                onMapTapped(point)
                true
            }

            // عند فتح التطبيق (بعد تحديد موقع المستخدم) وعند كل تكبير/تصغير يغيّر المنطقة المعروضة:
            // تحقّق هل تقع ضمن مستطيل منطقة غير محمَّلة دون انترنت، واعرض سؤال التحميل إن كان الأمر كذلك.
            map.addOnCameraIdleListener {
                checkOfflineRegionsForVisibleArea(map)
                updateLocationLabels(map)
            }
        }

        binding.fabLocate.setOnClickListener { centerOnMyLocation() }
        binding.fabTrackRecord.setOnClickListener { toggleTrackRecording() }
        binding.fabMenu.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.compassArrow.setOnClickListener { toggleSplitView() }
        binding.fabCloseCompassPanel.setOnClickListener { toggleSplitView() }
        binding.fabEndTrip.setOnClickListener { endTrip() }

        if (hasLocationPermission()) {
            startLocating()
        } else {
            requestLocationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("MissingPermission")
    private fun tryActivateLocationComponent() {
        if (locationComponentActive) return
        val map = mapLibreMapOrNull ?: return
        val style = loadedStyle ?: return
        if (!hasLocationPermission()) return

        val locationComponent = map.locationComponent
        locationComponent.activateLocationComponent(
            LocationComponentActivationOptions.builder(this, style)
                .useDefaultLocationEngine(true)
                .build()
        )
        locationComponent.isLocationComponentEnabled = true
        locationComponent.cameraMode = CameraMode.TRACKING
        locationComponent.renderMode = RenderMode.COMPASS
        locationComponentActive = true
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startLocating() {
        tryActivateLocationComponent()
        val providers = locationManager.getProviders(true)
        for (provider in providers) {
            locationManager.getLastKnownLocation(provider)?.let { firstFixListener.onLocationChanged(it) }
        }
        if (!firstFixHandled) {
            for (provider in providers) {
                locationManager.requestLocationUpdates(provider, 1000L, 0f, firstFixListener)
            }
        }
        for (provider in providers) {
            locationManager.requestLocationUpdates(provider, 2000L, 5f, continuousLocationListener)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** مقياس الرسم أسفل يمين الشاشة، فوق شريط الإسناد مباشرة. */
    private fun setupScaleBar(map: MapLibreMap) {
        val ratio = 0.32f
        val rightInset = dp(16)
        val bottomInset = dp(56) // يترك مساحة فوق شريط الإسناد السفلي
        val widget = ScaleBarPlugin(binding.map, map).create(
            ScaleBarOptions(this)
                .setTextColor(android.R.color.black)
                .setTextSize(28f)
                .setBarHeight(4f)
                .setMaxWidthRatio(ratio)
                .setMarginLeft(0f)
                .setMarginTop(0f)
        )
        binding.map.post {
            val barWidthPx = (binding.map.width * ratio).toInt()
            widget.setMarginLeft((binding.map.width - barWidthPx - rightInset).toFloat())
            widget.setMarginTop((binding.map.height - bottomInset).toFloat())
        }
    }

    // ===================== الملاحة: اللمس على الخريطة + المسار + البوصلة =====================

    private fun onMapTapped(point: LatLng) {
        val loc = lastLocation
        if (!locationComponentActive || loc == null) {
            Toast.makeText(this, R.string.need_user_location, Toast.LENGTH_SHORT).show()
            return
        }
        val userLatLng = LatLng(loc.latitude, loc.longitude)
        val distance = GeoMath.distanceMeters(userLatLng, point)
        val placeName = findPlaceNameAt(point)
        showNavigationChoiceDialog(userLatLng, point, distance, placeName)
    }

    /**
     * يبحث عن اسم موقع عند نقطة اللمس، إن وُجد: أولاً من طبقة مواقع المستخدم الخاصة (locations.db)،
     * ثم من طبقات الأماكن/المعالم في الخريطة الأساسية نفسها (poi/place من style_dalil.json، بنفس
     * ترتيب أولوية الاسم المستخدَم في تلك الطبقات: name:ar ثم name:nonlatin ثم name_en ثم name).
     * يُعيد null إن لم يُلمَس أي عنصر مسمّى فعلياً (لا تخمين لاسم غير موجود).
     */
    private fun findPlaceNameAt(point: LatLng): String? {
        val map = mapLibreMapOrNull ?: return null
        val screenPoint = map.projection.toScreenLocation(point)
        val tolerance = dp(24).toFloat()
        val rect = RectF(
            screenPoint.x - tolerance, screenPoint.y - tolerance,
            screenPoint.x + tolerance, screenPoint.y + tolerance
        )

        map.queryRenderedFeatures(rect, "locations-labels-layer").firstOrNull()?.let { feature ->
            val title = feature.getStringProperty("title")
            if (!title.isNullOrBlank()) return title
        }

        val styleLabelLayers = arrayOf(
            "poi_r20", "poi_r7", "poi_r1", "poi_transit",
            "label_other", "label_village", "label_town", "label_city"
        )
        val features = map.queryRenderedFeatures(rect, *styleLabelLayers)
        for (feature in features) {
            val name = feature.getStringProperty("name:ar")
                ?: feature.getStringProperty("name:nonlatin")
                ?: feature.getStringProperty("name_en")
                ?: feature.getStringProperty("name")
            if (!name.isNullOrBlank()) return name
        }
        return null
    }

    private fun showNavigationChoiceDialog(user: LatLng, destination: LatLng, distance: Double, placeName: String?) {
        val title = if (placeName != null) {
            getString(R.string.distance_to_named_point, placeName, GeoMath.formatDistance(distance))
        } else {
            getString(R.string.distance_to_point, GeoMath.formatDistance(distance))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(arrayOf(getString(R.string.mode_straight), getString(R.string.mode_car))) { _, which ->
                val mode = if (which == 0) NavMode.STRAIGHT else NavMode.CAR
                startRouting(user, destination, mode)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startRouting(user: LatLng, destination: LatLng, mode: NavMode) {
        activeDestination = destination
        binding.fabEndTrip.visibility = View.VISIBLE
        binding.status.visibility = View.VISIBLE
        binding.status.text = getString(R.string.computing_route)
        RoutingEngine.computeRoute(user, destination, mode) { outcome ->
            binding.status.visibility = View.GONE
            drawRouteOutcome(outcome)
            drawDestinationMarker(destination)
            refreshCompassAndPanel()
            outcome.fallbackReason?.let { reason -> Toast.makeText(this, reason, Toast.LENGTH_LONG).show() }
        }
    }

    /** ينهي الرحلة النشطة: يمسح المسار المرسوم وعلامة الوجهة، ويعيد البوصلة واللوحة لوضعهما الافتراضي. */
    private fun endTrip() {
        activeDestination = null
        splitViewVisible = false
        binding.compassPanel.visibility = View.GONE
        binding.fabCloseCompassPanel.visibility = View.GONE
        binding.fabEndTrip.visibility = View.GONE
        val style = loadedStyle
        style?.getSourceAs<GeoJsonSource>("route-solid-source")?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        style?.getSourceAs<GeoJsonSource>("route-straight-source")?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        style?.getSourceAs<GeoJsonSource>("dest-marker-source")?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        refreshCompassAndPanel()
        Toast.makeText(this, R.string.trip_ended, Toast.LENGTH_SHORT).show()
    }

    /** يضيف طبقتي خط المسار (حلّة واحدة ملوّنة حسب معبّد/درب) وخط الاتصال المستقيم (منقّط)، وطبقة علامة الوجهة. */
    private fun setupNavigationLayers(style: Style) {
        val solidSource = GeoJsonSource("route-solid-source", FeatureCollection.fromFeatures(emptyArray()))
        style.addSource(solidSource)
        style.addLayer(
            LineLayer("route-solid-layer", "route-solid-source").withProperties(
                PropertyFactory.lineWidth(7f),
                PropertyFactory.lineColor(
                    Expression.match(
                        Expression.get("cls"),
                        Expression.literal("#8B4513"),
                        Expression.stop("paved", Expression.literal("#D32F2F")),
                        Expression.stop("track", Expression.literal("#8B4513"))
                    )
                )
            )
        )

        val straightSource = GeoJsonSource("route-straight-source", FeatureCollection.fromFeatures(emptyArray()))
        style.addSource(straightSource)
        style.addLayer(
            LineLayer("route-straight-layer", "route-straight-source").withProperties(
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineColor("#202020"),
                // كان هذا النمط مكتوباً خطأً [0, 2] سابقاً (أي خط بطول صفر) فكان الخط غير مرئي تماماً
                // رغم رسمه فعلياً في الخلفية — هذا هو السبب الحقيقي وراء "الخط المستقيم لا يظهر".
                PropertyFactory.lineDasharray(arrayOf(2f, 2f))
            )
        )

        val destSource = GeoJsonSource("dest-marker-source", FeatureCollection.fromFeatures(emptyArray()))
        style.addSource(destSource)
        style.addLayer(
            CircleLayer("dest-marker-layer", "dest-marker-source").withProperties(
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleColor("#D32F2F"),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f)
            )
        )
    }

    private fun drawRouteOutcome(outcome: RouteOutcome) {
        val style = loadedStyle ?: return
        val solidFeatures = ArrayList<Feature>()
        val straightFeatures = ArrayList<Feature>()
        for (segment in outcome.segments) {
            if (segment.points.size < 2) continue
            val line = LineString.fromLngLats(segment.points.map { Point.fromLngLat(it.longitude, it.latitude) })
            val feature = Feature.fromGeometry(line)
            if (segment.cls == "straight") {
                straightFeatures.add(feature)
            } else {
                feature.addStringProperty("cls", segment.cls)
                solidFeatures.add(feature)
            }
        }
        style.getSourceAs<GeoJsonSource>("route-solid-source")
            ?.setGeoJson(FeatureCollection.fromFeatures(solidFeatures))
        style.getSourceAs<GeoJsonSource>("route-straight-source")
            ?.setGeoJson(FeatureCollection.fromFeatures(straightFeatures))
    }

    private fun drawDestinationMarker(destination: LatLng) {
        val style = loadedStyle ?: return
        val feature = Feature.fromGeometry(Point.fromLngLat(destination.longitude, destination.latitude))
        style.getSourceAs<GeoJsonSource>("dest-marker-source")
            ?.setGeoJson(FeatureCollection.fromFeatures(arrayOf(feature)))
    }

    // ===================== أسماء مواقع المستخدم (locations.db) على الخريطة =====================

    /**
     * يضيف طبقة SymbolLayer لأسماء المواقع من قاعدة بيانات المستخدم، بنفس مواصفة «الدليلة بلس» تماماً
     * (انظر LocationLabelStyle): خط Noto Sans Bold (أقرب خط متاح لـ .heavy في iOS)، نص أسود بحدّ أبيض
     * (halo) بعرض يقارب ما استُخدم في iOS، تحجيم تدريجي مع التكبير من 15.5 إلى ما يقارب 20.9، وبلا أي
     * علامة/نقطة — نص فقط. لا تظهر الطبقة أصلاً قبل حدّ التكبير المحسوب من معادلة iOS نفسها.
     */
    private fun setupLocationsLabelsLayer(style: Style) {
        val source = GeoJsonSource("locations-labels-source", FeatureCollection.fromFeatures(emptyArray()))
        style.addSource(source)
        val layer = SymbolLayer("locations-labels-layer", "locations-labels-source").withProperties(
            PropertyFactory.textField(Expression.get("title")),
            PropertyFactory.textFont(arrayOf(LocationLabelStyle.FONT_STACK_PRIMARY)),
            PropertyFactory.textSize(
                Expression.interpolate(
                    Expression.linear(),
                    Expression.zoom(),
                    Expression.stop(LocationLabelStyle.MIN_VISIBLE_ZOOM, Expression.literal(LocationLabelStyle.BASE_TEXT_SIZE)),
                    Expression.stop(LocationLabelStyle.MAX_TEXT_SIZE_ZOOM, Expression.literal(LocationLabelStyle.MAX_TEXT_SIZE))
                )
            ),
            PropertyFactory.textColor("#000000"),
            PropertyFactory.textHaloColor("#FFFFFF"),
            PropertyFactory.textHaloWidth(1.3f),
            PropertyFactory.textAllowOverlap(false),
            PropertyFactory.textIgnorePlacement(false)
        )
        layer.setMinZoom(LocationLabelStyle.MIN_VISIBLE_ZOOM)
        style.addLayer(layer)
    }

    /**
     * يُستدعى عند كل توقّف للكاميرا: إن كان التكبير الحالي أقل من حدّ ظهور الأسماء (LocationLabelStyle)،
     * تُفرَّغ الطبقة فوراً (لا تظهر أسماء أصلاً)؛ وإلا يُستعلم عن مواقع المنطقة المرئية فقط من قاعدة
     * بيانات المستخدم على خيط خلفية (SQLite + حتى ٣١٤٬٨٥٦ صفاً لا تُقرأ كلها، فقط ضمن حدود الشاشة)،
     * مع قطع الأسماء الطويلة عند ١٨ حرفاً كما في الدليلة بلس بالضبط.
     */
    private fun updateLocationLabels(map: MapLibreMap) {
        val style = loadedStyle ?: return
        val zoom = map.cameraPosition.zoom
        if (zoom < LocationLabelStyle.MIN_VISIBLE_ZOOM) {
            lastLocationsQueryKey = null
            style.getSourceAs<GeoJsonSource>("locations-labels-source")
                ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            return
        }
        val bounds = map.projection.visibleRegion.latLngBounds
        val key = "%.4f,%.4f,%.4f,%.4f".format(
            bounds.latitudeSouth, bounds.longitudeWest, bounds.latitudeNorth, bounds.longitudeEast
        )
        if (key == lastLocationsQueryKey || locationsQueryInFlight) return
        lastLocationsQueryKey = key
        locationsQueryInFlight = true

        Thread {
            val rows = locationsRepository.queryInBounds(
                south = bounds.latitudeSouth,
                west = bounds.longitudeWest,
                north = bounds.latitudeNorth,
                east = bounds.longitudeEast,
                limit = LocationLabelStyle.MAX_LABELS_QUERY_LIMIT
            )
            val features = rows.map { row ->
                val feature = Feature.fromGeometry(Point.fromLngLat(row.longitude, row.latitude))
                feature.addStringProperty("title", LocationLabelStyle.truncateTitle(row.title))
                feature
            }
            Handler(Looper.getMainLooper()).post {
                locationsQueryInFlight = false
                loadedStyle?.getSourceAs<GeoJsonSource>("locations-labels-source")
                    ?.setGeoJson(FeatureCollection.fromFeatures(features))
            }
        }.start()
    }

    /**
     * يحدّث اتجاه السهم ونص لوحة البوصلة. الاتجاه الجغرافي الفعلي (نحو الشمال افتراضياً، أو نحو
     * الوجهة بعد اختيارها) يُحسب من الإحداثيات كما كان، لكن زاوية رسم السهم على الشاشة الآن تُطرح
     * منها زاوية اتجاه الجهاز الحقيقية (deviceAzimuthDegrees، من حسّاس دوران الجهاز الفعلي) — فيصبح
     * السهم بوصلة فعلية تدور مع دوران الهاتف (يشير دائماً إلى الاتجاه الحقيقي بصرف النظر عن وضعية
     * إمساكك للجهاز)، لا رقماً ثابتاً كما كان سابقاً. النص (الدرجة ونص الجهة) يبقى الاتجاه الجغرافي
     * الموضوعي نفسه (غير متأثر بدوران الجهاز)، لأنه قراءة معلوماتية لا إبرة بصرية.
     */
    private fun refreshCompassAndPanel() {
        val destination = activeDestination
        val loc = lastLocation
        val hasTarget = destination != null && loc != null
        val targetBearing = if (hasTarget) {
            GeoMath.bearingDegrees(LatLng(loc!!.latitude, loc.longitude), destination!!)
        } else {
            0.0 // لا وجهة مختارة: تعمل كبوصلة عادية تشير إلى الشمال الحقيقي
        }
        val displayHeading = (((targetBearing - deviceAzimuthDegrees) % 360.0) + 360.0) % 360.0

        binding.compassArrow.headingDegrees = displayHeading.toFloat()
        binding.compassArrow.pointsToDestination = hasTarget

        if (splitViewVisible && hasTarget) {
            val userLatLng = LatLng(loc!!.latitude, loc.longitude)
            val distance = GeoMath.distanceMeters(userLatLng, destination!!)
            binding.compassPanel.bearingDegrees = displayHeading.toFloat()
            binding.compassPanel.bearingLabelText =
                "الاتجاه: ${Math.round(targetBearing)} درجة ${GeoMath.compassLabelArabic(targetBearing)}"
            binding.compassPanel.distanceText = "المسافة المتبقية: ${GeoMath.formatDistance(distance)}"
            binding.compassPanel.elevationText = if (loc.hasAltitude()) {
                "الارتفاع: ${Math.round(loc.altitude)} م"
            } else {
                "الارتفاع: غير متاح من الجهاز حالياً"
            }
        }
    }

    private fun toggleSplitView() {
        if (activeDestination == null) return
        splitViewVisible = !splitViewVisible
        binding.compassPanel.visibility = if (splitViewVisible) View.VISIBLE else View.GONE
        binding.fabCloseCompassPanel.visibility = if (splitViewVisible) View.VISIBLE else View.GONE
        if (splitViewVisible) refreshCompassAndPanel()
    }

    private fun toggleTrackRecording() {
        if (trackRecorder.isRecording) {
            val file = trackRecorder.stopAndSave()
            binding.fabTrackRecord.setImageResource(android.R.drawable.ic_menu_edit)
            if (file != null) {
                Toast.makeText(this, getString(R.string.recording_stopped_saved, file.name), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, R.string.recording_stopped_empty, Toast.LENGTH_SHORT).show()
            }
        } else {
            trackRecorder.start()
            binding.fabTrackRecord.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            Toast.makeText(this, R.string.recording_started, Toast.LENGTH_SHORT).show()
        }
    }

    // ===================== تحميل الخريطة دون انترنت (من دفعة سابقة، دون تعديل) =====================

    /**
     * أول مرة فقط في عمر التطبيق: تُعرض قائمة بكل الدول/المناطق المتاحة للتحميل دفعة واحدة (بصرف
     * النظر عن منطقة العرض الحالية)، ولا تُعرض هذه القائمة الكاملة مرة أخرى أبداً بعد ذلك (محفوظة
     * في SharedPreferences، بصرف النظر عن اختيار المستخدم). في كل مرة لاحقة: يُفحص فقط موقع المستخدم
     * الفعلي الحالي (lastLocation)، وإن كانت الدولة التي يتواجد فيها غير محمَّلة دون انترنت ولا يجري
     * تحميلها الآن، يُعرض سؤال تحميل تلك الدولة وحدها (لا قائمة الدول الأخرى). يتكرر هذا الفحص تلقائياً
     * مع كل توقّف للكاميرا.
     */
    private fun checkOfflineRegionsForVisibleArea(map: MapLibreMap) {
        val prefs = getSharedPreferences("dalilalbar_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("shown_initial_offline_list", false)) {
            // أول مرة فقط في عمر التطبيق: تُعرض كل الدول دفعة واحدة، بصرف النظر عن المنطقة المرئية حالياً،
            // ولا يُعاد عرض هذه القائمة الكاملة مطلقاً بعد ذلك (العلامة محفوظة دائماً، حتى لو أُغلقت دون تحميل).
            prefs.edit().putBoolean("shown_initial_offline_list", true).apply()
            val allCandidates = OfflineRegionDef.entries
                .filterNot { it.id in downloadedRegionIds || it.id in downloadingRegionIds }
            if (allCandidates.isNotEmpty()) {
                promptDownload(allCandidates)
            }
            return
        }
        // بعد المرة الأولى: لا تُعرض القائمة الكاملة مجدداً، بل فقط الدولة التي يتواجد فيها المستخدم فعلياً الآن.
        val loc = lastLocation ?: return
        val candidates = OfflineRegionDef.containing(loc.latitude, loc.longitude)
            .filterNot { it.id in downloadedRegionIds || it.id in downloadingRegionIds }
        if (candidates.isEmpty()) {
            lastOfflinePromptKey = null
            return
        }
        val key = candidates.joinToString(",") { it.id }
        if (key == lastOfflinePromptKey) return
        lastOfflinePromptKey = key
        promptDownload(candidates)
    }

    private fun promptDownload(candidates: List<OfflineRegionDef>) {
        if (candidates.size == 1) {
            val def = candidates[0]
            AlertDialog.Builder(this)
                .setTitle("تحميل الخريطة دون انترنت")
                .setMessage("هل تريد تحميل خريطة ${def.arabicName} لاستخدامها بلا انترنت؟")
                .setPositiveButton("تحميل") { _, _ -> downloadRegions(listOf(def)) }
                .setNegativeButton("ليس الآن", null)
                .show()
        } else {
            val checked = BooleanArray(candidates.size) { true }
            AlertDialog.Builder(this)
                .setTitle("تحميل الخريطة دون انترنت")
                .setMultiChoiceItems(candidates.map { it.arabicName }.toTypedArray(), checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton("تحميل المحدد") { _, _ ->
                    val selected = candidates.filterIndexed { i, _ -> checked[i] }
                    if (selected.isNotEmpty()) downloadRegions(selected)
                }
                .setNegativeButton("ليس الآن", null)
                .show()
        }
    }

    private fun downloadRegions(defs: List<OfflineRegionDef>) {
        defs.forEach { downloadingRegionIds.add(it.id) }
        downloadNextOfflineRegion(defs.toMutableList())
    }

    private fun downloadNextOfflineRegion(queue: MutableList<OfflineRegionDef>) {
        if (queue.isEmpty()) {
            binding.status.visibility = View.GONE
            return
        }
        val def = queue.removeAt(0)
        binding.status.visibility = View.VISIBLE
        binding.status.text = "جارٍ تحميل خريطة ${def.arabicName}… ٠٪"
        offlineController.startDownload(
            def,
            object : OfflineRegionsController.ProgressListener {
                override fun onProgress(d: OfflineRegionDef, percent: Int) {
                    binding.status.text = "جارٍ تحميل خريطة ${d.arabicName}… $percent٪"
                }

                override fun onComplete(d: OfflineRegionDef) {
                    downloadingRegionIds.remove(d.id)
                    downloadedRegionIds.add(d.id)
                    downloadNextOfflineRegion(queue)
                }

                override fun onDownloadError(d: OfflineRegionDef, message: String) {
                    downloadingRegionIds.remove(d.id)
                    binding.status.text = "تعذّر تحميل خريطة ${d.arabicName}: $message"
                    downloadNextOfflineRegion(queue)
                }
            }
        )
    }

    private fun centerOnMyLocation() {
        if (!locationComponentActive) return
        val map = mapLibreMapOrNull ?: return
        val loc = map.locationComponent.lastKnownLocation ?: return
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 15.0))
    }

    override fun onStart() {
        super.onStart()
        binding.map.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(orientationListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        sensorManager.unregisterListener(orientationListener)
    }

    override fun onStop() {
        super.onStop()
        binding.map.onStop()
        if (this::locationManager.isInitialized) {
            locationManager.removeUpdates(firstFixListener)
            locationManager.removeUpdates(continuousLocationListener)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.map.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.map.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.map.onSaveInstanceState(outState)
    }
}
