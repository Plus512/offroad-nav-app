package com.alruwaili.garminnav

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val REQ_MENU = 3001

/**
 * الشاشة الرئيسية: تحاكي تشغيل جهاز قارمن مباشرة على شاشة الخريطة.
 * شريط بيانات علوي (السرعة / المسافة / الوقت)، الخريطة في الوسط،
 * شريط أزرار سفلي (القائمة / وضع علامة / تكبير / مسار).
 */
class MainActivity : Activity(), SensorEventListener, LocationListener {

    private lateinit var map: MapCanvasView
    private lateinit var tvSpeed: TextView
    private lateinit var tvDist: TextView
    private lateinit var tvTime: TextView
    // شريط «إلى أين؟»: يظهر فقط عند وجود وجهة مُحدَّدة (بالّمس على الخريطة، أو بالبحث،
    // أو من النقاط المحفوظة)، ويعرض المسافة إليها مع زر «إيقاف» لإلغائها.
    private lateinit var navBar: LinearLayout
    private lateinit var navDistanceTv: TextView

    private lateinit var sm: SensorManager
    private var rotSensor: Sensor? = null
    private var smoothHeading = Float.NaN

    private var lm: LocationManager? = null
    private var totalDistM = 0.0
    private var lastLoc: Location? = null

    private var zoomLevel = 5
    private var tracking = false
    private var trackStartDistM = 0.0
    private var trackStartMs = 0L
    // نقاط GPS الفعلية (بوحدات قارمن الخام) المُجمَّعة أثناء تسجيل المسار الحالي،
    // تُحفظ كاملة مع المسار عند إيقاف التسجيل لتُرسم عند استعراضه لاحقاً.
    private val trackPoints = mutableListOf<Pair<Int, Int>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(GTheme.BEZEL)

        map = MapCanvasView(this)
        root.addView(map, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // شريط البيانات العلوي
        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.setBackgroundColor(GTheme.BAR_BG)
        topBar.gravity = Gravity.CENTER
        val topLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(90))
        topLp.gravity = Gravity.TOP

        tvSpeed = dataBox("السرعة", "٠", "كم/س")
        tvDist = dataBox("المسافة", "٠٫٠", "كم")
        tvTime = dataBox("الوقت", "--:--", "")
        topBar.addView(boxWrap(tvSpeed, "كم/س"))
        topBar.addView(boxWrap(tvDist, "كم"))
        topBar.addView(boxWrap(tvTime, ""))
        root.addView(topBar, topLp)

        // شريط الأزرار السفلي
        val bottomBar = LinearLayout(this)
        bottomBar.orientation = LinearLayout.HORIZONTAL
        bottomBar.setBackgroundColor(GTheme.BAR_BG)
        bottomBar.gravity = Gravity.CENTER
        val bottomLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(130))
        bottomLp.gravity = Gravity.BOTTOM

        bottomBar.addView(garminButton("☰", "القائمة") {
            startActivityForResult(Intent(this, MenuActivity::class.java), REQ_MENU)
        })
        bottomBar.addView(garminButton("📍", "علامة") { markWaypoint() })
        bottomBar.addView(garminButton("🎯", "موقعي") { map.recenter() })
        bottomBar.addView(garminButton("🔍", "تكبير") { changeZoom(1) })
        bottomBar.addView(garminButton("↪", "مسار") { toggleTracking() })
        root.addView(bottomBar, bottomLp)

        // شريط «إلى أين؟» — فوق شريط الأزرار السفلي مباشرة، مخفي بلا وجهة مُحدَّدة.
        navBar = LinearLayout(this)
        navBar.orientation = LinearLayout.HORIZONTAL
        navBar.layoutDirection = View.LAYOUT_DIRECTION_RTL
        navBar.setBackgroundColor(GTheme.BAR_BG)
        navBar.gravity = Gravity.CENTER_VERTICAL
        navBar.setPadding(dp(18), dp(10), dp(18), dp(10))
        navBar.visibility = View.GONE

        navDistanceTv = TextView(this)
        navDistanceTv.textDirection = View.TEXT_DIRECTION_RTL
        navDistanceTv.textSize = 15f
        navDistanceTv.setTextColor(GTheme.TEXT)
        navDistanceTv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        navBar.addView(navDistanceTv)

        val stopBtn = TextView(this)
        stopBtn.text = "إيقاف"
        stopBtn.textDirection = View.TEXT_DIRECTION_RTL
        stopBtn.textSize = 15f
        stopBtn.setTextColor(GTheme.ACCENT)
        stopBtn.setPadding(dp(18), dp(6), dp(4), dp(6))
        stopBtn.setOnClickListener { stopGoTo() }
        navBar.addView(stopBtn)

        val navBarLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        navBarLp.gravity = Gravity.BOTTOM
        navBarLp.bottomMargin = dp(130) // ارتفاع الشريط السفلي، ليظهر شريط الوجهة فوقه مباشرة
        root.addView(navBar, navBarLp)

        setContentView(root)

        // لمسة واحدة على الخريطة (ليست سحباً أو تكبيراً) تعرض قائمة بأسلوب قارمن:
        // «إلى أين؟» للموقع الذي لُمس، أو حفظه كنقطة، أو عرض تفاصيله.
        map.onMapTap = { lon, lat -> showMapTapMenu(lon, lat) }

        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager

        askPerms()
    }

    override fun onStart() {
        super.onStart()
        // عند كل رجوع إلى هذه الشاشة (مثلاً بعد تنزيل/استيراد خريطة جديدة من القائمة):
        // إن توفّر ملف خريطة ولم يكن محمّلاً فعلياً في الذاكرة بعد (أو تغيّر)، حمّله الآن
        // ففعلياً فك التشفير (الطرق/الأسماء) لا يحدث إلا باستدعاء هذا.
        if (MapStorage.mapExists(this) && MapRepository.map == null) {
            map.loadGarminMapFromStream { MapStorage.openMapInputStream(this)!! }
        } else if (!MapStorage.mapExists(this)) {
            map.mapLoaded = false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MENU && resultCode == Activity.RESULT_OK && data != null) {
            // نتيجة من شاشة البحث عن اسم أو النقاط المحفوظة، مُمرَّرة عبر شاشة القائمة:
            // انتقل إلى الموقع المختار.
            if (data.hasExtra("viewTrackStartMs")) {
                // نتيجة من شاشة «المسارات»: عرض مسار محفوظ سابقاً على الخريطة.
                val startMs = data.getLongExtra("viewTrackStartMs", 0L)
                map.showSavedTrack(SavedData.loadTrackPoints(this, startMs))
            } else if (data.hasExtra("lon") && data.hasExtra("lat")) {
                // أي موقع مُختار (بحث عن اسم، أو نقطة محفوظة) يصبح وجهة «إلى أين؟»
                // فوراً: خط إليه ومسافة، بنفس أسلوب قارمن.
                startGoTo(data.getIntExtra("lon", 0), data.getIntExtra("lat", 0))
            }
        }
    }

    private fun changeZoom(delta: Int) {
        zoomLevel = (zoomLevel + delta).coerceIn(1, 10)
        map.zoomLevel = zoomLevel
        Toast.makeText(this, "مستوى التكبير: $zoomLevel", Toast.LENGTH_SHORT).show()
    }

    private fun toggleTracking() {
        tracking = !tracking
        if (tracking) {
            trackStartDistM = totalDistM
            trackStartMs = System.currentTimeMillis()
            trackPoints.clear()
            map.startNewTrack()
            // أول نقطة معروفة الآن (إن وُجدت) تُضاف فوراً كبداية الخط، بدل الانتظار
            // لتحديث GPS التالي الذي قد يتأخر ثانية أو أكثر.
            map.currentFix()?.let { (lon, lat) -> trackPoints.add(lon to lat); map.addTrackPoint(lon, lat) }
            Toast.makeText(this, "بدأ تسجيل المسار", Toast.LENGTH_SHORT).show()
        } else {
            val distM = totalDistM - trackStartDistM
            SavedData.addTrack(this, trackStartMs, System.currentTimeMillis(), distM, trackPoints.toList())
            Toast.makeText(this, "انتهى المسار: ${Prefs.formatDistance(this, distM)} — تم الحفظ في «المسارات»", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * «إلى أين؟» (نفس الاسم الظاهر فعلياً في شاشة قارمن الحقيقية التي فحصناها):
     * يحدِّد الوجهة على الخريطة، يرسم خطاً إليها، ويعرض شريط المسافة مع زر الإيقاف.
     */
    private fun startGoTo(lon: Int, lat: Int) {
        map.setDestination(lon, lat)
        map.goTo(lon, lat)
        navBar.visibility = View.VISIBLE
        updateNavBar()
    }

    /** إيقاف/رجوع عن «إلى أين؟» الحالية: يمسح الخط ويخفي شريط المسافة. */
    private fun stopGoTo() {
        map.clearDestination()
        navBar.visibility = View.GONE
    }

    /** يُحدِّث نص المسافة والاتجاه في شريط «إلى أين؟» من موقعك الحالي إلى الوجهة. */
    private fun updateNavBar() {
        val dest = map.currentDestination() ?: return
        val fix = map.currentFix()
        navDistanceTv.text = if (fix != null) {
            val distM = GeoMath.distanceMeters(fix.first, fix.second, dest.first, dest.second)
            val brg = GeoMath.bearingDegrees(fix.first, fix.second, dest.first, dest.second)
            "المسافة إلى الوجهة: ${Prefs.formatDistance(this, distM)} — الاتجاه: ${GeoMath.compassLabel(brg)}"
        } else {
            "بانتظار إشارة GPS لحساب المسافة إلى الوجهة…"
        }
    }

    /**
     * قائمة لمسة الخريطة (بأسلوب شاشة التأشير في قارمن): «إلى أين؟» تبدأ التوجيه فوراً،
     * «حفظ نقطة هنا» تسمّي وتحفظ هذا الموقع بالذات دون حاجة لانتظار إشارة GPS،
     * و«تفاصيل الموقع» تعرض إحداثياته والمسافة/الاتجاه من موقعك الحالي.
     */
    private fun showMapTapMenu(lon: Int, lat: Int) {
        AlertDialog.Builder(this)
            .setTitle("هذا الموقع")
            .setItems(arrayOf("إلى أين؟ (الذهاب إلى هنا)", "حفظ نقطة هنا", "تفاصيل الموقع")) { _, which ->
                when (which) {
                    0 -> startGoTo(lon, lat)
                    1 -> saveWaypointAt(lon, lat)
                    2 -> showLocationDetails(lon, lat)
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    /** تفاصيل موقع مُحدَّد: إحداثياته الحقيقية، والمسافة/الاتجاه من موقعك الحالي إن توفّر. */
    private fun showLocationDetails(lon: Int, lat: Int) {
        val degLat = GeoMath.rawToDeg(lat)
        val degLon = GeoMath.rawToDeg(lon)
        val sb = StringBuilder()
        sb.append(String.format("الإحداثيات: %.5f, %.5f", degLat, degLon))
        map.currentFix()?.let { (fLon, fLat) ->
            val distM = GeoMath.distanceMeters(fLon, fLat, lon, lat)
            val brg = GeoMath.bearingDegrees(fLon, fLat, lon, lat)
            sb.append("\nالمسافة من موقعك: ").append(Prefs.formatDistance(this, distM))
            sb.append("\nالاتجاه: ").append(GeoMath.compassLabel(brg))
        }
        AlertDialog.Builder(this)
            .setTitle("تفاصيل الموقع")
            .setMessage(sb.toString())
            .setPositiveButton("إغلاق", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun dataBox(label: String, value: String, unit: String): TextView {
        val tv = TextView(this)
        tv.text = value
        tv.textSize = 30f
        tv.setTextColor(GTheme.TEXT)
        tv.gravity = Gravity.CENTER
        return tv
    }

    private fun boxWrap(valueTv: TextView, unit: String): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        col.addView(valueTv)
        if (unit.isNotEmpty()) {
            val u = TextView(this)
            u.text = unit
            u.textDirection = View.TEXT_DIRECTION_RTL
            u.textSize = 13f
            u.setTextColor(GTheme.MUTED)
            u.gravity = Gravity.CENTER
            col.addView(u)
        }
        col.layoutParams = lp
        return col
    }

    private fun garminButton(icon: String, label: String, onClick: () -> Unit): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.setPadding(dp(4), dp(10), dp(4), dp(10))
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        val ic = TextView(this)
        ic.text = icon
        ic.textSize = 32f
        ic.gravity = Gravity.CENTER
        ic.setTextColor(GTheme.TEXT)
        val tv = TextView(this)
        tv.text = label
        tv.textDirection = View.TEXT_DIRECTION_RTL
        tv.textSize = 14f
        tv.gravity = Gravity.CENTER
        tv.setTextColor(GTheme.MUTED)
        col.addView(ic)
        col.addView(tv)
        col.setOnClickListener { onClick() }
        col.layoutParams = lp
        return col
    }

    /**
     * يحفظ موقعك الحالي كنقطة مسمّاة (فكرة مأخوذة من "وضع علامة" في أجهزة قارمن):
     * يطلب اسماً مقترحاً (الوقت الحالي)، ويحفظه عبر SavedData ليظهر لاحقاً في
     * شاشة «النقاط المحفوظة» من القائمة الرئيسية.
     */
    private fun markWaypoint() {
        val fix = map.currentFix()
        if (fix == null) {
            Toast.makeText(this, "لا يوجد تحديد موقع بعد — انتظر إشارة GPS", Toast.LENGTH_SHORT).show()
            return
        }
        saveWaypointAt(fix.first, fix.second)
    }

    /** يحفظ موقعاً مُحدَّداً (من GPS الحالي أو من لمسة على الخريطة) كنقطة مسمّاة. */
    private fun saveWaypointAt(lon: Int, lat: Int) {
        val input = EditText(this)
        input.textDirection = View.TEXT_DIRECTION_RTL
        input.setText("نقطة ${SimpleDateFormat("HH:mm", Locale.US).format(Date())}")

        AlertDialog.Builder(this)
            .setTitle("حفظ نقطة")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                val name = input.text.toString().trim()
                SavedData.addPoint(this, name, lon, lat)
                Toast.makeText(this, "تم حفظ النقطة", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------- الأذونات والموقع ----------

    private fun askPerms() {
        val need = ArrayList<String>()
        for (p in arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p)
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 1)
        } else {
            startLocation()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocation()
        } else {
            Toast.makeText(
                this,
                "إذن الموقع مرفوض — فعّله يدوياً من إعدادات الهاتف ← التطبيقات ← ملاح البر ← الأذونات",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startLocation() {
        try {
            val provider = if (lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true)
                LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            lm?.requestLocationUpdates(provider, 1000L, 1f, this)
        } catch (e: SecurityException) {
        } catch (e: Exception) {
        }
    }

    override fun onLocationChanged(loc: Location) {
        map.onLocation(loc)

        // أول إشارة GPS قد تكون غير دقيقة (دقة ضعيفة) فتُعطي سرعة وهمية كبيرة.
        // لا تُعرض السرعة إلا إذا كانت القراءة مدعومة فعلياً ومنطقية (أقل من ٣٠٠ كم/س).
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        tvSpeed.text = if (speedKmh in 0.0..300.0) speedKmh.roundToInt().toString() else "0"

        // لا تُحسب المسافة من قراءة ذات دقة ضعيفة (> 50 متر) لتجنّب قفزة مسافة وهمية
        // بين أول إشارة غير دقيقة والإشارة الجيدة التي تليها.
        val accOk = !loc.hasAccuracy() || loc.accuracy <= 50f
        if (accOk) {
            lastLoc?.let { totalDistM += it.distanceTo(loc) }
            lastLoc = loc
        }
        tvDist.text = String.format("%.1f", totalDistM / 1000.0)

        // أثناء تسجيل مسار: أضف كل نقطة GPS جديدة (بدقة مقبولة) لخط المسار المرسوم،
        // واحفظها هنا أيضاً كي تُخزَّن كاملة مع المسار عند إيقاف التسجيل.
        if (tracking && accOk) {
            map.currentFix()?.let { (lon, lat) ->
                trackPoints.add(lon to lat)
                map.addTrackPoint(lon, lat)
            }
        }

        // وجهة «إلى أين؟» نشطة: حدِّث المسافة/الاتجاه المعروضين مع كل تحديث GPS.
        if (map.currentDestination() != null) updateNavBar()
    }

    @Deprecated("deprecated in API") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // ---------- البوصلة ----------

    override fun onResume() {
        super.onResume()
        rotSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        // تُطبَّق فوراً عند كل رجوع لهذه الشاشة، كي يأخذ تغيير الإعداد مفعوله مباشرة
        // دون الحاجة لإعادة تشغيل التطبيق.
        if (Prefs.keepScreenOn(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
        try { lm?.removeUpdates(this) } catch (e: Exception) {}
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rm = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rm, e.values)
        val o = FloatArray(3)
        SensorManager.getOrientation(rm, o)
        var az = Math.toDegrees(o[0].toDouble()).toFloat()
        az = (az + 360f) % 360f
        if (smoothHeading.isNaN()) smoothHeading = az
        else {
            val diff = ((az - smoothHeading + 540f) % 360f) - 180f
            smoothHeading = (smoothHeading + diff * 0.15f + 360f) % 360f
        }
        map.heading = smoothHeading
    }
}
