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
