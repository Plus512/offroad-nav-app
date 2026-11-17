package com.alruwaili.garminnav

import android.Manifest
import android.app.Activity
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.roundToInt

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

    private lateinit var sm: SensorManager
    private var rotSensor: Sensor? = null
    private var smoothHeading = Float.NaN

    private var lm: LocationManager? = null
    private var totalDistM = 0.0
    private var lastLoc: Location? = null

    private var zoomLevel = 5
    private var tracking = false
    private var trackStartDistM = 0.0

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
            startActivity(Intent(this, MenuActivity::class.java))
        })
        bottomBar.addView(garminButton("📍", "علامة") { markWaypoint() })
        bottomBar.addView(garminButton("🔍", "تكبير") { changeZoom(1) })
        bottomBar.addView(garminButton("↪", "مسار") { toggleTracking() })
        root.addView(bottomBar, bottomLp)

        setContentView(root)

        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager

        askPerms()
    }

    override fun onStart() {
        super.onStart()
        // تحديث شاشة الخريطة إن عاد المستخدم من تنزيل خريطة جديدة في القائمة.
        map.mapLoaded = mapFile().exists()
    }

    private fun mapFile(): File = File(filesDir, "maps/almisharIMAP.img")

    private fun changeZoom(delta: Int) {
        zoomLevel = (zoomLevel + delta).coerceIn(1, 10)
        map.zoomLevel = zoomLevel
        Toast.makeText(this, "مستوى التكبير: $zoomLevel", Toast.LENGTH_SHORT).show()
    }

    private fun toggleTracking() {
        tracking = !tracking
        if (tracking) {
            trackStartDistM = totalDistM
            Toast.makeText(this, "بدأ تسجيل المسار", Toast.LENGTH_SHORT).show()
        } else {
            val d = (totalDistM - trackStartDistM) / 1000.0
            Toast.makeText(this, String.format("انتهى المسار: %.2f كم", d), Toast.LENGTH_LONG).show()
        }
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

    private fun markWaypoint() {
        // حفظ نقطة الموقع الحالي — لاحقاً يُربط بقاعدة بيانات نقاط محفوظة.
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
        tvSpeed.text = (loc.speed * 3.6).roundToInt().toString()
        lastLoc?.let { totalDistM += it.distanceTo(loc) }
        lastLoc = loc
        tvDist.text = String.format("%.1f", totalDistM / 1000.0)
    }

    @Deprecated("deprecated in API") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // ---------- البوصلة ----------

    override fun onResume() {
        super.onResume()
        rotSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
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
