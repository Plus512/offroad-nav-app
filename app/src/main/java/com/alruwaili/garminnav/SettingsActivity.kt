package com.alruwaili.garminnav

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/** شاشة إعدادات حقيقية وبسيطة: حالة الخريطة ومعلومات التطبيق. */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(GTheme.BEZEL)
        root.setPadding(dp(30), dp(50), dp(30), dp(30))

        root.addView(title("الإعدادات"))

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
