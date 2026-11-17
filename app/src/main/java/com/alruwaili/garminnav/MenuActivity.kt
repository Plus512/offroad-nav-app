package com.alruwaili.garminnav

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
        setContentView(scroll)
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
                "الطوارئ", "الأقمار الصناعية", "البوصلة", "المسارات", "النقاط المحفوظة", "بحث عن موقع" ->
                    Toast.makeText(this, "$label — قيد الإنشاء", Toast.LENGTH_SHORT).show()
                "استيراد خريطة", "الخرائط" -> downloadMap()
            }
        }
        return cell
    }

    /**
     * تنزيل حقيقي لملف الخريطة من GitHub إلى تخزين التطبيق.
     * ملاحظة صريحة: هذا يضع الملف على الجهاز فقط. رسم الطرق الفعلية منه
     * لم يُفعَّل بعد (انظر MapCanvasView) — هذا عمل منفصل لم يكتمل.
     */
    private fun downloadMap() {
        val mapsDir = File(filesDir, "maps")
        if (!mapsDir.exists()) mapsDir.mkdirs()
        val dest = File(mapsDir, "almisharIMAP.img")
        val main = Handler(Looper.getMainLooper())

        Toast.makeText(this, "جارٍ تنزيل الخريطة…", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val conn = URL(MAP_DOWNLOAD_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.connect()
                if (conn.responseCode != 200) {
                    throw Exception("رمز استجابة الخادم: ${conn.responseCode}")
                }
                conn.inputStream.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                main.post {
                    Toast.makeText(
                        this,
                        "اكتمل التنزيل (${dest.length() / 1024 / 1024} ميجا) — الطرق لم تُفعَّل بعد",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                main.post {
                    Toast.makeText(this, "فشل التنزيل: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
