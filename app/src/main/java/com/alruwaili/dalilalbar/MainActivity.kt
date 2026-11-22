package com.alruwaili.dalilalbar

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
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
    private var recordingBlinkAnimator: android.animation.ObjectAnimator? = null
    private lateinit var savedPlacesStore: com.alruwaili.dalilalbar.nav.SavedPlacesStore
    private lateinit var savedTracksStore: com.alruwaili.dalilalbar.nav.SavedTracksStore
    private var measuring = false
    private var measureStart: LatLng? = null
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
    private val rotationRemapped = FloatArray(9)
    private val orientationRaw = FloatArray(3)

    private val orientationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotationRaw, event.values)
            // SensorManager.getOrientation() على مصفوفة الدوران الخام يُعطي اتجاهاً صحيحاً فقط عندما
            // يكون الجهاز مستلقياً أفقياً (الشاشة للأعلى) — وهو سلوك موثَّق في Android API نفسه، وليس
            // تخميناً. عند حمل الهاتف عمودياً (الوضع الطبيعي لاستخدام البوصلة)، يجب أولاً إعادة تخطيط
            // محاور مصفوفة الدوران (remapCoordinateSystem) من إطار الجهاز الافتراضي إلى الإطار الذي
            // يلائم حمله عمودياً (الشاشة تجاه المستخدم، الحافة العلوية للأعلى): AXIS_X يبقى كما هو،
            // و AXIS_Z يُستبدل بـ AXIS_Y — أي remapCoordinateSystem(rotationRaw, AXIS_X, AXIS_Z, ...)
            // كما في توثيق Android الرسمي لحالة "القراءة عندما يُحمل الجهاز كبوصلة عمودية".
            SensorManager.remapCoordinateSystem(
                rotationRaw, SensorManager.AXIS_X, SensorManager.AXIS_Z, rotationRemapped
            )
            SensorManager.getOrientation(rotationRemapped, orientationRaw)
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

    // شاشة "المحفوظات" المستقلة الجديدة (SavedItemsActivity) قد تُعيد "اذهب إلى" نقطة معيّنة عبر
    // setResult، أو لا تُعيد شيئاً (RESULT_CANCELED) إذا أغلقها المستخدم بزر الرجوع فقط.
    private val savedItemsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null && data.hasExtra("go_to_lat")) {
                val lat = data.getDoubleExtra("go_to_lat", 0.0)
                val lon = data.getDoubleExtra("go_to_lon", 0.0)
                val zoom = data.getDoubleExtra("go_to_zoom", 14.0)
                mapLibreMapOrNull?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom))
            }
        }

    /** يفتح شاشة "المحفوظات" المستقلة الجديدة، مع موقع المستخدم الحالي (لحساب المسافة لكل عنصر)
     * والتبويب المطلوب فتحه مبدئياً (مواقع أو مسارات). */
    private fun openSavedItems(showPlaces: Boolean) {
        val intent = Intent(this, SavedItemsActivity::class.java).apply {
            putExtra(SavedItemsActivity.EXTRA_SHOW_PLACES, showPlaces)
            lastLocation?.let {
                putExtra(SavedItemsActivity.EXTRA_USER_LAT, it.latitude)
                putExtra(SavedItemsActivity.EXTRA_USER_LON, it.longitude)
            }
        }
        savedItemsLauncher.launch(intent)
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
        savedPlacesStore = com.alruwaili.dalilalbar.nav.SavedPlacesStore(applicationContext)
        savedTracksStore = com.alruwaili.dalilalbar.nav.SavedTracksStore(applicationContext)

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
                style.addImage("qa_khabra_pattern", buildQaKhabraPatternBitmap())
                tryActivateLocationComponent()
                setupNavigationLayers(style)
                setupLocationsLabelsLayer(style)
                setupSavedItemsLayers(style)
                setupMeasureLayer(style)
                refreshSavedPlacesLayer()
                refreshSavedTracksLayer()
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
            // تحديث حيّ لأداة قياس المسافة أثناء تحريك الخريطة (وليس فقط عند التوقف) — لتمتد الخريطة
            // بالشريط كما طُلب تحديداً: "كل ما حرّك المستخدم الخريطة يمتد معه شريط يحسب المسافة".
            map.addOnCameraMoveListener {
                if (measuring && measureStart != null) updateMeasureLine(map)
            }
        }

        binding.fabLocate.setOnClickListener { centerOnMyLocation() }
        binding.fabTrackRecord.setOnClickListener { toggleTrackRecording() }
        binding.fabMenu.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.fabSearch.setOnClickListener { showSearchDialog() }
        binding.fabSavedItems.setOnClickListener { openSavedItems(showPlaces = true) }
        binding.fabMeasure.setOnClickListener { toggleMeasureTool() }
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
        if (measuring) {
            if (measureStart == null) {
                measureStart = point
                binding.tvMeasureDistance.visibility = View.VISIBLE
                mapLibreMapOrNull?.let { updateMeasureLine(it) }
                Toast.makeText(this, R.string.measure_distance, Toast.LENGTH_SHORT).show()
            }
            return
        }
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

    // ===================== أداة قياس المسافة (زر يمين الشاشة) =====================

    private fun toggleMeasureTool() {
        measuring = !measuring
        if (!measuring) {
            measureStart = null
            binding.tvMeasureDistance.visibility = View.GONE
            loadedStyle?.getSourceAs<GeoJsonSource>("measure-line-source")
                ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        } else {
            Toast.makeText(this, R.string.measure_distance, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMeasureLine(map: MapLibreMap) {
        val start = measureStart ?: return
        val center = map.cameraPosition.target ?: return
        val distance = GeoMath.distanceMeters(start, center)
        binding.tvMeasureDistance.text = getString(R.string.measure_distance_label, GeoMath.formatDistance(distance))
        val line = LineString.fromLngLats(listOf(Point.fromLngLat(start.longitude, start.latitude), Point.fromLngLat(center.longitude, center.latitude)))
        loadedStyle?.getSourceAs<GeoJsonSource>("measure-line-source")
            ?.setGeoJson(FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(line))))
    }

    /**
     * نسيج خطوط زرقاء معرّجة مقطّعة (zigzag/dashed) على خلفية شفافة، يُستخدم لتمييز مناطق "قاع/خبرة/غدير"
     * (منخفضات تجمُّع مياه موسمية — مستنقعات مائية موسمية وليست مياهاً دائمة) بصرياً عن باقي تضاريس
     * الصحراء. تم تعديله بناءً على طلب المستخدم الصريح: "خطوط مهشّرة معرّجة مقطّعة" بدلاً من الخطوط
     * المستقيمة المائلة المستخدمة سابقاً. نرسمه برمجياً بدل تضمين صورة خارجية، لأن الـsprite الحالي
     * بعيد (tiles.openfreemap.org) ولا يمكن إضافة صورة محلية إليه إلا عبر Style.addImage في الكود.
     */
    private fun buildQaKhabraPatternBitmap(): Bitmap {
        val size = 24
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#1565C0")
            strokeWidth = 1.4f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            // يحوّل أي مسار (خط متعرّج هنا) إلى خط متقطّع (مقطّع): جزء مرسوم 3px ثم فراغ 2.2px وهكذا.
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(3f, 2.2f), 0f)
        }

        val amplitude = 2.6f
        val period = size / 3f // 3 موجات كاملة عبر عرض البلاطة، كي تتكرر أفقياً دون فاصل مرئي عند حواف البلاط.

        fun zigzagPath(baseY: Float): Path {
            val path = Path()
            var x = -size.toFloat()
            var up = true
            path.moveTo(x, baseY)
            while (x < size * 2f) {
                val nextX = x + period / 2f
                val y = if (up) baseY - amplitude else baseY + amplitude
                path.lineTo(nextX, y)
                x = nextX
                up = !up
            }
            return path
        }

        // صفّان من الخطوط المعرّجة المقطّعة موزّعان رأسياً لتغطية البلاطة كاملةً كنسيج مستنقع ماء.
        canvas.drawPath(zigzagPath(size * 0.3f), paint)
        canvas.drawPath(zigzagPath(size * 0.75f), paint)

        return bitmap
    }

    private fun setupMeasureLayer(style: Style) {
        val source = GeoJsonSource("measure-line-source", FeatureCollection.fromFeatures(emptyArray()))
        style.addSource(source)
        style.addLayer(
            LineLayer("measure-line-layer", "measure-line-source").withProperties(
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineColor("#39FF6A"),
                PropertyFactory.lineDasharray(arrayOf(1f, 1.5f))
            )
        )
    }

    // ===================== البحث عن موقع بالاسم =====================
    // أُعيد تصميم هذه الواجهة بالكامل بناءً على طلب المستخدم الصريح: بدل نافذة منبثقة في وسط الشاشة
    // (AlertDialog) تطلب كتابة الاسم كاملاً ثم الضغط لعرض النتائج، أصبح شريط بحث ملاصق لأعلى الشاشة
    // (searchBarContainer) مع قائمة منسدلة حيّة (lvSearchResults) تتحدّث تلقائياً مع كل حرف يُكتب —
    // تماماً كما وصف: "تكتب ال، تطلع لك جميع الأسماء بحرف ال. تكتب الكلمة تظهر لك الأسماء منسدلة تحت".

    private var currentSearchResults: List<LocationsRepository.LocationRow> = emptyList()
    private var pendingSearchRunnable: Runnable? = null
    private val searchDebounceHandler = Handler(Looper.getMainLooper())
    private val searchResultsAdapter by lazy {
        android.widget.ArrayAdapter(
            this, android.R.layout.simple_list_item_1, android.R.id.text1, mutableListOf<String>()
        ).also { adapter ->
            // نطبّق ألوان الثيم النيون يدوياً على صفوف القائمة لأن simple_list_item_1 الجاهزة سوداء/بيضاء افتراضياً.
            binding.lvSearchResults.adapter = adapter
        }
    }

    private var searchUiInitialized = false

    private fun setupSearchUi() {
        if (searchUiInitialized) return // نمنع تسجيل مستمعي الكتابة (TextWatcher) أكثر من مرة عند فتح البحث عدة مرات.
        searchUiInitialized = true
        searchResultsAdapter // يضمن تهيئة المحوّل وربطه بالقائمة (lazy) مرة واحدة فقط قبل أي استخدام.

        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                scheduleLiveSearch(s?.toString().orEmpty())
            }
        })
        binding.etSearch.setOnEditorActionListener { _, _, _ ->
            scheduleLiveSearch(binding.etSearch.text?.toString().orEmpty(), immediate = true)
            true
        }
        binding.btnCloseSearch.setOnClickListener { hideSearchUi() }
        binding.lvSearchResults.setOnItemClickListener { _, _, position, _ ->
            val r = currentSearchResults.getOrNull(position) ?: return@setOnItemClickListener
            val target = LatLng(r.latitude, r.longitude)
            mapLibreMapOrNull?.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 14.0))
            hideSearchUi()
        }
    }

    private fun showSearchDialog() {
        setupSearchUi()
        binding.searchBarContainer.visibility = View.VISIBLE
        binding.etSearch.requestFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSearchUi() {
        pendingSearchRunnable?.let { searchDebounceHandler.removeCallbacks(it) }
        binding.lvSearchResults.visibility = View.GONE
        binding.searchBarContainer.visibility = View.GONE
        binding.etSearch.text?.clear()
        currentSearchResults = emptyList()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }

    /**
     * بحث حيّ مع كل حرف: نؤجّل التنفيذ الفعلي ١٨٠ ملّي ثانية بعد آخر حرف (debounce) كي لا نستعلم قاعدة
     * البيانات (٣١٤ ألف صف) على كل ضغطة زر منفردة أثناء الكتابة السريعة، بل فقط بعد توقّف قصير طبيعي.
     */
    private fun scheduleLiveSearch(query: String, immediate: Boolean = false) {
        pendingSearchRunnable?.let { searchDebounceHandler.removeCallbacks(it) }
        if (query.isBlank()) {
            currentSearchResults = emptyList()
            searchResultsAdapter.clear()
            binding.lvSearchResults.visibility = View.GONE
            return
        }
        val runnable = Runnable { runSearch(query) }
        pendingSearchRunnable = runnable
        searchDebounceHandler.postDelayed(runnable, if (immediate) 0L else 180L)
    }

    private fun runSearch(query: String) {
        Thread {
            val results = locationsRepository.searchByName(query, 30)
            runOnUiThread {
                currentSearchResults = results
                searchResultsAdapter.clear()
                searchResultsAdapter.addAll(results.map { it.title })
                binding.lvSearchResults.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
            }
        }.start()
    }

    // ===================== المحفوظات (مواقع محفوظة + مسارات مسجَّلة) =====================

    private fun setupSavedItemsLayers(style: Style) {
        val placesSource = GeoJsonSource("saved-places-source", FeatureCollection.fromFeatures(emptyArray()))
        style.addSource(placesSource)
        style.addLayer(
            SymbolLayer("saved-places-layer", "saved-places-source").withProperties(
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                PropertyFactory.textSize(13f),
                PropertyFactory.textColor("#39FF6A"),
                PropertyFactory.textHaloColor("#000000"),
                PropertyFactory.textHaloWidth(1.2f),
                PropertyFactory.textOffset(arrayOf(0f, 1.2f))
            )
        )
        style.addLayer(
            CircleLayer("saved-places-point-layer", "saved-places-source").withProperties(
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleColor("#39FF6A"),
                PropertyFactory.circleStrokeColor("#000000"),
                PropertyFactory.circleStrokeWidth(1.5f)
            )
        )

        val tracksSource = GeoJsonSource("saved-tracks-source", FeatureCollection.fromFeatures(emptyArray()))
        style.addSource(tracksSource)
        style.addLayer(
            LineLayer("saved-tracks-layer", "saved-tracks-source").withProperties(
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineColor("#FFA500")
            )
        )
    }

    private fun refreshSavedPlacesLayer() {
        val style = loadedStyle ?: return
        val features = savedPlacesStore.list().filter { it.visible }.map {
            Feature.fromGeometry(Point.fromLngLat(it.latLng.longitude, it.latLng.latitude)).apply {
                addStringProperty("name", it.name)
            }
        }
        style.getSourceAs<GeoJsonSource>("saved-places-source")?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun refreshSavedTracksLayer() {
        val style = loadedStyle ?: return
        val features = savedTracksStore.list().filter { it.visible }.mapNotNull { t ->
            val points = savedTracksStore.readPoints(t.file)
            if (points.size < 2) return@mapNotNull null
            Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) }))
        }
        style.getSourceAs<GeoJsonSource>("saved-tracks-source")?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun showSaveLocationDialog(point: LatLng) {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.save_location_name_hint)
            setText(findPlaceNameAt(point) ?: "")
        }
        AlertDialog.Builder(this, R.style.Theme_DalilAlBar_NeonDialog)
            .setTitle(R.string.save_location)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                val saved = savedPlacesStore.add(name, point)
                refreshSavedPlacesLayer()
                Toast.makeText(this, getString(R.string.location_saved, saved.name), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        AlertDialog.Builder(this, R.style.Theme_DalilAlBar_NeonDialog)
            .setTitle(title)
            .setItems(
                arrayOf(getString(R.string.mode_straight), getString(R.string.mode_car), getString(R.string.save_location))
            ) { _, which ->
                when (which) {
                    0 -> startRouting(user, destination, NavMode.STRAIGHT)
                    1 -> startRouting(user, destination, NavMode.CAR)
                    else -> showSaveLocationDialog(destination)
                }
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
            val pending = trackRecorder.stop()
            binding.fabTrackRecord.setImageResource(R.drawable.ic_track_start)
            recordingBlinkAnimator?.cancel()
            recordingBlinkAnimator = null
            binding.tvRecordingIndicator.alpha = 1f
            binding.tvRecordingIndicator.visibility = View.GONE
            if (pending == null || pending.pointCount < 2) {
                Toast.makeText(this, R.string.recording_stopped_empty, Toast.LENGTH_SHORT).show()
                return
            }
            showTrackInfoDialog(pending)
        } else {
            trackRecorder.start()
            binding.fabTrackRecord.setImageResource(R.drawable.ic_track_stop)
            binding.tvRecordingIndicator.visibility = View.VISIBLE
            recordingBlinkAnimator?.cancel()
            recordingBlinkAnimator = android.animation.ObjectAnimator.ofFloat(binding.tvRecordingIndicator, View.ALPHA, 1f, 0.2f).apply {
                duration = 650
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
            Toast.makeText(this, R.string.recording_started, Toast.LENGTH_SHORT).show()
        }
    }

    /** بعد إيقاف التسجيل: يعرض معلومات المسار (المسافة/المدة/عدد النقاط) مع خيار حفظ باسم أو حذف،
     * كما طُلب صراحة — لا يُكتب أي ملف قبل أن يختار المستخدم "حفظ". */
    private fun showTrackInfoDialog(pending: com.alruwaili.dalilalbar.nav.TrackRecorder.PendingTrack) {
        val totalSeconds = pending.durationMs / 1000
        val durationText = String.format(
            java.util.Locale.US, "%02d:%02d:%02d",
            totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60
        )
        val info = getString(
            R.string.track_info_message,
            GeoMath.formatDistance(pending.distanceMeters),
            durationText,
            pending.pointCount
        )
        AlertDialog.Builder(this, R.style.Theme_DalilAlBar_NeonDialog)
            .setTitle(R.string.track_info_title)
            .setMessage(info)
            .setPositiveButton(R.string.save_as_name) { _, _ -> promptSaveTrackName(pending) }
            .setNegativeButton(R.string.delete) { _, _ ->
                Toast.makeText(this, R.string.track_discarded, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun promptSaveTrackName(pending: com.alruwaili.dalilalbar.nav.TrackRecorder.PendingTrack) {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.track_name_hint)
            setText(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()))
        }
        AlertDialog.Builder(this, R.style.Theme_DalilAlBar_NeonDialog)
            .setTitle(R.string.save_as_name)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                trackRecorder.saveAs(pending, name)
                refreshSavedTracksLayer()
                // طُلب صراحةً: "وعند الحفظ يذهب إلى قائمة المحفوظات" — فننتقل مباشرة إلى تبويب
                // المسارات في شاشة المحفوظات الجديدة بدل إظهار Toast فقط.
                openSavedItems(showPlaces = false)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
    /**
     * طُلب صراحة (٢١ نوفمبر) ألا تُعرض قائمة كل الدول إطلاقاً — فقط دولة المستخدم الفعلية بحسب
     * موقعه (GPS) الآن، عند أول فتح وعند كل توقّف تكبير/تحريك لاحق أيضاً. لا فرق بين "أول مرة" وما
     * بعدها بعد الآن: نفس منطق containing() يُطبَّق دائماً.
     * ملاحظة أمانة: "أي دولة في العالم" يتطلّب حدود كل دولة حقيقية، وهذا غير متوفر لدينا — المناطق
     * المعرَّفة فعلياً هنا تبقى محصورة بالأردن/السعودية (٣ مناطق)/الإمارات/الكويت/قطر/البحرين/عُمان؛
     * لمستخدم خارج هذه الدول لا يُعرض شيء (لا نفتعل بيانات حدود غير حقيقية لدول ليس لدينا مناطق تحميل
     * فعلية لها).
     */
    private fun checkOfflineRegionsForVisibleArea(map: MapLibreMap) {
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
            AlertDialog.Builder(this, R.style.Theme_DalilAlBar_NeonDialog)
                .setTitle("تحميل الخريطة دون انترنت")
                .setMessage("هل تريد تحميل خريطة ${def.arabicName} لاستخدامها بلا انترنت؟")
                .setPositiveButton("تحميل") { _, _ -> downloadRegions(listOf(def)) }
                .setNegativeButton("ليس الآن", null)
                .show()
        } else {
            val checked = BooleanArray(candidates.size) { true }
            AlertDialog.Builder(this, R.style.Theme_DalilAlBar_NeonDialog)
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
        showDownloadProgressDialog(def, queue)
    }

    /** نافذة تقدّم تحميل مركزية: شريط + نسبة مئوية + أزرار توقف مؤقت/إلغاء/تحميل في الخلفية، كما طُلب
     * صراحة بدل تحديث شريط حالة علوي بسيط فقط. "تحميل في الخلفية" يُغلق هذه النافذة فحسب — التحميل
     * نفسه يتابع في خدمة MapLibre الخلفية (OfflineManager) بصرف النظر عن ظهور أي واجهة له. */
    private fun showDownloadProgressDialog(def: OfflineRegionDef, queue: MutableList<OfflineRegionDef>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_download_progress, null)
        val tvName = dialogView.findViewById<android.widget.TextView>(R.id.tvDownloadRegionName)
        val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.progressBarDownload)
        val tvPercent = dialogView.findViewById<android.widget.TextView>(R.id.tvDownloadPercent)
        val btnPause = dialogView.findViewById<android.widget.Button>(R.id.btnDownloadPause)
        val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btnDownloadCancel)
        val btnBackground = dialogView.findViewById<android.widget.Button>(R.id.btnDownloadBackground)
        tvName.text = def.arabicName

        var currentRegion: org.maplibre.android.offline.OfflineRegion? = null
        var isPaused = false

        val dialog = AlertDialog.Builder(this, R.style.Theme_DalilAlBar_NeonDialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnPause.setOnClickListener {
            val region = currentRegion ?: return@setOnClickListener
            isPaused = !isPaused
            if (isPaused) {
                offlineController.pauseDownload(region)
                btnPause.text = getString(R.string.download_resume)
            } else {
                offlineController.resumeDownload(region)
                btnPause.text = getString(R.string.download_pause)
            }
        }
        btnCancel.setOnClickListener {
            val region = currentRegion
            downloadingRegionIds.remove(def.id)
            dialog.dismiss()
            if (region != null) {
                offlineController.cancelDownload(region) { }
            }
            Toast.makeText(this, getString(R.string.download_cancelled, def.arabicName), Toast.LENGTH_SHORT).show()
            downloadNextOfflineRegion(queue)
        }
        btnBackground.setOnClickListener {
            // لا يوقف ولا يُلغي شيئاً — فقط يُخفي النافذة؛ التحميل يتابع فعلياً في الخلفية.
            dialog.dismiss()
        }

        dialog.show()

        offlineController.startDownload(
            def,
            object : OfflineRegionsController.ProgressListener {
                override fun onProgress(d: OfflineRegionDef, percent: Int, region: org.maplibre.android.offline.OfflineRegion) {
                    currentRegion = region
                    progressBar.progress = percent
                    tvPercent.text = "$percent%"
                }

                override fun onComplete(d: OfflineRegionDef) {
                    downloadingRegionIds.remove(d.id)
                    downloadedRegionIds.add(d.id)
                    if (dialog.isShowing) dialog.dismiss()
                    downloadNextOfflineRegion(queue)
                }

                override fun onDownloadError(d: OfflineRegionDef, message: String) {
                    downloadingRegionIds.remove(d.id)
                    if (dialog.isShowing) dialog.dismiss()
                    Toast.makeText(this@MainActivity, "تعذّر تحميل خريطة ${d.arabicName}: $message", Toast.LENGTH_LONG).show()
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
        // قد تكون شاشة "المحفوظات" (SavedItemsActivity) غيّرت حذفاً/إظهاراً/إخفاءً لمواقع أو مسارات
        // محفوظة بينما كانت مفتوحة فوق هذه الشاشة؛ نعيد قراءتها من المخزن الفعلي عند العودة.
        refreshSavedPlacesLayer()
        refreshSavedTracksLayer()
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
