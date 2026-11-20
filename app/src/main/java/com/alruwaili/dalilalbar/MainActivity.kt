package com.alruwaili.dalilalbar

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
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
        binding.compassArrow.setOnClickListener { toggleSplitView() }

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
        showNavigationChoiceDialog(userLatLng, point, distance)
    }

    private fun showNavigationChoiceDialog(user: LatLng, destination: LatLng, distance: Double) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.distance_to_point, GeoMath.formatDistance(distance)))
            .setItems(arrayOf(getString(R.string.mode_straight), getString(R.string.mode_car))) { _, which ->
                val mode = if (which == 0) NavMode.STRAIGHT else NavMode.CAR
                startRouting(user, destination, mode)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startRouting(user: LatLng, destination: LatLng, mode: NavMode) {
        activeDestination = destination
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
                PropertyFactory.lineDasharray(arrayOf(0f, 2f))
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

    /** يحدّث اتجاه السهم (شمال افتراضياً، أو نحو الوجهة بعد اختيارها) ونص لوحة البوصلة إن كانت ظاهرة. */
    private fun refreshCompassAndPanel() {
        val destination = activeDestination
        val loc = lastLocation
        if (destination == null || loc == null) {
            binding.compassArrow.pointsToDestination = false
            binding.compassArrow.headingDegrees = 0f
            return
        }
        val userLatLng = LatLng(loc.latitude, loc.longitude)
        val bearing = GeoMath.bearingDegrees(userLatLng, destination)
        binding.compassArrow.headingDegrees = bearing.toFloat()
        binding.compassArrow.pointsToDestination = true

        if (splitViewVisible) {
            val distance = GeoMath.distanceMeters(userLatLng, destination)
            binding.compassPanel.bearingDegrees = bearing.toFloat()
            binding.compassPanel.bearingLabelText =
                "الاتجاه: ${Math.round(bearing)} درجة ${GeoMath.compassLabelArabic(bearing)}"
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
     * يفحص المنطقة المرئية حالياً على الخريطة: إن كانت تتقاطع مع منطقة (دولة/جزء من السعودية) لم
     * تُحمَّل دون انترنت ولا يجري تحميلها الآن، يعرض سؤال التحميل. يتكرر هذا الفحص تلقائياً مع كل
     * توقّف للكاميرا (بعد أول تحديد لموقع المستخدم عند فتح التطبيق، وبعد كل عملية تكبير/تصغير أو
     * تحريك) — فإذا رفض المستخدم التحميل، سيُسأل مرة أخرى عند تكبير/تحريك يغيّر المنطقة المعروضة.
     */
    private fun checkOfflineRegionsForVisibleArea(map: MapLibreMap) {
        val visible = map.projection.visibleRegion.latLngBounds
        val candidates = OfflineRegionDef.intersecting(visible)
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
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
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
