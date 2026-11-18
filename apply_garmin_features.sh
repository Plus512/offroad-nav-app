#!/data/data/com.termux/files/usr/bin/bash
set -e
echo "تطبيق تحديثات تطبيق دليل البر — إلى أين؟ + إعدادات فعّالة..."

mkdir -p "$(dirname "app/src/main/AndroidManifest.xml")"
cat > "app/src/main/AndroidManifest.xml" << 'GARMIN_PATCH_EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.INTERNET" />

    <uses-feature android:name="android.hardware.location.gps" android:required="false" />
    <uses-feature android:name="android.hardware.sensor.compass" android:required="false" />

    <application
        android:allowBackup="true"
        android:icon="@drawable/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.GarminNav">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="fullUser"
            android:configChanges="orientation|screenSize|keyboardHidden|screenLayout">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".MenuActivity"
            android:exported="false"
            android:screenOrientation="fullUser" />

        <activity
            android:name=".SettingsActivity"
            android:exported="false"
            android:screenOrientation="fullUser" />

        <activity
            android:name=".SearchActivity"
            android:exported="false"
            android:screenOrientation="fullUser" />

        <activity
            android:name=".CompassActivity"
            android:exported="false"
            android:screenOrientation="fullUser" />

        <activity
            android:name=".SatellitesActivity"
            android:exported="false"
            android:screenOrientation="fullUser" />

        <activity
            android:name=".SavedPointsActivity"
            android:exported="false"
            android:screenOrientation="fullUser" />

        <activity
            android:name=".TracksActivity"
            android:exported="false"
            android:screenOrientation="fullUser" />

        <activity
            android:name=".EmergencyActivity"
            android:exported="false"
            android:screenOrientation="fullUser" />

    </application>
</manifest>
GARMIN_PATCH_EOF
echo "  كتب: app/src/main/AndroidManifest.xml"

mkdir -p "$(dirname "app/src/main/java/com/alruwaili/garminnav/MainActivity.kt")"
cat > "app/src/main/java/com/alruwaili/garminnav/MainActivity.kt" << 'GARMIN_PATCH_EOF'
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
GARMIN_PATCH_EOF
echo "  كتب: app/src/main/java/com/alruwaili/garminnav/MainActivity.kt"

mkdir -p "$(dirname "app/src/main/java/com/alruwaili/garminnav/MenuActivity.kt")"
cat > "app/src/main/java/com/alruwaili/garminnav/MenuActivity.kt" << 'GARMIN_PATCH_EOF'
package com.alruwaili.garminnav

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val REQ_IMPORT_MAP = 2001
private const val REQ_PICK_FOLDER = 2002
private const val REQ_SEARCH = 2003
private const val REQ_POINTS = 2004
private const val REQ_TRACKS = 2005

/**
 * رابط ملف خريطتك على GitHub (raw).
 * ملاحظة مهمة: raw.githubusercontent.com لا يُقدّم الملف لتطبيق غير مسجَّل دخول إذا كان
 * المستودع خاصاً (private) — لهذا يجب أن يكون مستودع offroad-nav-app نفسه عاماً (public)
 * من إعدادات GitHub (Settings ← Danger Zone ← Change visibility)، وإلا سيستمر خطأ ٤٠٤.
 */
const val MAP_DOWNLOAD_URL =
    "https://raw.githubusercontent.com/Plus512/offroad-nav-app/main/maps/almisharIMAP.img"

/** القائمة الرئيسية بأسلوب قارمن: شبكة ٣×٣ أيقونات. */
class MenuActivity : Activity() {

    private lateinit var hostRoot: FrameLayout
    private var progressOverlay: FrameLayout? = null
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var progressTitle: TextView

    /** الإجراء المؤجَّل ريثما يختار المستخدم مجلد الحفظ الدائم ("download" أو "import"). */
    private var pendingAction: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(GTheme.BEZEL)
        root.setPadding(dp(30), dp(40), dp(30), dp(40))

        val title = TextView(this)
        title.text = "القائمة الرئيسية"
        title.textDirection = View.TEXT_DIRECTION_RTL
        title.textSize = 26f
        title.setTextColor(GTheme.TEXT)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, dp(30))
        root.addView(title)

        val grid = GridLayout(this)
        grid.columnCount = 3

        val items = listOf(
            "📍" to "النقاط المحفوظة",
            "↪" to "المسارات",
            "🗺" to "الخرائط",
            "🧭" to "البوصلة",
            "📶" to "الأقمار الصناعية",
            "⬇" to "استيراد خريطة",
            "🔍" to "بحث عن موقع",
            "🆘" to "الطوارئ",
            "⚙" to "الإعدادات",
        )

        // عرض كل خلية يُحسب من عرض الشاشة الفعلي حتى تظهر ٣ أعمدة دائماً، مهما كان حجم الشاشة.
        val screenWidthPx = resources.displayMetrics.widthPixels
        val sidePaddingPx = dp(30) * 2
        val marginPerCellPx = dp(8) * 2
        val cellWidthPx = (screenWidthPx - sidePaddingPx) / 3 - marginPerCellPx

        for ((icon, label) in items) {
            grid.addView(menuItem(icon, label, cellWidthPx))
        }
        root.addView(grid)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(GTheme.BEZEL)
        scroll.addView(root)

        // جذر عام (FrameLayout) يحوي القائمة + طبقة شريط التقدّم التي تظهر فوقها أثناء
        // التحميل/الاستيراد (تماماً كشاشة التحميل في تطبيقك «الدليلة +»).
        hostRoot = FrameLayout(this)
        hostRoot.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(hostRoot)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun menuItem(icon: String, label: String, cellWidthPx: Int): LinearLayout {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER
        cell.setBackgroundColor(GTheme.BAR_BG)
        cell.setPadding(dp(6), dp(20), dp(6), dp(20))

        val lp = GridLayout.LayoutParams()
        lp.width = cellWidthPx
        lp.height = dp(135)
        lp.setMargins(dp(8), dp(8), dp(8), dp(8))
        cell.layoutParams = lp

        val ic = TextView(this)
        ic.text = icon
        ic.textSize = 32f
        ic.setTextColor(GTheme.ACCENT)
        ic.gravity = Gravity.CENTER

        val tv = TextView(this)
        tv.text = label
        tv.textDirection = View.TEXT_DIRECTION_RTL
        tv.textSize = 12f
        tv.setTextColor(GTheme.TEXT)
        tv.gravity = Gravity.CENTER
        tv.setPadding(0, dp(8), 0, 0)

        cell.addView(ic)
        cell.addView(tv)

        cell.setOnClickListener {
            when (label) {
                "الإعدادات" -> startActivity(Intent(this, SettingsActivity::class.java))
                "البوصلة" -> startActivity(Intent(this, CompassActivity::class.java))
                "الأقمار الصناعية" -> startActivity(Intent(this, SatellitesActivity::class.java))
                "الطوارئ" -> startActivity(Intent(this, EmergencyActivity::class.java))
                "المسارات" -> startActivityForResult(Intent(this, TracksActivity::class.java), REQ_TRACKS)
                "النقاط المحفوظة" -> startActivityForResult(Intent(this, SavedPointsActivity::class.java), REQ_POINTS)
                "بحث عن موقع" -> {
                    if (MapRepository.map == null) {
                        Toast.makeText(this, "لم تُحمَّل خريطة بعد — استورد أو نزّل الخريطة أولاً", Toast.LENGTH_LONG).show()
                    } else {
                        startActivityForResult(Intent(this, SearchActivity::class.java), REQ_SEARCH)
                    }
                }
                "استيراد خريطة" -> ensureFolderThen("import")
                "الخرائط" -> ensureFolderThen("download")
            }
        }
        return cell
    }

    /**
     * يتأكد من وجود مجلد حفظ دائم (SAF) مُختار قبل أي تنزيل/استيراد جديد، بحيث تبقى
     * الخريطة محفوظة حتى لو حُذف التطبيق لاحقاً. إن لم يوجد مجلد بعد، يطلب من
     * المستخدم اختيار واحد أولاً ثم يستكمل الإجراء المطلوب تلقائياً.
     */
    private fun ensureFolderThen(action: String) {
        if (MapStorage.hasPersistentFolder(this)) {
            if (action == "download") downloadMap() else pickMapFile()
            return
        }
        pendingAction = action
        Toast.makeText(
            this,
            "اختر مجلداً لحفظ الخريطة فيه بشكل دائم — بهذا تبقى الخريطة موجودة حتى لو حذفت التطبيق أو حدّثته، ولن تحتاج لتنزيلها مرة أخرى",
            Toast.LENGTH_LONG
        ).show()
        startActivityForResult(MapStorage.pickFolderIntent(), REQ_PICK_FOLDER)
    }

    // ---------- طبقة شريط التقدّم ----------

    private fun showProgress(titleText: String) {
        // يمنع الشاشة من الإطفاء أثناء التحميل — إطفاء الشاشة على بعض الأجهزة
        // (خصوصاً MIUI) يقطع الاتصالات الشبكية الجارية في الخلفية.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(0xEE1C1C1EL.toInt())

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.layoutDirection = View.LAYOUT_DIRECTION_RTL
        box.gravity = Gravity.CENTER
        val boxLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        boxLp.gravity = Gravity.CENTER
        box.layoutParams = boxLp
        box.setPadding(dp(40), dp(40), dp(40), dp(40))

        progressTitle = TextView(this)
        progressTitle.text = titleText
        progressTitle.textDirection = View.TEXT_DIRECTION_RTL
        progressTitle.textSize = 18f
        progressTitle.setTextColor(GTheme.TEXT)
        progressTitle.gravity = Gravity.CENTER
        progressTitle.setPadding(0, 0, 0, dp(20))
        box.addView(progressTitle)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        progressBar.progress = 0
        val barLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20))
        box.addView(progressBar, barLp)

        progressText = TextView(this)
        progressText.text = "0%"
        progressText.textDirection = View.TEXT_DIRECTION_RTL
        progressText.textSize = 15f
        progressText.setTextColor(GTheme.MUTED)
        progressText.gravity = Gravity.CENTER
        progressText.setPadding(0, dp(15), 0, 0)
        box.addView(progressText)

        overlay.addView(box)
        hostRoot.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        progressOverlay = overlay
    }

    private fun updateProgress(doneBytes: Long, totalBytes: Long) {
        if (totalBytes > 0) {
            val pct = ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
            progressBar.isIndeterminate = false
            progressBar.progress = pct
            progressText.text = "${pct}% — ${doneBytes / 1024 / 1024} / ${totalBytes / 1024 / 1024} ميجا"
        } else {
            progressBar.isIndeterminate = true
            progressText.text = "${doneBytes / 1024 / 1024} ميجا"
        }
    }

    private fun hideProgress() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progressOverlay?.let { hostRoot.removeView(it) }
        progressOverlay = null
    }

    // ---------- استيراد من ذاكرة الهاتف ----------

    /**
     * فتح منتقي ملفات الجهاز لاختيار ملف الخريطة (.img) مباشرة — بديل للتحميل من
     * الإنترنت، مفيد عندما تكون شبكة الجوال غير موثوقة لنقل ملف بهذا الحجم.
     */
    private fun pickMapFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_IMPORT_MAP)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_IMPORT_MAP -> {
                if (resultCode == Activity.RESULT_OK) {
                    val uri = data?.data
                    if (uri != null) importMapFile(uri)
                }
            }
            REQ_PICK_FOLDER -> {
                val action = pendingAction
                pendingAction = null
                val folderUri = data?.data
                if (resultCode == Activity.RESULT_OK && folderUri != null) {
                    MapStorage.onFolderPicked(this, folderUri)
                    when (action) {
                        "download" -> downloadMap()
                        "import" -> pickMapFile()
                    }
                } else {
                    Toast.makeText(this, "لم يُختر مجلد — تم إلغاء العملية", Toast.LENGTH_SHORT).show()
                }
            }
            REQ_SEARCH, REQ_POINTS, REQ_TRACKS -> {
                // نتيجة البحث عن اسم، أو اختيار نقطة محفوظة، أو اختيار مسار لعرضه:
                // نمررها للأعلى (شاشة الخريطة) دون أي تغيير.
                if (resultCode == Activity.RESULT_OK && data != null) {
                    setResult(Activity.RESULT_OK, data)
                    finish()
                }
            }
        }
    }

    private fun importMapFile(uri: Uri) {
        val main = Handler(Looper.getMainLooper())
        main.post { showProgress("جارٍ نسخ الخريطة من هاتفك…") }

        Thread {
            var tmpUri: Uri? = null
            try {
                val totalBytes = try {
                    contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                } catch (e: Exception) { -1L }

                val (tUri, out) = MapStorage.createTempOutputStream(this)
                    ?: throw Exception("تعذّر إنشاء ملف مؤقت في المجلد المحفوظ")
                tmpUri = tUri
                val input = contentResolver.openInputStream(uri)
                    ?: throw Exception("تعذّر فتح الملف المحدَّد")
                var copied = 0L
                var lastUpdate = 0L
                input.use { inp ->
                    out.use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            copied += n
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 150) {
                                lastUpdate = now
                                main.post { updateProgress(copied, totalBytes) }
                            }
                        }
                    }
                }
                if (!MapStorage.finalizeDownload(this, tUri)) throw Exception("تعذّر حفظ الملف النهائي")
                MapRepository.clear()
                main.post {
                    hideProgress()
                    Toast.makeText(
                        this,
                        "تم استيراد الخريطة (${MapStorage.mapSizeMb(this)} ميجا) — ستبقى محفوظة حتى لو حُذف التطبيق",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                tmpUri?.let { MapStorage.deleteTemp(this, it) }
                main.post {
                    hideProgress()
                    Toast.makeText(this, "فشل الاستيراد: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------- تحميل من الإنترنت ----------

    /**
     * تنزيل حقيقي لملف الخريطة من GitHub إلى تخزين التطبيق، مع شاشة تقدّم حقيقية
     * (نسبة مئوية + ميجا/ميجا) تبقى الشاشة مضاءة أثناءها لتفادي قطع الاتصال الذي
     * تسببه بعض الأجهزة (مثل MIUI) حين تُطفئ الشاشة أو يغادر المستخدم الشاشة أثناء
     * تنزيل في الخلفية. ملاحظة صريحة: هذا يضع الملف على الجهاز فقط. رسم الطرق
     * الفعلية منه لم يُفعَّل بعد (انظر MapCanvasView) — هذا عمل منفصل لم يكتمل.
     */
    private fun downloadMap() {
        val main = Handler(Looper.getMainLooper())
        main.post { showProgress("جارٍ تنزيل الخريطة…") }

        Thread {
            var lastError: Exception? = null
            var success = false
            var finalMb = 0L
            for (attempt in 1..5) {
                var tmpUri: Uri? = null
                try {
                    val conn = URL(MAP_DOWNLOAD_URL).openConnection() as HttpURLConnection
                    conn.connectTimeout = 30000
                    conn.readTimeout = 30000
                    conn.connect()
                    if (conn.responseCode != 200) {
                        throw Exception("رمز استجابة الخادم: ${conn.responseCode}")
                    }
                    val totalBytes = conn.contentLengthLong
                    val (tUri, out) = MapStorage.createTempOutputStream(this)
                        ?: throw Exception("تعذّر إنشاء ملف مؤقت في المجلد المحفوظ")
                    tmpUri = tUri
                    var copied = 0L
                    var lastUpdate = 0L
                    conn.inputStream.use { input ->
                        out.use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                copied += n
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 150) {
                                    lastUpdate = now
                                    main.post { updateProgress(copied, totalBytes) }
                                }
                            }
                        }
                    }
                    if (!MapStorage.finalizeDownload(this, tUri)) throw Exception("تعذّر حفظ الملف النهائي")
                    MapRepository.clear()
                    finalMb = MapStorage.mapSizeMb(this)
                    success = true
                    break
                } catch (e: Exception) {
                    lastError = e
                    tmpUri?.let { MapStorage.deleteTemp(this, it) }
                    if (attempt < 5) {
                        main.post {
                            progressTitle.text = "انقطع الاتصال، إعادة المحاولة (${attempt}/5)…"
                        }
                        Thread.sleep(2000)
                    }
                }
            }
            if (success) {
                main.post {
                    hideProgress()
                    Toast.makeText(
                        this,
                        "اكتمل التنزيل ($finalMb ميجا) — ستبقى الخريطة محفوظة حتى لو حُذف التطبيق",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                main.post {
                    hideProgress()
                    Toast.makeText(this, "فشل التنزيل بعد ٥ محاولات: ${lastError?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
GARMIN_PATCH_EOF
echo "  كتب: app/src/main/java/com/alruwaili/garminnav/MenuActivity.kt"

mkdir -p "$(dirname "app/src/main/java/com/alruwaili/garminnav/MapCanvasView.kt")"
cat > "app/src/main/java/com/alruwaili/garminnav/MapCanvasView.kt" << 'GARMIN_PATCH_EOF'
package com.alruwaili.garminnav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.concurrent.thread

/**
 * شاشة الخريطة. ترسم الآن الطرق والمعالم الحقيقية المفكوكة من ملف قارمن .img
 * (قطاعا TRE وRGN)، فوق خلفية رملية، مع سهم الموقع الحالي (يدور حسب اتجاه السير)
 * ودعم السحب بالإصبع (Pan) والتكبير بإصبعين (Pinch-zoom) وزر إعادة التوسيط («موقعي»).
 *
 * فك التشفير (الحدود، التقسيمات الفرعية، الطرق، النقاط، وأسماء المعالم من قطاع LBL) تم
 * التحقق منه تجريبياً وبصرياً على خريطة حقيقية قبل اعتماده (راجع التعليقات في
 * GarminMapData.kt للتفاصيل). تُعرض أسماء المعالم فقط عند تكبير كافٍ، وفقط للأسماء التي
 * اجتازت فحص السلامة (نص عربي/لاتيني مقروء بالكامل) — أي اسم مشكوك فيه يُهمَل بصمت.
 */
class MapCanvasView(ctx: Context) : View(ctx) {

    var heading: Float = 0f
        set(v) { field = v; invalidate() }

    var hasFix: Boolean = false
        set(v) { field = v; invalidate() }

    var mapLoaded: Boolean = false
        set(v) { field = v; invalidate() }

    /** true أثناء قراءة وفك ملف الخريطة في الخلفية (قد يستغرق وقتاً لملف كبير). */
    var mapLoading: Boolean = false
        set(v) { field = v; invalidate() }

    /** مستوى التكبير (١ أبعد ← ١٠ أقرب). */
    var zoomLevel: Int = 5
        set(v) { field = v; invalidate(); requestDecode() }

    private val bg = Paint().apply { color = GTheme.SAND }
    private val gridPaint = Paint().apply {
        color = Color.argb(60, 0, 0, 0)
        strokeWidth = 2f
    }
    private val vehiclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GTheme.ACCENT
        style = Paint.Style.FILL
    }
    private val vehicleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1C1E")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val msgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 20, 15, 10)
        textAlign = Paint.Align.CENTER
        textSize = 30f
    }
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B2B2E")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val polygonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 120, 150, 90)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    // أودية/مجاري مائية (رمز النوع ٠x18 «مجرى مائي رفيع»، ٠x1f «نهر»، ٠x26 «نهر متقطع» —
    // تم التحقق من وجودها فعلياً في بيانات خريطتك: ٥٨٩٢ خطاً بالرمز ٠x18 وحده، منطقي جداً
    // لشبكة الشعاب الكثيفة في منطقة صحراوية) — تُرسم بلون أزرق مائي بدل اللون العام للطرق.
    private val wadiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3C78A8")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // مضلعات مائية (نهر ٠x46، بحيرة ٠x3c، نهر/بحيرة متقطعة ٠x4c) — تعبئة زرقاء شبه شفافة.
    private val waterPolyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 60, 120, 168)
        style = Paint.Style.FILL
    }
    private val poiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B8441C")
        style = Paint.Style.FILL
    }
    // أسماء المعالم (من قطاع LBL، عربية صحيحة ١٠٠٪ تم التحقق منها) — تُرسم فقط عند
    // تكبير كافٍ وبحد أقصى للعدد لتفادي ازدحام الشاشة وبطء الرسم.
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B2B2E")
        textSize = 24f
        textAlign = Paint.Align.LEFT
    }

    // إزاحة السحب بالإصبع (بكسل)؛ تُصفَّر عند الضغط على زر «موقعي» لإعادة التوسيط.
    private var panX = 0f
    private var panY = 0f
    private var scaleAccum = 1f

    // خط المسار (نقاط GPS بوحدات قارمن الخام) — إما مسار يُسجَّل الآن مباشرة، أو
    // مسار محفوظ سابقاً يُعرض للاستعراض؛ يُرسم بنفس الطريقة في الحالتين.
    private val trackPoints = mutableListOf<Pair<Int, Int>>()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D1374A")
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // ---------- «إلى أين؟» (الوجهة المختارة بالّمس أو بالبحث) ----------
    // الفكرة مأخوذة من شاشة Where To? في قارمن (نفس الاسم الظاهر فعلياً داخل
    // ملف APK الذي فحصناه)، بشيفرة ورسم أصليين بالكامل: خط متقطّع من موقعك
    // الحالي إلى الوجهة، وعلامة مثلّثة عندها، وبطاقة المسافة تُحدَّثها الشاشة الرئيسية.
    private var destLon: Int? = null
    private var destLat: Int? = null
    private val destPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E6DB4")
        style = Paint.Style.FILL
    }
    private val destLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E6DB4")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(22f, 16f), 0f)
    }

    /** يُستدعى عند لمس الخريطة لمسة واحدة (ليس سحباً ولا تكبيراً)، بموقع اللمس بوحدات قارمن الخام. */
    var onMapTap: ((lon: Int, lat: Int) -> Unit)? = null

    // ---------- بيانات الخريطة الحقيقية (قارمن .img) ----------
    private var garminMap: GarminMap? = null
    @Volatile private var decoded: DecodedFeatures? = null
    @Volatile private var decodeGeneration = 0
    private var centerLon = 0
    private var centerLat = 0
    private var mapLoadFailed = false

    /** وحدات قارمن الخام لكل بكسل حسب مستوى التكبير (١=الأبعد، ١٠=الأقرب). */
    private fun unitsPerPixel(): Double = 16.0 * Math.pow(2.0, (5 - zoomLevel).toDouble())

    /** يحمّل ملف قارمن .img من مسار مباشر (الأسلوب القديم)، في خيط خلفي. */
    fun loadGarminMap(path: String) {
        loadGarminMapCommon { GarminMap(path).also { it.open() } }
    }

    /**
     * يحمّل ملف قارمن .img من أي مصدر (مجرى بيانات)، في خيط خلفي — يُستخدم عند
     * القراءة من مجلد محفوظ عبر SAF (حيث لا يوجد مسار ملف مباشر). [openStream]
     * يُستدعى في الخيط الخلفي نفسه ويُغلق تلقائياً بعد القراءة.
     */
    fun loadGarminMapFromStream(openStream: () -> java.io.InputStream) {
        loadGarminMapCommon {
            val m = GarminMap()
            openStream().use { m.openFromStream(it) }
            m
        }
    }

    private fun loadGarminMapCommon(opener: () -> GarminMap) {
        mapLoadFailed = false
        mapLoading = true
        thread {
            try {
                val m = opener()
                if (m.tiles.isEmpty()) {
                    post { mapLoading = false; mapLoadFailed = true; invalidate() }
                    return@thread
                }
                garminMap = m
                MapRepository.map = m
                MapRepository.labelIndex = null
                post {
                    centerLon = m.centerLon
                    centerLat = m.centerLat
                    mapLoading = false
                    mapLoaded = true
                    scheduleDecode()
                }
            } catch (e: Exception) {
                post { mapLoading = false; mapLoadFailed = true; invalidate() }
            }
        }
    }

    /** يعيد فك الطرق/النقاط المرئية حالياً فقط (حسب موقع الكاميرا والتكبير)، في خيط خلفي. */
    private fun scheduleDecode() {
        val m = garminMap ?: return
        if (width == 0 || height == 0) return
        val myGen = ++decodeGeneration
        val upp = unitsPerPixel()
        // المركز الجغرافي الفعلي المعروض حالياً في وسط الشاشة (يأخذ السحب بالإصبع بعين الاعتبار).
        val viewLon = centerLon - (panX * upp).toInt()
        val viewLat = centerLat + (panY * upp).toInt()
        val halfW = (width * upp / 2).toInt()
        val halfH = (height * upp / 2).toInt()
        val margin = ((halfW + halfH) / 2 * 0.3).toInt()
        val lonMin = viewLon - halfW - margin
        val lonMax = viewLon + halfW + margin
        val latMin = viewLat - halfH - margin
        val latMax = viewLat + halfH + margin
        thread {
            val result = m.decodeVisible(lonMin, lonMax, latMin, latMax, 8000)
            if (myGen == decodeGeneration) {
                decoded = result
                post { invalidate() }
            }
        }
    }

    private val decodeRunnable = Runnable { scheduleDecode() }

    /** يطلب إعادة فك بعد توقف قصير عن التحريك، لتفادي إعادة الفك مع كل حدث سحب. */
    private fun requestDecode() {
        if (garminMap == null) return
        removeCallbacks(decodeRunnable)
        postDelayed(decodeRunnable, 180)
    }

    private val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            panX -= distanceX
            panY -= distanceY
            invalidate()
            requestDecode()
            return true
        }

        // لمسة واحدة سريعة (ليست سحباً): حوّل موقعها على الشاشة إلى إحداثي خريطة
        // حقيقي وأبلغ المستدعي (الشاشة الرئيسية) عبر onMapTap، التي تعرض عندها
        // قائمة «إلى أين؟ / حفظ نقطة هنا / تفاصيل الموقع» بأسلوب قارمن.
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onMapTap?.invoke(screenToLon(e.x), screenToLat(e.y))
            return true
        }
    })

    /** يحوّل إحداثي بكسل على الشاشة (مثل موقع اللمس) إلى خط طول بوحدات قارمن الخام. */
    private fun screenToLon(x: Float): Int {
        val upp = unitsPerPixel()
        val cx0 = width / 2f + panX
        return centerLon + ((x - cx0) * upp).toInt()
    }

    /** يحوّل إحداثي بكسل على الشاشة (مثل موقع اللمس) إلى خط عرض بوحدات قارمن الخام. */
    private fun screenToLat(y: Float): Int {
        val upp = unitsPerPixel()
        val cy0 = height / 2f + panY
        return centerLat - ((y - cy0) * upp).toInt()
    }

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaleAccum = 1f
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleAccum *= detector.scaleFactor
            while (scaleAccum > 1.15f) {
                zoomLevel = (zoomLevel + 1).coerceAtMost(10)
                scaleAccum /= 1.15f
            }
            while (scaleAccum < 1f / 1.15f) {
                zoomLevel = (zoomLevel - 1).coerceAtLeast(1)
                scaleAccum *= 1.15f
            }
            requestDecode()
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (garminMap != null) scheduleDecode()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    /** زر «موقعي»: يعيد توسيط سهم الموقع بعد سحبه بالإصبع، ويستأنف تتبّع GPS إن توفّر. */
    fun recenter() {
        panX = 0f
        panY = 0f
        if (lastFixLon != null && lastFixLat != null) {
            centerLon = lastFixLon!!
            centerLat = lastFixLat!!
        }
        invalidate()
        requestDecode()
    }

    /** ينقل مركز العرض إلى موقع محدد (مثلاً نتيجة بحث)، بمعزل عن موقع GPS الحالي. */
    fun goTo(lon: Int, lat: Int) {
        panX = 0f
        panY = 0f
        centerLon = lon
        centerLat = lat
        if (zoomLevel < 7) zoomLevel = 7 // تكبير كافٍ لرؤية الاسم المختار بوضوح
        invalidate()
        requestDecode()
    }

    /**
     * آخر إحداثي GPS مؤكَّد، بوحدات قارمن الخام (نفس وحدات goTo/centerLon/centerLat)،
     * أو null إن لم تُستقبل أي إشارة بعد — تستخدمه شاشات مثل «وضع علامة» و«الطوارئ».
     */
    fun currentFix(): Pair<Int, Int>? {
        val lon = lastFixLon
        val lat = lastFixLat
        return if (lon != null && lat != null) lon to lat else null
    }

    /** يبدأ تسجيل مسار جديد: يمسح أي خط سابق ويبدأ تجميع نقاط جديدة مع كل تحديث GPS. */
    fun startNewTrack() {
        trackPoints.clear()
        invalidate()
    }

    /** يضيف نقطة GPS جديدة لخط المسار الجاري تسجيله الآن (يُستدعى من onLocation). */
    fun addTrackPoint(lon: Int, lat: Int) {
        trackPoints.add(lon to lat)
        invalidate()
    }

    /** يحدِّد وجهة «إلى أين؟» الحالية (بوحدات قارمن الخام) لرسم الخط والعلامة إليها. */
    fun setDestination(lon: Int, lat: Int) {
        destLon = lon
        destLat = lat
        invalidate()
    }

    /** يلغي الوجهة الحالية (زر «إيقاف» في الشاشة الرئيسية). */
    fun clearDestination() {
        destLon = null
        destLat = null
        invalidate()
    }

    /** الوجهة الحالية بوحدات قارمن الخام، أو null إن لم تُحدَّد وجهة. */
    fun currentDestination(): Pair<Int, Int>? {
        val lon = destLon
        val lat = destLat
        return if (lon != null && lat != null) lon to lat else null
    }

    /** يعرض مساراً محفوظاً سابقاً (بدل التسجيل المباشر) ويتوسّط العرض على بدايته. */
    fun showSavedTrack(points: List<Pair<Int, Int>>) {
        trackPoints.clear()
        trackPoints.addAll(points)
        if (points.isNotEmpty()) {
            goTo(points.first().first, points.first().second)
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)

        // إحداثيات تحويل الخريطة إلى بكسل الشاشة — تُحسب دائماً (حتى بلا خريطة محمَّلة)
        // لأن خط المسار وسهم المركبة يُرسمان بغضّ النظر عن توفّر بيانات الطرق.
        val upp = unitsPerPixel()
        val cx0 = width / 2f + panX
        val cy0 = height / 2f + panY
        fun px(lon: Int): Float = cx0 + ((lon - centerLon) / upp).toFloat()
        fun py(lat: Int): Float = cy0 - ((lat - centerLat) / upp).toFloat()

        val gm = garminMap
        val feats = decoded
        if (gm != null && feats != null) {
            val path = android.graphics.Path()
            for (line in feats.lines) {
                if (line.lons.isEmpty()) continue
                path.reset()
                path.moveTo(px(line.lons[0]), py(line.lats[0]))
                for (i in 1 until line.lons.size) path.lineTo(px(line.lons[i]), py(line.lats[i]))
                val isWadiLine = !line.isPolygon && (line.typ == 0x18 || line.typ == 0x1f || line.typ == 0x26)
                val isWaterPoly = line.isPolygon && (line.typ == 0x46 || line.typ == 0x3c || line.typ == 0x4c)
                val paint = when {
                    isWadiLine -> wadiPaint
                    isWaterPoly -> waterPolyPaint
                    line.isPolygon -> polygonPaint
                    else -> roadPaint
                }
                c.drawPath(path, paint)
            }
            var labelsDrawn = 0
            val showLabels = zoomLevel >= 5 || Prefs.alwaysLabels(context)
            for (pt in feats.points) {
                val x = px(pt.lon); val y = py(pt.lat)
                c.drawCircle(x, y, 5f, poiPaint)
                val lbl = pt.label
                if (showLabels && lbl != null && labelsDrawn < 400) {
                    c.drawText(lbl, x + 8f, y + 8f, labelPaint)
                    labelsDrawn++
                }
            }
        } else {
            // لا خريطة محمّلة بعد: شبكة خفيفة فقط لإعطاء إحساس بالحركة، وليست طرقاً حقيقية.
            val step = (220 - zoomLevel * 18).coerceAtLeast(40)
            val stepF = step.toFloat()
            var y = (((panY % stepF) + stepF) % stepF) - stepF
            while (y < height) { c.drawLine(0f, y, width.toFloat(), y, gridPaint); y += stepF }
            var x = (((panX % stepF) + stepF) % stepF) - stepF
            while (x < width) { c.drawLine(x, 0f, x, height.toFloat(), gridPaint); x += stepF }
        }

        if (mapLoading) {
            c.drawText("جارٍ قراءة ملف الخريطة… قد يستغرق بعض الوقت", width / 2f, height / 2f - 60f, msgPaint)
        } else if (!mapLoaded) {
            c.drawText("لم تُستورد خريطة بعد — اضغط «استيراد خريطة» من القائمة", width / 2f, height / 2f - 60f, msgPaint)
        } else if (mapLoadFailed) {
            c.drawText("تعذّر قراءة ملف الخريطة", width / 2f, height / 2f - 60f, msgPaint)
        } else if (gm != null && feats == null) {
            c.drawText("جارٍ تحميل الخريطة…", width / 2f, height / 2f - 60f, msgPaint)
        }

        // خط المسار (تسجيل مباشر أو مسار محفوظ معروض للاستعراض) — يُرسم بغضّ النظر
        // عن توفّر بيانات الطرق، لأن px/py محسوبتان دائماً أعلاه.
        if (trackPoints.size >= 2) {
            val tPath = android.graphics.Path()
            tPath.moveTo(px(trackPoints[0].first), py(trackPoints[0].second))
            for (i in 1 until trackPoints.size) {
                tPath.lineTo(px(trackPoints[i].first), py(trackPoints[i].second))
            }
            c.drawPath(tPath, trackPaint)
        }

        // خط ومعلم «إلى أين؟»: يُرسم الخط فقط إن توفّر تحديد GPS حالي (لنعرف من أين
        // نبدأ)، بينما تظهر العلامة عند الوجهة بغضّ النظر عن توفّر الإشارة.
        val dLon = destLon
        val dLat = destLat
        if (dLon != null && dLat != null) {
            val fLon = lastFixLon
            val fLat = lastFixLat
            if (fLon != null && fLat != null) {
                c.drawLine(px(fLon), py(fLat), px(dLon), py(dLat), destLinePaint)
            }
            val dx = px(dLon); val dy = py(dLat)
            val dr = minOf(width, height) * 0.03f
            val flag = android.graphics.Path()
            flag.moveTo(dx, dy)
            flag.lineTo(dx, dy - dr * 2.2f)
            flag.lineTo(dx + dr * 1.4f, dy - dr * 1.7f)
            flag.lineTo(dx, dy - dr * 1.2f)
            flag.close()
            c.drawPath(flag, destPaint)
            c.drawCircle(dx, dy, dr * 0.35f, destPaint)
        }

        // سهم المركبة في وسط الشاشة افتراضياً، يتحرك مع السحب بالإصبع ويدور مع اتجاه السير.
        val cx = width / 2f + panX
        val cy = height / 2f + panY
        c.save()
        c.rotate(heading, cx, cy)
        val r = minOf(width, height) * 0.05f
        val path = android.graphics.Path()
        path.moveTo(cx, cy - r)
        path.lineTo(cx + r * 0.7f, cy + r * 0.8f)
        path.lineTo(cx, cy + r * 0.35f)
        path.lineTo(cx - r * 0.7f, cy + r * 0.8f)
        path.close()
        c.drawPath(path, vehiclePaint)
        c.drawPath(path, vehicleStroke)
        c.restore()

        if (!hasFix) {
            c.drawText("بانتظار إشارة GPS…", width / 2f, height / 2f + 70f, msgPaint)
        }
    }

    private var lastFixLon: Int? = null
    private var lastFixLat: Int? = null

    fun onLocation(loc: Location) {
        hasFix = true
        val lon = (loc.longitude * (1 shl 23) / 180.0).toInt()
        val lat = (loc.latitude * (1 shl 23) / 180.0).toInt()
        lastFixLon = lon
        lastFixLat = lat
        // في وضع التتبّع (لم يسحب المستخدم الشاشة بعيداً)، يتبع مركز العرض الموقع الحالي.
        if (panX == 0f && panY == 0f) {
            centerLon = lon
            centerLat = lat
            requestDecode()
        }
    }
}
GARMIN_PATCH_EOF
echo "  كتب: app/src/main/java/com/alruwaili/garminnav/MapCanvasView.kt"

mkdir -p "$(dirname "app/src/main/java/com/alruwaili/garminnav/SavedData.kt")"
cat > "app/src/main/java/com/alruwaili/garminnav/SavedData.kt" << 'GARMIN_PATCH_EOF'
package com.alruwaili.garminnav

import android.content.Context
import java.io.File

/** نقطة محفوظة بالاسم مع إحداثيات قارمن الخام (نفس وحدات GarminMap) ووقت الحفظ. */
data class SavedPoint(val name: String, val lon: Int, val lat: Int, val timeMs: Long)

/** جلسة مسار مسجَّلة: بداية/نهاية/مسافة بالأمتار. */
data class TrackSession(val startMs: Long, val endMs: Long, val distanceM: Double)

/**
 * تخزين بسيط للنقاط المحفوظة والمسارات، بدون قاعدة بيانات: كل سجل بسطر نصي واحد
 * مفصول بـ"|" داخل ملف في تخزين التطبيق الداخلي (filesDir). يبقى موجوداً بين فتحات
 * التطبيق، ويُحذف فقط إذا حُذف التطبيق نفسه — وهذا مقبول لهذه البيانات، بخلاف ملف
 * الخريطة نفسه الذي يُحفظ في مجلد خارجي دائم عبر MapStorage.
 *
 * كل مسار يُخزَّن بسجل ملخّص واحد في tracks.txt (البداية/النهاية/المسافة)، بالإضافة
 * إلى ملف منفصل يحمل كل نقاط المسار الفعلية (خط طول/عرض لكل نقطة GPS التُقطت أثناء
 * التسجيل) باسم مبني من وقت البداية — هذا ما يسمح برسم خط المسار لاحقاً عند استعراضه.
 *
 * فكرة الشاشتين («النقاط المحفوظة» و«المسارات») مأخوذة من شاشتي MyLocationsActivity
 * وTracks في تطبيق قارمن، لكن بتخزين وشيفرة مكتوبين من الصفر خصيصاً لهذا التطبيق.
 */
object SavedData {

    private fun pointsFile(ctx: Context) = File(ctx.filesDir, "saved_points.txt")
    private fun tracksFile(ctx: Context) = File(ctx.filesDir, "tracks.txt")
    private fun trackPointsFile(ctx: Context, startMs: Long) = File(ctx.filesDir, "track_points_$startMs.txt")

    @Synchronized
    fun addPoint(ctx: Context, name: String, lon: Int, lat: Int) {
        val safeName = name.replace("|", " ").replace("\n", " ").trim().ifEmpty { "نقطة" }
        val line = "$safeName|$lon|$lat|${System.currentTimeMillis()}\n"
        pointsFile(ctx).appendText(line)
    }

    @Synchronized
    fun listPoints(ctx: Context): List<SavedPoint> {
        val f = pointsFile(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size != 4) return@mapNotNull null
            try {
                SavedPoint(parts[0], parts[1].toInt(), parts[2].toInt(), parts[3].toLong())
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.timeMs }
    }

    @Synchronized
    fun deletePoint(ctx: Context, point: SavedPoint) {
        val f = pointsFile(ctx)
        if (!f.exists()) return
        val remaining = f.readLines().filterNot { line ->
            val parts = line.split("|")
            parts.size == 4 &&
                parts[0] == point.name &&
                parts[1] == point.lon.toString() &&
                parts[2] == point.lat.toString() &&
                parts[3] == point.timeMs.toString()
        }
        f.writeText(if (remaining.isEmpty()) "" else remaining.joinToString("\n") + "\n")
    }

    /** يحفظ ملخّص المسار، وإن توفّرت نقاط فعلية (خط سير GPS) يحفظها في ملف منفصل لعرضها لاحقاً. */
    @Synchronized
    fun addTrack(ctx: Context, startMs: Long, endMs: Long, distanceM: Double, points: List<Pair<Int, Int>>) {
        tracksFile(ctx).appendText("$startMs|$endMs|$distanceM\n")
        if (points.isNotEmpty()) {
            val sb = StringBuilder()
            for ((lon, lat) in points) {
                sb.append(lon).append(',').append(lat).append('\n')
            }
            trackPointsFile(ctx, startMs).writeText(sb.toString())
        }
    }

    @Synchronized
    fun listTracks(ctx: Context): List<TrackSession> {
        val f = tracksFile(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size != 3) return@mapNotNull null
            try {
                TrackSession(parts[0].toLong(), parts[1].toLong(), parts[2].toDouble())
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.startMs }
    }

    /** يقرأ نقاط مسار محدَّد (بوحدات قارمن الخام) لرسمه على الخريطة؛ قائمة فارغة إن لم تُحفظ نقاط له. */
    @Synchronized
    fun loadTrackPoints(ctx: Context, startMs: Long): List<Pair<Int, Int>> {
        val f = trackPointsFile(ctx, startMs)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size != 2) return@mapNotNull null
            try {
                parts[0].toInt() to parts[1].toInt()
            } catch (e: Exception) {
                null
            }
        }
    }
}
GARMIN_PATCH_EOF
echo "  كتب: app/src/main/java/com/alruwaili/garminnav/SavedData.kt"

mkdir -p "$(dirname "app/src/main/java/com/alruwaili/garminnav/GeoMath.kt")"
cat > "app/src/main/java/com/alruwaili/garminnav/GeoMath.kt" << 'GARMIN_PATCH_EOF'
package com.alruwaili.garminnav

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * دوال رياضية جغرافية بسيطة، شيفرة أصلية بالكامل: تحويل وحدات قارمن الخام
 * (raw = درجة × ٢^٢٣ ÷ ١٨٠، نفس الوحدة المستخدمة في كل مكان بالتطبيق) من/إلى
 * الدرجات الحقيقية، وحساب المسافة (صيغة هافرساين) والاتجاه بين نقطتين — تُستخدم
 * في شاشة «إلى أين؟» وتفاصيل الموقع وشريط المسافة المباشر.
 */
object GeoMath {
    private const val UNITS_PER_DEGREE = (1 shl 23) / 180.0
    private const val EARTH_RADIUS_M = 6371000.0

    fun rawToDeg(raw: Int): Double = raw / UNITS_PER_DEGREE

    fun degToRaw(deg: Double): Int = (deg * UNITS_PER_DEGREE).toInt()

    /** المسافة بالأمتار بين نقطتين بوحدات قارمن الخام (صيغة هافرساين). */
    fun distanceMeters(lon1: Int, lat1: Int, lon2: Int, lat2: Int): Double {
        val la1 = Math.toRadians(rawToDeg(lat1))
        val la2 = Math.toRadians(rawToDeg(lat2))
        val dLa = la2 - la1
        val dLo = Math.toRadians(rawToDeg(lon2) - rawToDeg(lon1))
        val a = sin(dLa / 2).pow(2) + cos(la1) * cos(la2) * sin(dLo / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    /** الاتجاه بالدرجات (٠..٣٦٠، ٠=شمال) من نقطة إلى أخرى بوحدات قارمن الخام. */
    fun bearingDegrees(lon1: Int, lat1: Int, lon2: Int, lat2: Int): Double {
        val la1 = Math.toRadians(rawToDeg(lat1))
        val la2 = Math.toRadians(rawToDeg(lat2))
        val dLo = Math.toRadians(rawToDeg(lon2) - rawToDeg(lon1))
        val y = sin(dLo) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLo)
        val brg = Math.toDegrees(atan2(y, x))
        return (brg + 360.0) % 360.0
    }

    /** يحوّل درجة اتجاه إلى اسم جهة مختصر بالعربية (ثماني جهات). */
    fun compassLabel(deg: Double): String {
        val dirs = arrayOf("شمال", "شمال شرقي", "شرق", "جنوب شرقي", "جنوب", "جنوب غربي", "غرب", "شمال غربي")
        val idx = (((deg % 360.0) + 360.0) % 360.0 / 45.0).roundToInt() % 8
        return dirs[idx]
    }
}
GARMIN_PATCH_EOF
echo "  كتب: app/src/main/java/com/alruwaili/garminnav/GeoMath.kt"

mkdir -p "$(dirname "app/src/main/java/com/alruwaili/garminnav/Prefs.kt")"
cat > "app/src/main/java/com/alruwaili/garminnav/Prefs.kt" << 'GARMIN_PATCH_EOF'
package com.alruwaili.garminnav

import android.content.Context

/**
 * إعدادات التطبيق المحفوظة (SharedPreferences بسيطة، بلا قاعدة بيانات): تُعدَّل
 * من شاشة «الإعدادات» وتُقرأ فوراً من أي شاشة أخرى تحتاجها — وحدة المسافة تؤثر
 * على كل نص مسافة في التطبيق (شريط «إلى أين؟»، تفاصيل الموقع، قائمة المسارات)،
 * وبقية الإعدادات تُقرأ عند فتح الشاشة الرئيسية أو رسم الخريطة.
 */
object Prefs {
    private const val NAME = "app_settings"
    private const val KEY_UNIT_MILES = "unit_miles"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_ALWAYS_LABELS = "always_labels"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun unitMiles(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_UNIT_MILES, false)
    fun setUnitMiles(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_UNIT_MILES, v).apply() }

    fun keepScreenOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_KEEP_SCREEN_ON, false)
    fun setKeepScreenOn(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_KEEP_SCREEN_ON, v).apply() }

    fun alwaysLabels(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ALWAYS_LABELS, false)
    fun setAlwaysLabels(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_ALWAYS_LABELS, v).apply() }

    /** ينسّق مسافة بالأمتار حسب وحدة المستخدم المختارة (كم أو ميل)، مع اسم الوحدة ضمن النص. */
    fun formatDistance(ctx: Context, meters: Double): String {
        return if (unitMiles(ctx)) {
            String.format("%.2f ميل", meters / 1609.344)
        } else if (meters >= 1000.0) {
            String.format("%.2f كم", meters / 1000.0)
        } else {
            String.format("%.0f م", meters)
        }
    }
}
GARMIN_PATCH_EOF
echo "  كتب: app/src/main/java/com/alruwaili/garminnav/Prefs.kt"

mkdir -p "$(dirname "app/src/main/java/com/alruwaili/garminnav/SettingsActivity.kt")"
cat > "app/src/main/java/com/alruwaili/garminnav/SettingsActivity.kt" << 'GARMIN_PATCH_EOF'
package com.alruwaili.garminnav

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.io.File

/**
 * شاشة إعدادات حقيقية وفعّالة: كل مفتاح هنا يُطبَّق فوراً (عبر Prefs) في الشاشة
 * الرئيسية وشاشة الخريطة دون الحاجة لإعادة تشغيل التطبيق، بالإضافة لمعلومات
 * الخريطة والتطبيق كما كانت.
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(GTheme.BEZEL)
        root.setPadding(dp(30), dp(50), dp(30), dp(30))

        root.addView(title("الإعدادات"))

        root.addView(switchRow("وحدة المسافة: ميل بدل كيلومتر", Prefs.unitMiles(this)) {
            Prefs.setUnitMiles(this, it)
        })
        root.addView(switchRow("إبقاء الشاشة مضاءة أثناء استخدام التطبيق", Prefs.keepScreenOn(this)) {
            Prefs.setKeepScreenOn(this, it)
        })
        root.addView(switchRow("إظهار أسماء المعالم في كل مستويات التكبير", Prefs.alwaysLabels(this)) {
            Prefs.setAlwaysLabels(this, it)
        })

        val mapFile = File(filesDir, "maps/almisharIMAP.img")
        val mapStatus = if (mapFile.exists())
            "محمَّلة (${mapFile.length() / 1024 / 1024} ميجا)"
        else
            "غير محمَّلة"
        root.addView(row("حالة الخريطة", mapStatus))

        val pkgInfo = packageManager.getPackageInfo(packageName, 0)
        root.addView(row("إصدار التطبيق", pkgInfo.versionName ?: "-"))
        root.addView(row("الهوية", packageName))

        setContentView(root)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun title(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textDirection = View.TEXT_DIRECTION_RTL
        tv.textSize = 26f
        tv.setTextColor(GTheme.TEXT)
        tv.gravity = Gravity.CENTER
        tv.setPadding(0, 0, 0, dp(40))
        return tv
    }

    /** صف مفتاح تشغيل/إيقاف يُطبِّق التغيير فوراً عبر [onChange] عند كل تبديل. */
    private fun switchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.layoutDirection = View.LAYOUT_DIRECTION_RTL
        r.setBackgroundColor(GTheme.BAR_BG)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(20), dp(18), dp(20), dp(18))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(10))
        r.layoutParams = lp

        val l = TextView(this)
        l.text = label
        l.textDirection = View.TEXT_DIRECTION_RTL
        l.textSize = 16f
        l.setTextColor(GTheme.TEXT)
        l.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val sw = Switch(this)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }

        r.addView(l)
        r.addView(sw)
        return r
    }

    private fun row(label: String, value: String): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.layoutDirection = View.LAYOUT_DIRECTION_RTL
        r.setBackgroundColor(GTheme.BAR_BG)
        r.setPadding(dp(20), dp(20), dp(20), dp(20))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(10))
        r.layoutParams = lp

        val l = TextView(this)
        l.text = label
        l.textDirection = View.TEXT_DIRECTION_RTL
        l.textSize = 16f
        l.setTextColor(GTheme.MUTED)
        l.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val v = TextView(this)
        v.text = value
        v.textDirection = View.TEXT_DIRECTION_RTL
        v.textSize = 16f
        v.setTextColor(GTheme.TEXT)
        v.gravity = Gravity.END

        r.addView(l)
        r.addView(v)
        return r
    }
}
GARMIN_PATCH_EOF
echo "  كتب: app/src/main/java/com/alruwaili/garminnav/SettingsActivity.kt"

mkdir -p "$(dirname "app/src/main/java/com/alruwaili/garminnav/CompassActivity.kt")"
cat > "app/src/main/java/com/alruwaili/garminnav/CompassActivity.kt" << 'GARMIN_PATCH_EOF'
package com.alruwaili.garminnav

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * شاشة بوصلة مستقلة بأسلوب قارمن: حلقة ثابتة الحجم، تدور بها حروف الاتجاهات
 * الأربعة حسب اتجاه السير الفعلي، بينما سهم ثابت في الأعلى يمثّل دائماً الاتجاه
 * الذي يشير إليه الهاتف (تماماً كبوصلة أجهزة قارمن أثناء القيادة).
 * الفكرة مأخوذة من شاشة Compass في قارمن، لكن الرسم والشيفرة هنا أصليان بالكامل.
 */
class CompassActivity : Activity(), SensorEventListener {

    private lateinit var dial: CompassDialView
    private lateinit var headingText: TextView
    private lateinit var sm: SensorManager
    private var rotSensor: Sensor? = null
    private var smoothHeading = Float.NaN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(GTheme.BEZEL)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        val colLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        colLp.gravity = Gravity.CENTER

        val title = TextView(this)
        title.text = "البوصلة"
        title.textDirection = View.TEXT_DIRECTION_RTL
        title.textSize = 22f
        title.setTextColor(GTheme.TEXT)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, dp(30))
        col.addView(title)

        dial = CompassDialView(this)
        val size = dp(260)
        col.addView(dial, LinearLayout.LayoutParams(size, size))

        headingText = TextView(this)
        headingText.text = "٠°"
        headingText.textSize = 30f
        headingText.setTextColor(GTheme.ACCENT)
        headingText.gravity = Gravity.CENTER
        headingText.setPadding(0, dp(24), 0, 0)
        col.addView(headingText)

        root.addView(col, colLp)
        setContentView(root)

        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onResume() {
        super.onResume()
        rotSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
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
        dial.heading = smoothHeading
        headingText.text = "${smoothHeading.toInt()}°  ${compassLabel(smoothHeading)}"
    }

    private fun compassLabel(deg: Float): String = when {
        deg < 22.5f || deg >= 337.5f -> "شمال"
        deg < 67.5f -> "شمال شرق"
        deg < 112.5f -> "شرق"
        deg < 157.5f -> "جنوب شرق"
        deg < 202.5f -> "جنوب"
        deg < 247.5f -> "جنوب غرب"
        deg < 292.5f -> "غرب"
        else -> "شمال غرب"
    }
}

/**
 * قرص بوصلة مرسوم يدوياً: الحلقة وحروف الاتجاهات تدور معاً بعكس اتجاه السير
 * (فتصل حرف الشمال فعلياً إلى حيث الشمال الحقيقي)، بينما سهم ثابت أعلى القرص
 * يمثّل دائماً الاتجاه الذي يشير إليه مقدّم الهاتف.
 */
private class CompassDialView(ctx: Context) : View(ctx) {

    var heading: Float = 0f
        set(v) {
            field = v
            invalidate()
        }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GTheme.MUTED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GTheme.MUTED
        strokeWidth = 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GTheme.TEXT
        textAlign = Paint.Align.CENTER
        textSize = 28f
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GTheme.NEEDLE
        style = Paint.Style.FILL
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 14f

        // الحلقة الدوّارة (الاتجاهات)
        c.save()
        c.rotate(-heading, cx, cy)
        c.drawCircle(cx, cy, r, ringPaint)
        for (deg in 0 until 360 step 30) {
            val rad = Math.toRadians(deg.toDouble())
            val x1 = cx + (r - 10f) * Math.sin(rad).toFloat()
            val y1 = cy - (r - 10f) * Math.cos(rad).toFloat()
            val x2 = cx + r * Math.sin(rad).toFloat()
            val y2 = cy - r * Math.cos(rad).toFloat()
            c.drawLine(x1, y1, x2, y2, tickPaint)
        }
        c.drawText("شمال", cx, cy - r + 34f, labelPaint)
        c.drawText("جنوب", cx, cy + r - 12f, labelPaint)
        c.drawText("شرق", cx + r - 28f, cy + 10f, labelPaint)
        c.drawText("غرب", cx - r + 28f, cy + 10f, labelPaint)
        c.restore()

        // السهم الثابت: يمثّل دائماً اتجاه مقدّم الهاتف (أعلى الشاشة).
        val path = Path()
        path.moveTo(cx, cy - r + 6f)
        path.lineTo(cx + 16f, cy - r + 42f)
        path.lineTo(cx, cy - r + 26f)
        path.lineTo(cx - 16f, cy - r + 42f)
        path.close()
        c.drawPath(path, needlePaint)
    }
}
GARMIN_PATCH_EOF
echo "  كتب: app/src/main/java/com/alruwaili/garminnav/CompassActivity.kt"

mkdir -p "$(dirname "app/src/main/java/com/alruwaili/garminnav/SatellitesActivity.kt")"
cat > "app/src/main/java/com/alruwaili/garminnav/SatellitesActivity.kt" << 'GARMIN_PATCH_EOF'
package com.alruwaili.garminnav

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * شاشة حالة الأقمار الصناعية (فكرة مأخوذة من شاشة Satellites في قارمن، شيفرة
 * أصلية مكتوبة من الصفر): تعرض عدد الأقمار المرئية حالياً، وعدد المُستخدَم منها
 * فعلياً في حساب الموقع — مؤشر مباشر على قوة إشارة GPS في مكانك الحالي.
 */
class SatellitesActivity : Activity() {

    private lateinit var visibleValue: TextView
    private lateinit var usedValue: TextView
    private lateinit var statusText: TextView
    private var lm: LocationManager? = null

    private val callback: GnssStatus.Callback? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    render(status)
                }
            }
        } else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(GTheme.BEZEL)
        root.setPadding(dp(30), dp(50), dp(30), dp(30))

        val title = TextView(this)
        title.text = "الأقمار الصناعية"
        title.textDirection = View.TEXT_DIRECTION_RTL
        title.textSize = 26f
        title.setTextColor(GTheme.TEXT)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, dp(30))
        root.addView(title)

        val row1 = statRow("الأقمار المرئية")
        visibleValue = row1.second
        root.addView(row1.first)

        val row2 = statRow("مستخدَمة في تحديد الموقع")
        usedValue = row2.second
        root.addView(row2.first)

        statusText = TextView(this)
        statusText.textDirection = View.TEXT_DIRECTION_RTL
        statusText.textSize = 14f
        statusText.setTextColor(GTheme.MUTED)
        statusText.gravity = Gravity.CENTER
        statusText.setPadding(0, dp(24), 0, 0)
        root.addView(statusText)

        setContentView(root)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun statRow(label: String): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutDirection = View.LAYOUT_DIRECTION_RTL
        row.setBackgroundColor(GTheme.BAR_BG)
        row.setPadding(dp(20), dp(24), dp(20), dp(24))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        row.layoutParams = lp

        val l = TextView(this)
        l.text = label
        l.textDirection = View.TEXT_DIRECTION_RTL
        l.textSize = 17f
        l.setTextColor(GTheme.MUTED)
        l.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val v = TextView(this)
        v.text = "٠"
        v.textSize = 24f
        v.setTextColor(GTheme.ACCENT)
        v.gravity = Gravity.END

        row.addView(l)
        row.addView(v)
        return row to v
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "إذن الموقع غير ممنوح — افتح الشاشة الرئيسية أولاً لمنحه"
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            statusText.text = "هذه الميزة تحتاج أندرويد ٧ فأعلى"
            return
        }
        statusText.text = "جارٍ البحث عن إشارة…"
        try {
            callback?.let { lm?.registerGnssStatusCallback(it, Handler(Looper.getMainLooper())) }
        } catch (e: Exception) {
            statusText.text = "تعذّر قراءة حالة الأقمار على هذا الجهاز"
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            callback?.let { lm?.unregisterGnssStatusCallback(it) }
        } catch (e: Exception) {
        }
    }

    private fun render(status: GnssStatus) {
        val total = status.satelliteCount
        var used = 0
        for (i in 0 until total) {
            if (status.usedInFix(i)) used++
        }
        visibleValue.text = total.toString()
        usedValue.text = used.toString()
        statusText.text = if (used > 0) "تحديد موقع نشط" else "بانتظار قفل إشارة كافية…"
    }
}
GARMIN_PATCH_EOF
echo "  كتب: app/src/main/java/com/alruwaili/garminnav/SatellitesActivity.kt"

mkdir -p "$(dirname "app/src/main/java/com/alruwaili/garminnav/SavedPointsActivity.kt")"
cat > "app/src/main/java/com/alruwaili/garminnav/SavedPointsActivity.kt" << 'GARMIN_PATCH_EOF'
package com.alruwaili.garminnav

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Date

/**
 * قائمة النقاط المحفوظة (الفكرة مأخوذة من شاشة MyLocationsActivity في قارمن،
 * شيفرة أصلية بالكامل): الضغط على نقطة يُرجع إحداثياتها للشاشة الرئيسية
 * لتتوسّط الخريطة عليها، وأيقونة السلة تحذفها.
 */
class SavedPointsActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(GTheme.BEZEL)
        root.setPadding(dp(24), dp(30), dp(24), dp(16))

        val title = TextView(this)
        title.text = "النقاط المحفوظة"
        title.textDirection = View.TEXT_DIRECTION_RTL
        title.textSize = 22f
        title.setTextColor(GTheme.TEXT)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, dp(20))
        root.addView(title)

        emptyText = TextView(this)
        emptyText.text = "لا توجد نقاط محفوظة بعد — اضغط زر «📍 علامة» في الشاشة الرئيسية لحفظ موقعك الحالي"
        emptyText.textDirection = View.TEXT_DIRECTION_RTL
        emptyText.textSize = 15f
        emptyText.setTextColor(GTheme.MUTED)
        emptyText.gravity = Gravity.CENTER
        emptyText.visibility = View.GONE
        root.addView(emptyText)

        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun reload() {
        listBox.removeAllViews()
        val points = SavedData.listPoints(this)
        emptyText.visibility = if (points.isEmpty()) View.VISIBLE else View.GONE
        for (p in points) listBox.addView(pointRow(p))
    }

    private fun pointRow(p: SavedPoint): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutDirection = View.LAYOUT_DIRECTION_RTL
        row.setPadding(dp(14), dp(16), dp(14), dp(16))
        row.setBackgroundColor(GTheme.BAR_BG)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(6)
        row.layoutParams = lp

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val nameTv = TextView(this)
        nameTv.text = p.name
        nameTv.textDirection = View.TEXT_DIRECTION_RTL
        nameTv.textSize = 17f
        nameTv.setTextColor(GTheme.TEXT)
        col.addView(nameTv)

        val dateTv = TextView(this)
        dateTv.text = DateFormat.format("yyyy-MM-dd HH:mm", Date(p.timeMs)).toString()
        dateTv.textSize = 12f
        dateTv.setTextColor(GTheme.MUTED)
        col.addView(dateTv)

        col.setOnClickListener {
            val data = Intent()
            data.putExtra("lon", p.lon)
            data.putExtra("lat", p.lat)
            setResult(Activity.RESULT_OK, data)
            finish()
        }

        val delTv = TextView(this)
        delTv.text = "🗑"
        delTv.textSize = 20f
        delTv.setPadding(dp(16), 0, dp(4), 0)
        delTv.setOnClickListener {
            SavedData.deletePoint(this, p)
            reload()
        }

        row.addView(col)
        row.addView(delTv)
        return row
    }
}
GARMIN_PATCH_EOF
echo "  كتب: app/src/main/java/com/alruwaili/garminnav/SavedPointsActivity.kt"

mkdir -p "$(dirname "app/src/main/java/com/alruwaili/garminnav/TracksActivity.kt")"
cat > "app/src/main/java/com/alruwaili/garminnav/TracksActivity.kt" << 'GARMIN_PATCH_EOF'
package com.alruwaili.garminnav

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Date

/**
 * قائمة المسارات المسجَّلة سابقاً (الفكرة مأخوذة من شاشة Tracks في قارمن،
 * شيفرة أصلية بالكامل): تاريخ البدء، المدة، والمسافة لكل مسار. الضغط على مسار
 * يُرجع وقت بدايته (معرِّفه) للشاشة الرئيسية لتعرض خطّه على الخريطة.
 */
class TracksActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(GTheme.BEZEL)
        root.setPadding(dp(24), dp(30), dp(24), dp(16))

        val title = TextView(this)
        title.text = "المسارات"
        title.textDirection = View.TEXT_DIRECTION_RTL
        title.textSize = 22f
        title.setTextColor(GTheme.TEXT)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, dp(20))
        root.addView(title)

        emptyText = TextView(this)
        emptyText.text = "لا توجد مسارات مسجَّلة بعد — اضغط زر «↪ مسار» في الشاشة الرئيسية لبدء التسجيل، ثم اضغطه مرة أخرى لإنهائه وحفظه"
        emptyText.textDirection = View.TEXT_DIRECTION_RTL
        emptyText.textSize = 15f
        emptyText.setTextColor(GTheme.MUTED)
        emptyText.gravity = Gravity.CENTER
        emptyText.visibility = View.GONE
        root.addView(emptyText)

        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        listBox.removeAllViews()
        val tracks = SavedData.listTracks(this)
        emptyText.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        for (t in tracks) listBox.addView(trackRow(t))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun trackRow(t: TrackSession): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.layoutDirection = View.LAYOUT_DIRECTION_RTL
        row.setPadding(dp(14), dp(16), dp(14), dp(16))
        row.setBackgroundColor(GTheme.BAR_BG)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(6)
        row.layoutParams = lp

        val dateTv = TextView(this)
        dateTv.text = DateFormat.format("yyyy-MM-dd HH:mm", Date(t.startMs)).toString()
        dateTv.textSize = 16f
        dateTv.setTextColor(GTheme.TEXT)
        row.addView(dateTv)

        val durationMin = (t.endMs - t.startMs) / 60000.0
        val infoTv = TextView(this)
        infoTv.text = "المسافة: ${Prefs.formatDistance(this, t.distanceM)} — المدة: ${String.format("%.0f دقيقة", durationMin)}"
        infoTv.textSize = 13f
        infoTv.setTextColor(GTheme.MUTED)
        row.addView(infoTv)

        row.setOnClickListener {
            val data = Intent()
            data.putExtra("viewTrackStartMs", t.startMs)
            setResult(Activity.RESULT_OK, data)
            finish()
        }

        return row
    }
}
GARMIN_PATCH_EOF
echo "  كتب: app/src/main/java/com/alruwaili/garminnav/TracksActivity.kt"

mkdir -p "$(dirname "app/src/main/java/com/alruwaili/garminnav/EmergencyActivity.kt")"
cat > "app/src/main/java/com/alruwaili/garminnav/EmergencyActivity.kt" << 'GARMIN_PATCH_EOF'
package com.alruwaili.garminnav

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * شاشة طوارئ بسيطة: تعرض إحداثياتك الحالية بخط كبير وواضح، وزر مشاركة فورية
 * عبر صندوق المشاركة القياسي في أندرويد (رسائل نصية، واتساب، أي تطبيق مثبّت).
 * الفكرة مأخوذة من شاشة "إرسال الموقع" في تطبيقات قارمن، لكن بتطبيق مستقل
 * بشيفرة أصلية، وعبر صندوق مشاركة عام بدل الاعتماد على الرسائل النصية فقط.
 */
class EmergencyActivity : Activity() {

    private lateinit var coordsText: TextView
    private lateinit var infoText: TextView
    private var lastFix: Location? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(GTheme.BEZEL)
        root.setPadding(dp(30), dp(50), dp(30), dp(30))
        root.gravity = Gravity.CENTER

        val title = TextView(this)
        title.text = "الطوارئ — موقعي الحالي"
        title.textDirection = View.TEXT_DIRECTION_RTL
        title.textSize = 22f
        title.setTextColor(GTheme.TEXT)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, dp(30))
        root.addView(title)

        coordsText = TextView(this)
        coordsText.text = "جارٍ تحديد الموقع…"
        coordsText.textDirection = View.TEXT_DIRECTION_RTL
        coordsText.textSize = 24f
        coordsText.setTextColor(GTheme.ACCENT)
        coordsText.gravity = Gravity.CENTER
        root.addView(coordsText)

        infoText = TextView(this)
        infoText.textDirection = View.TEXT_DIRECTION_RTL
        infoText.textSize = 14f
        infoText.setTextColor(GTheme.MUTED)
        infoText.gravity = Gravity.CENTER
        infoText.setPadding(0, dp(10), 0, dp(40))
        root.addView(infoText)

        val shareBtn = Button(this)
        shareBtn.text = "مشاركة موقعي الآن"
        shareBtn.setOnClickListener { shareLocation() }
        root.addView(shareBtn)

        setContentView(root)
        loadLastKnown()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun loadLastKnown() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            coordsText.text = "إذن الموقع غير ممنوح"
            return
        }
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    val l = lm.getLastKnownLocation(p) ?: continue
                    val cur = best
                    if (cur == null || l.time > cur.time) best = l
                } catch (e: Exception) {
                }
            }
            val found = best
            if (found != null) {
                lastFix = found
                render(found)
            } else {
                coordsText.text = "لا يوجد تحديد موقع حديث"
                infoText.text = "افتح الشاشة الرئيسية أولاً حتى يلتقط الجهاز إشارة GPS، ثم عُد لهذه الشاشة"
            }
        } catch (e: SecurityException) {
            coordsText.text = "إذن الموقع غير ممنوح"
        }
    }

    private fun render(loc: Location) {
        coordsText.text = String.format("%.5f, %.5f", loc.latitude, loc.longitude)
        val ageSec = (System.currentTimeMillis() - loc.time) / 1000
        infoText.text = "آخر تحديث: منذ $ageSec ثانية — الدقة: ${loc.accuracy.toInt()} متر"
    }

    private fun shareLocation() {
        val loc = lastFix
        if (loc == null) {
            Toast.makeText(this, "لا يوجد موقع محدَّد بعد", Toast.LENGTH_SHORT).show()
            return
        }
        val mapsUrl = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
        val text = "موقعي الحالي: ${String.format("%.5f, %.5f", loc.latitude, loc.longitude)}\n$mapsUrl"
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "مشاركة الموقع عبر"))
    }
}
GARMIN_PATCH_EOF
echo "  كتب: app/src/main/java/com/alruwaili/garminnav/EmergencyActivity.kt"

echo "تم! الآن نفّذ: git add -A && git commit -m \"...\" && git push origin test-push، ثم شغّل البناء من تبويب Actions."
