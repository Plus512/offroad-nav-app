package com.alruwaili.garminnav

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

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

        for ((icon, label) in items) {
            grid.addView(menuItem(icon, label))
        }
        root.addView(grid)

        setContentView(root)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun menuItem(icon: String, label: String): LinearLayout {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER
        cell.setBackgroundColor(GTheme.BAR_BG)
        cell.setPadding(dp(14), dp(24), dp(14), dp(24))

        val lp = GridLayout.LayoutParams()
        lp.width = dp(155)
        lp.height = dp(150)
        lp.setMargins(dp(8), dp(8), dp(8), dp(8))
        cell.layoutParams = lp

        val ic = TextView(this)
        ic.text = icon
        ic.textSize = 38f
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
                "الإعدادات", "الطوارئ", "الأقمار الصناعية", "البوصلة", "المسارات", "النقاط المحفوظة", "بحث عن موقع" ->
                    Toast.makeText(this, "$label — قيد الإنشاء", Toast.LENGTH_SHORT).show()
                "استيراد خريطة", "الخرائط" -> {
                    Toast.makeText(this, "استيراد ملف قارمن .img — قيد الإنشاء", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return cell
    }
}
