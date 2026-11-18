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
